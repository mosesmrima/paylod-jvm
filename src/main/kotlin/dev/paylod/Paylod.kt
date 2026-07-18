package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.RealTimeSource
import dev.paylod.internal.TimeSource
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The base URL. Identical for every paylod customer, so it is baked in — you never pass it. */
const val DEFAULT_BASE_URL = "https://paylod.dev/functions/v1"

private const val MAX_AMOUNT = 150_000
private const val DEFAULT_WAIT_TIMEOUT_MS = 120_000L

// The ceiling on any single sleep when the operation has no absolute deadline of its own.
private const val MAX_UNBOUNDED_SLEEP_MS = 60_000L

// Ramp: quick first look, then ease off. Capped at 5s. Values in ms.
private val POLL_SCHEDULE_MS = longArrayOf(1_000, 1_000, 1_500, 2_000, 2_500, 3_000, 4_000, 5_000)

/**
 * The paylod API client — the JVM (Kotlin + Java) client for the hosted paylod M-Pesa API.
 *
 * Construction takes an API key and nothing else. The base URL is the same for every customer, so
 * it is baked in; there is no config object to assemble, no endpoint to look up, and no OAuth token
 * to fetch and refresh.
 *
 * ```
 * val paylod = Paylod("mp_live_...")
 * val outcome = paylod.collectAndWait("0712345678", 100, idempotencyKey = attempt.id)
 * if (outcome.paid) fulfil(outcome.receipt) else toast(outcome.message)
 * ```
 *
 * Sync API for v1. All calls block the calling thread; run them off your request thread (or from a
 * coroutine's `Dispatchers.IO`) if that matters. A future release could add coroutine/`CompletableFuture`
 * variants without changing this surface.
 */
class Paylod internal constructor(
    apiKeyArg: String?,
    options: PaylodOptions,
    private val time: TimeSource,
    private val random: java.util.Random,
) {
    private val apiKey: String
    private val baseUrl: String
    private val webhookSecret: String?
    private val timeoutMs: Long
    private val maxRetries: Int
    private val transport: HttpTransport
    private val simulateMode: Boolean

    /**
     * The sandbox simulator: drive a payment to any of the five outcomes from a test file, with no
     * phone. Every method refuses a `mp_live_` key locally.
     */
    @JvmField
    val simulate: Simulator

    init {
        val env: (String) -> String? = { System.getenv(it) }
        val key = apiKeyArg ?: options.apiKey ?: env("PAYLOD_API_KEY")
        if (key == null || key.trim().isEmpty()) {
            throw PaylodConfigException(
                "No paylod API key. Pass one — Paylod(System.getenv(\"PAYLOD_API_KEY\")) — or set " +
                    "the PAYLOD_API_KEY environment variable. This key can move money: keep it on a " +
                    "server and never ship it to a browser or mobile app.",
            )
        }
        apiKey = key.trim()
        // Validate the key's SYNTAX before it can ever be interpolated into an Authorization header.
        // The JDK's header validator would otherwise reject it later with the full token inside the
        // exception message.
        assertValidApiKey(apiKey)

        baseUrl = (options.baseUrl ?: env("PAYLOD_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/')
        // Reject a plaintext / non-canonical origin BEFORE any key can leave the process. Loopback
        // HTTP is allowed only behind an explicit test-only flag, and never with a live key.
        assertSecureBaseUrl(baseUrl, apiKey, options.allowInsecureBaseUrl)
        webhookSecret = options.webhookSecret ?: env("PAYLOD_WEBHOOK_SECRET")
        timeoutMs = options.timeoutMs
        maxRetries = options.maxRetries
        transport = options.transport ?: JdkHttpTransport()

        // Simulator mode is a TEST posture, fenced off from production at CONSTRUCTION time.
        simulateMode = options.simulate
        if (simulateMode) assertSandboxKey(apiKey, "Paylod(options.simulate = true)")

        simulate = Simulator(apiKey) { method, path, body, idem -> request(method, path, body, idem) }
    }

    /** Construct with an explicit API key (or `null` to read `PAYLOD_API_KEY`) and options. */
    @JvmOverloads
    constructor(apiKey: String? = null, options: PaylodOptions = PaylodOptions.of()) :
        this(apiKey, options, RealTimeSource, java.util.Random())

    /** Everything-in-one-object form. */
    constructor(options: PaylodOptions) :
        this(options.apiKey, options, RealTimeSource, java.util.Random())

    // ── HTTP ────────────────────────────────────────────────────────────────────────────────

    private fun request(
        method: String,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        deadlineMs: Long? = null,
        validate: ((Map<String, Any?>, Int) -> Unit)? = null,
    ): Map<String, Any?> {
        val url = "$baseUrl$path"
        var lastError: PaylodException? = null

        // Serialize the body ONCE, before the retry loop. Re-serialising on each attempt would let a
        // mutable `metadata` map be mutated under a FIXED Idempotency-Key — the second attempt would
        // send a different body than the first, defeating the double-charge guard.
        val bodyStr = if (body == null) null else Json.write(body)

        var attempt = 0
        while (attempt <= maxRetries) {
            if (attempt > 0) boundedSleep(jitter(250L shl (attempt - 1)), deadlineMs)

            // Cap this request to whatever time the WHOLE operation has left. A 30s per-request
            // timeout must never let a wait({ timeoutMs = 5000 }) run for 30s.
            var perRequestTimeout = timeoutMs
            val remaining = remaining(deadlineMs)
            if (remaining != null) {
                if (remaining <= 0) break // out of time — surface the last error / a timeout below
                perRequestTimeout = Math.min(perRequestTimeout, remaining)
            }

            val headers = LinkedHashMap<String, String>()
            headers["authorization"] = "Bearer $apiKey"
            headers["accept"] = "application/json"
            if (body != null) headers["content-type"] = "application/json"
            if (idempotencyKey != null) headers["idempotency-key"] = idempotencyKey

            // Wrapped so that PRINTING the header map — `spec.headers.toString()`, a string template,
            // a structured-log field dump — can never render the bearer token. Lookups are unaffected.
            val spec = HttpRequestSpec(method, url, RedactingHeaders(headers), bodyStr, perRequestTimeout)

            val response: HttpResponseSpec = try {
                transport.execute(spec)
            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            }
            // A PaylodInterruptedException from the transport is deliberately NOT caught here: an
            // interrupt is a cancellation, not a transient blip, so it propagates without a retry.

            // A 3xx is REFUSED here, not followed and not retried — and this is the check that actually
            // enforces the no-redirect guarantee, because the SDK does not control an injected
            // `HttpTransport`. A custom transport built on a redirect-following client would replay
            // `Authorization: Bearer` to the 3xx target's host. We cannot stop third-party code from
            // doing that, but the SDK itself never treats a redirect as something to chase: the API
            // never legitimately redirects, so a 3xx is a misconfiguration or an attack, and it is
            // terminal.
            if (response.status in 300..399) {
                throw PaylodApiException(
                    "paylod responded ${response.status} (a redirect). The paylod API never redirects, " +
                        "and a redirect is never followed: doing so would replay your API key to the " +
                        "redirect target. Check baseUrl and any proxy in front of it.",
                    response.status,
                    null,
                    idempotencyKey,
                    indeterminate = true,
                )
            }

            val text = response.body
            val parsed: Any? = try {
                if (text.isEmpty()) null else Json.parse(text)
            } catch (e: Exception) {
                text
            }

            if (response.status in 200..299) {
                @Suppress("UNCHECKED_CAST")
                val map = parsed as? Map<String, Any?> ?: emptyMap()
                // A malformed 2xx (e.g. no payment id) is INDETERMINATE — the validator throws rather
                // than let an empty shape through. A 2xx is never retried, so this propagates at once.
                validate?.invoke(map, response.status)
                return map
            }

            val message = ((parsed as? Map<*, *>)?.get("error") as? String) ?: "paylod responded ${response.status}"
            val apiError = PaylodApiException(message, response.status, parsed, idempotencyKey)

            // 429 / 5xx are transient. A 409 is retried ONLY when it is explicitly "same key still in
            // progress" — every other 409 (body conflict, indeterminate) is a real, terminal answer.
            val transient = response.status == 429 || response.status >= 500
            val inProgress = response.status == 409 && IN_PROGRESS_409_RE.containsMatchIn(message)
            if ((!transient && !inProgress) || attempt == maxRetries) throw apiError

            lastError = apiError
            // Honour Retry-After — read case-insensitively, in BOTH RFC 9110 forms, and clamped to the
            // operation deadline. It is deliberately NOT truncated to a flat 10s: a server that says
            // "wait 60s" is telling us the request is still in flight on its side, and retrying at 10s
            // under the same Idempotency-Key just adds load to a system already asking for room.
            // If the header is absent, the top-of-loop backoff covers the wait.
            val retryAfterMs = parseRetryAfterMs(headerValue(response.headers, "retry-after"), time.nowMillis())
            if (retryAfterMs != null) boundedSleep(retryAfterMs, deadlineMs)
            attempt++
        }

        throw lastError ?: PaylodConnectionException("Request to $url failed")
    }

    /**
     * Remaining time to the deadline, or `null` when there is no deadline.
     *
     * Deadlines are MONOTONIC. Using the wall clock here would mean an NTP step or a manual clock
     * change moved the budget: a backwards adjustment during a `wait()` makes `remaining` grow, and
     * polling continues past the deadline the caller was promised.
     */
    private fun remaining(deadlineMs: Long?): Long? =
        if (deadlineMs == null) null else deadlineMs - time.monotonicMillis()

    /**
     * A sleep clamped to the operation deadline, so a backoff — or a server-dictated `Retry-After` —
     * can never push past [wait]'s cap. When there is NO deadline (a bare `collect()`), the delay is
     * still ceilinged, so a hostile or broken `Retry-After: 86400` cannot park a caller's thread for
     * a day.
     */
    private fun boundedSleep(ms: Long, deadlineMs: Long?) {
        var capped = ms
        val rem = remaining(deadlineMs)
        capped = if (rem != null) Math.min(capped, Math.max(0L, rem)) else Math.min(capped, MAX_UNBOUNDED_SLEEP_MS)
        if (capped > 0) time.sleep(capped)
    }

    private fun jitter(ms: Long): Long = Math.round(ms * (0.8 + random.nextDouble() * 0.4))

    private fun pollDelay(attempt: Int): Long =
        jitter(POLL_SCHEDULE_MS[Math.min(attempt, POLL_SCHEDULE_MS.size - 1)])

    // ── Validation ──────────────────────────────────────────────────────────────────────────

    private fun buildCollectBody(params: CollectParams): Map<String, Any?> {
        val amount = params.amount
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw PaylodInvalidRequestException("amount must be between 1 and $MAX_AMOUNT KES (got $amount).")
        }

        val out = LinkedHashMap<String, Any?>()
        out["amount"] = amount
        out["phone"] = Phone.normalize(params.phone)

        // Validate AND transmit the SAME trimmed representation — never validate the trimmed length
        // while transmitting the untrimmed original (that would let " x…(12 spaces) " slip past a
        // 12-char bound). A provided-but-blank value is rejected rather than sent as empty.
        if (params.accountReference != null) {
            val ref = params.accountReference.trim()
            if (ref.isEmpty()) {
                throw PaylodInvalidRequestException("accountReference must not be blank.")
            }
            if (ref.length > 12) {
                throw PaylodInvalidRequestException("accountReference must be 12 characters or fewer.")
            }
            out["accountReference"] = ref
        }
        if (params.description != null) {
            val desc = params.description.trim()
            if (desc.isEmpty()) {
                throw PaylodInvalidRequestException("description must not be blank.")
            }
            if (desc.length > 64) {
                throw PaylodInvalidRequestException("description must be 64 characters or fewer.")
            }
            out["description"] = desc
        }
        if (params.metadata != null) out["metadata"] = params.metadata
        return out
    }

    // ── Public API ──────────────────────────────────────────────────────────────────────────

    /**
     * Send an STK Push. Returns as soon as the prompt is on the customer's phone — the payment is
     * pending. Settle it with [status], [wait], or a webhook.
     *
     * Pass `idempotencyKey`, and mint ONE KEY PER PAYMENT ATTEMPT. See [CollectParams.idempotencyKey].
     */
    fun collect(params: CollectParams): CollectAck {
        val body = buildCollectBody(params)
        // A caller-supplied key is the double-charge guard — reject a blank/whitespace/control-char
        // one loudly rather than silently drop protection. A generated key is always well-formed.
        if (params.idempotencyKey == null) warnMissingIdempotencyKey()
        else assertValidIdempotencyKey(params.idempotencyKey)
        val idempotencyKey = params.idempotencyKey ?: UUID.randomUUID().toString()

        try {
            if (simulateMode) {
                // Same call, same ack, no handset. The key was proven a sandbox key in the constructor.
                val simParams = SimulateCollectParams.builder()
                    .phone(params.phone)
                    .amount(params.amount)
                    .accountReference(params.accountReference)
                    .description(params.description)
                    .metadata(params.metadata)
                    .idempotencyKey(idempotencyKey)
                    .build()
                val created = simulate.collect(simParams)
                return CollectAck(
                    paymentId = created.paymentId,
                    status = PaymentStatus.PENDING,
                    checkoutRequestId = created.checkoutRequestId,
                    idempotencyKey = idempotencyKey,
                )
            }

            val ack = request("POST", "/collect", body, idempotencyKey) { map, status ->
                // Validate the WHOLE acknowledgement, not just the payment id. A 2xx that is missing
                // any part of the ack shape is a response we do not understand, and the charge behind
                // it may well have moved — so it is INDETERMINATE, never a silently-degraded ack with
                // empty-string fields a caller would go on to use as a real checkout reference.
                fun bad(why: String): Nothing = throw PaylodApiException(
                    "paylod returned a 2xx collect response that is not a valid acknowledgement " +
                        "($why) — the charge state is INDETERMINATE. Read the payment with this " +
                        "idempotencyKey before starting any new attempt; do NOT mint a fresh key " +
                        "(that risks a second charge).",
                    status,
                    map,
                    idempotencyKey,
                    indeterminate = true,
                )

                val id = map["paymentId"]
                if (id !is String || id.isBlank()) bad("no usable paymentId")
                val checkout = map["checkoutRequestId"]
                if (checkout !is String || checkout.isBlank()) bad("no usable checkoutRequestId")
                // `POST /collect` ALWAYS answers 202 with a hardcoded `status: "pending"` — including
                // for an idempotent REPLAY, which returns the STORED original ack rather than the
                // current settled state. So there is no legitimate settled ack, and accepting one
                // would mean trusting a shape the API never emits. Require the literal.
                val wireStatus = map["status"]
                if (wireStatus !is String) bad("status is missing or is not a string")
                if (wireStatus != "pending") {
                    bad("status is \"$wireStatus\", but a collect acknowledgement is always \"pending\"")
                }
            }
            return CollectAck(
                paymentId = ack["paymentId"] as String,
                status = PaymentStatus.PENDING,
                checkoutRequestId = ack["checkoutRequestId"] as String,
                idempotencyKey = idempotencyKey,
            )
        } catch (e: PaylodException) {
            // Whatever went wrong (network, timeout, 5xx, malformed 2xx), the caller MUST be able to
            // recover the effective key and retry with the SAME one — a fresh key would double-charge.
            if (e.idempotencyKey == null) e.idempotencyKey = idempotencyKey
            throw e
        }
    }

    /** Java-friendly overload — no params object needed for the common case. */
    @JvmOverloads
    fun collect(
        phone: String,
        amount: Int,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
    ): CollectAck = collect(CollectParams(phone, amount, accountReference, description, idempotencyKey))

    /** Read a payment. `GET /status/:id`. */
    fun status(paymentId: String): Payment = readPayment(paymentId, null)

    private fun readPayment(paymentId: String, deadlineMs: Long?): Payment {
        if (paymentId.isEmpty()) throw PaylodInvalidRequestException("paymentId is required.")
        val encoded = URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20")
        val p = request("GET", "/status/$encoded", null, null, deadlineMs) { map, status ->
            // Validate the COMPLETE, state-dependent status schema before a Payment is built from it.
            //
            // Checking only `id` was a false-PAID bug: `{"id":"pay_1","status":"success"}` sailed
            // through, `PaymentStatus.fromWire` turned the string into SUCCESS, no result code meant
            // the classifier never ran, and `Outcomes.of` returned `paid = true` with a null receipt.
            // A caller doing the documented `if (outcome.paid) fulfil(...)` shipped the goods on a
            // response carrying no evidence whatsoever that money had moved.
            //
            // So: the status STRING is never sufficient on its own. A terminal success must arrive
            // with something M-Pesa actually said — a receipt, or a result code to classify — and any
            // field of the wrong shape makes the whole body untrustworthy rather than partly usable.
            // Every rejection is INDETERMINATE: not paid, and not proof of failure either.
            fun bad(why: String): Nothing = throw PaylodApiException(
                "paylod returned a malformed 2xx status body ($why). The payment state is " +
                    "INDETERMINATE — this is NOT a confirmed payment and must NOT be fulfilled. Read " +
                    "the payment again, or let the webhook settle it.",
                status,
                map,
                null,
                indeterminate = true,
            )

            val id = map["id"]
            if (id !is String || id.isBlank()) bad("no payment id")

            val wireStatus = map["status"]
            if (wireStatus !is String) bad("status is missing or is not a string")
            val parsedStatus = PaymentStatus.parseWire(wireStatus)
                ?: bad("status \"$wireStatus\" is not one of pending/success/failed")

            // `mpesaReceipt` is THE proof of settlement. A non-string, or a blank string pretending to
            // be one, is not a receipt.
            val receipt = map["mpesaReceipt"]
            if (receipt != null && (receipt !is String || receipt.isBlank())) {
                bad("mpesaReceipt is present but is not a non-empty string")
            }

            // `resultCode` drives the classifier, which outranks the raw status field. A shape it
            // cannot read (a boolean, an object, an empty string) would be normalized into a junk
            // string and decoded as an unknown code — silently changing the outcome.
            val resultCode = map["resultCode"]
            if (resultCode != null && resultCode !is String && resultCode !is Number) {
                bad("resultCode is neither a string nor a number")
            }
            if (resultCode is String && resultCode.isBlank()) bad("resultCode is a blank string")

            val resultDesc = map["resultDesc"]
            if (resultDesc != null && resultDesc !is String) bad("resultDesc is present but is not a string")

            if (parsedStatus == PaymentStatus.SUCCESS && receipt == null && resultCode == null) {
                bad(
                    "status is \"success\" but the body carries NEITHER an mpesaReceipt NOR a " +
                        "resultCode, so nothing in it evidences that money actually moved",
                )
            }
        }
        return Payment(
            id = p["id"] as String,
            status = PaymentStatus.fromWire(p["status"]?.toString()),
            mpesaReceipt = p["mpesaReceipt"]?.toString(),
            resultCode = normalizeResultCode(p["resultCode"]),
            resultDesc = p["resultDesc"]?.toString(),
        )
    }

    /** [status], but already decoded and renderable. This is the one you usually want. */
    fun check(paymentId: String): PaymentOutcome = Outcomes.of(status(paymentId))

    /**
     * Poll an existing payment until it settles, with a backoff ramp (1s -> 5s, jittered).
     *
     * "Settled" is decided by the classifier, not the raw `status` field: a `failed` row carrying
     * code 4999 means the prompt is live and the customer hasn't entered their PIN yet, so this keeps
     * polling.
     *
     * @throws PaylodTimeoutException if still pending at the deadline — deliberately NOT a failed
     *   outcome.
     */
    @JvmOverloads
    fun wait(paymentId: String, options: WaitOptions = WaitOptions.DEFAULT): PaymentOutcome {
        val timeout = options.timeoutMs.takeIf { it > 0 } ?: DEFAULT_WAIT_TIMEOUT_MS
        // Monotonic: the caller's timeout is a DURATION, and must not be lengthened or shortened by a
        // wall-clock adjustment landing mid-wait.
        val startedAt = time.monotonicMillis()
        val deadline = startedAt + timeout

        var last: Payment? = null
        var attempt = 0
        while (true) {
            // Propagate the wait's deadline into each poll so no single status read can hang past it.
            val payment = readPayment(paymentId, deadline)
            last = payment

            val outcome = Outcomes.of(payment)
            if (outcome.status != OutcomeStatus.PENDING) return outcome
            options.onPoll?.onPoll(payment)

            val delay = pollDelay(attempt)
            if (time.monotonicMillis() + delay >= deadline) break
            time.sleep(delay)
            attempt++
        }

        throw PaylodTimeoutException(paymentId, last!!, time.monotonicMillis() - startedAt)
    }

    /**
     * [collect] + [wait]. The one-liner most integrations actually want: ring the phone, wait for
     * the PIN, hand back something you can render.
     */
    @JvmOverloads
    fun collectAndWait(params: CollectParams, options: WaitOptions = WaitOptions.DEFAULT): PaymentOutcome {
        val ack = collect(params)
        try {
            return wait(ack.paymentId, options)
        } catch (e: PaylodException) {
            // The collect was ACKNOWLEDGED before this threw — an STK prompt is live on a handset and a
            // charge exists under `ack.idempotencyKey`. Every failure from here on (poll timeout,
            // transport blip, 5xx, malformed status body, interrupt) previously arrived with
            // idempotencyKey = null, because `wait()` reads a payment and never sees a key. A caller
            // following the documented "retry with the key on the exception" rule then found none,
            // minted a fresh one, and charged the customer a SECOND time.
            //
            // Attach both handles to the live charge, without overwriting anything already set deeper
            // in the stack.
            if (e.idempotencyKey == null) e.idempotencyKey = ack.idempotencyKey
            if (e.paymentId == null) e.paymentId = ack.paymentId
            throw e
        }
    }

    /** Java-friendly overload of [collectAndWait]. */
    @JvmOverloads
    fun collectAndWait(
        phone: String,
        amount: Int,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
        options: WaitOptions = WaitOptions.DEFAULT,
    ): PaymentOutcome =
        collectAndWait(CollectParams(phone, amount, accountReference, description, idempotencyKey), options)

    /**
     * Decode an M-Pesa result code offline. No network, no API key needed at call time. The strings
     * are identical to the ones the API puts in `event.data.decoded`.
     */
    @JvmOverloads
    fun decodeError(resultCode: Any?, rawDesc: String? = null): DecodedError =
        DarajaCatalog.decodeError(resultCode, rawDesc)

    /**
     * Verify a raw webhook body + signature header, returning `true`/`false`. HMAC-SHA256 over
     * `${t}.${rawBody}`, `t=,v1=` header, 300s tolerance. Use [parseWebhook] when you want the event.
     */
    @JvmOverloads
    fun verifyWebhook(
        rawBody: String,
        signatureHeader: String?,
        secret: String? = null,
        toleranceSec: Long = Webhooks.DEFAULT_TOLERANCE_SEC,
        nowSec: Long? = null,
    ): Boolean = Webhooks.verify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec, nowSec)

    /** ByteArray overload of [verifyWebhook] — pass the exact raw bytes that arrived. */
    @JvmOverloads
    fun verifyWebhook(
        rawBody: ByteArray,
        signatureHeader: String?,
        secret: String? = null,
        toleranceSec: Long = Webhooks.DEFAULT_TOLERANCE_SEC,
        nowSec: Long? = null,
    ): Boolean = Webhooks.verify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec, nowSec)

    /**
     * Verify a webhook and return the typed [WebhookEvent]. Throws
     * [PaylodSignatureVerificationException] on any failure — never returns a half-trusted value.
     */
    @JvmOverloads
    fun parseWebhook(
        rawBody: String,
        signatureHeader: String?,
        secret: String? = null,
        toleranceSec: Long = Webhooks.DEFAULT_TOLERANCE_SEC,
        nowSec: Long? = null,
    ): WebhookEvent = Webhooks.parseAndVerify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec, nowSec)

    private fun normalizeResultCode(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private companion object {
        val warned = AtomicBoolean(false)

        /**
         * A `409` retried only when it is explicitly the "same key still running" case. Every other
         * 409 (body conflict, indeterminate) is a real answer and must NOT be retried.
         */
        val IN_PROGRESS_409_RE = Regex("already in progress", RegexOption.IGNORE_CASE)

        /**
         * The EXACT hosts a paylod key may ever be sent to. Not a suffix match — `endsWith(".paylod.dev")`
         * would accept any subdomain, including one an attacker gets to point somewhere else.
         *
         * `api.paylod.dev` does not route today. It is listed anyway because both names are owned by
         * paylod, so allowing it cannot let a key reach a host the owner does not control — and its
         * absence would mean published SDKs hard-reject a legitimate future migration to that host.
         */
        val ALLOWED_HOSTS = setOf("paylod.dev", "api.paylod.dev")

        /** The canonical origin, for error messages. */
        const val CANONICAL_HOST = "paylod.dev"

        private fun isLoopbackHost(host: String): Boolean =
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

        /**
         * Enforce a secure ORIGIN for `baseUrl` — an allowlist, not merely a scheme check.
         *
         * HTTPS alone is not enough. `PAYLOD_BASE_URL=https://attacker.example` is still HTTPS, and it
         * would ship a live `Authorization: Bearer mp_live_…` header to an origin of the attacker's
         * choosing — a full credential handover from one environment variable. So the host itself must
         * be one of [ALLOWED_HOSTS] exactly. On top of that we refuse the URL shapes that are used to
         * smuggle a different effective origin past a naive check:
         *
         *   • userinfo (`https://paylod.dev@attacker.example/…`) — the real host is `attacker.example`
         *   • a missing/empty host, and raw IP literals (private, link-local, loopback, or otherwise)
         *   • an unexpected port — HTTPS on 443 only
         *   • a query string or fragment on what is supposed to be a bare API root
         *
         * Loopback stays permitted for local development and the test suite — in EITHER scheme, since
         * an `https://localhost` dev server is no more the canonical origin than a plaintext one — but
         * ONLY behind the explicit `allowInsecureBaseUrl` opt-in and NEVER with a live (`mp_live_`) key.
         */
        fun assertSecureBaseUrl(baseUrl: String, apiKey: String, allowInsecure: Boolean) {
            val parsed = try {
                URI(baseUrl)
            } catch (e: Exception) {
                throw PaylodConfigException("baseUrl is not a valid URL: \"$baseUrl\".")
            }
            val scheme = parsed.scheme?.lowercase()
            val isLive = apiKey.startsWith("mp_live_")

            fun reject(why: String): Nothing = throw PaylodConfigException(
                "baseUrl is not an allowed paylod origin: $why (got \"$baseUrl\"). The API key can move " +
                    "money, so it is only ever sent to https://$CANONICAL_HOST. Loopback HTTP " +
                    "(localhost, 127.0.0.1) is allowed ONLY with allowInsecureBaseUrl = true and NEVER " +
                    "with an mp_live_ key.",
            )

            // `URI` exposes userinfo separately, and reports a null host for the authority shapes it
            // cannot parse — both are treated as fatal rather than ignored.
            if (parsed.userInfo != null) reject("the URL carries userinfo, which hides the real host")
            val host = (parsed.host ?: "").lowercase()
            if (host.isEmpty()) reject("it has no host")
            if (parsed.rawQuery != null) reject("it has a query string")
            if (parsed.rawFragment != null) reject("it has a fragment")

            // Loopback is a DEVELOPMENT posture, and the gate is the same whichever scheme it wears.
            // Handling it first means an `https://localhost` dev server cannot slip through on the
            // strength of its scheme alone — it needs the explicit opt-in exactly like plaintext does,
            // and a live key is refused on both.
            if (isLoopbackHost(host)) {
                if (!allowInsecure) {
                    reject("loopback requires the test-only allowInsecureBaseUrl = true opt-in")
                }
                if (isLive) reject("a live mp_live_ key is never sent to a loopback host")
                if (scheme != "http" && scheme != "https") reject("\"$scheme\" is not an http(s):// URL")
                return
            }

            if (scheme == "https") {
                if (parsed.port != -1 && parsed.port != 443) reject("HTTPS is only allowed on port 443")
                if (host !in ALLOWED_HOSTS) {
                    reject("\"$host\" is not one of ${ALLOWED_HOSTS.joinToString(", ")}")
                }
                return
            }

            if (scheme != "http") reject("\"$scheme\" is not an https:// URL")
            reject("plaintext HTTP would transmit your API key in the clear")
        }

        /** Bounded delta-seconds: digits only, so `1e3`, `+5` and hex can never be read as a delay. */
        val RETRY_AFTER_SECONDS_RE = Regex("^[0-9]{1,9}$")

        /**
         * Parse an RFC 9110 `Retry-After` value into a delay in milliseconds, or `null` when it is
         * absent/unusable. BOTH wire forms are supported — bare delta-seconds (`Retry-After: 30`) and
         * an HTTP-date (`Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`) — because real gateways emit
         * both, and silently ignoring the date form turns a server's explicit "come back later" into
         * an immediate hammering retry.
         */
        fun parseRetryAfterMs(value: String?, nowMs: Long): Long? {
            val s = value?.trim() ?: return null
            if (s.isEmpty()) return null

            if (RETRY_AFTER_SECONDS_RE.matches(s)) {
                val secs = s.toLongOrNull() ?: return null
                return if (secs > 0) secs * 1000 else null
            }

            val instant = try {
                java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
            } catch (e: Exception) {
                return null
            }
            val delta = instant.toEpochMilli() - nowMs
            return if (delta > 0) delta else null
        }

        /** Case-insensitive header lookup — a transport is not obliged to hand us lowercased keys. */
        fun headerValue(headers: Map<String, String>, name: String): String? =
            headers[name] ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun warnMissingIdempotencyKey() {
            if (!warned.compareAndSet(false, true)) return
            System.err.println(
                "[paylod] collect() was called without an `idempotencyKey`, so this charge is not " +
                    "protected against being sent twice. A double-clicked Pay button, a refreshed tab, " +
                    "or a redelivered job will fire a SECOND STK prompt and can charge your customer " +
                    "twice. Pass ONE KEY PER PAYMENT ATTEMPT — an id you mint when the customer presses " +
                    "Pay, and persist on that attempt. https://paylod.dev/docs/sdk#idempotency",
            )
        }
    }
}
