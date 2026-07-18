package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.RealTimeSource
import dev.paylod.internal.TimeSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** The base URL. Identical for every paylod customer, so it is baked in — you never pass it. */
const val DEFAULT_BASE_URL = "https://paylod.dev/functions/v1"

private const val MAX_AMOUNT = 150_000
private const val DEFAULT_WAIT_TIMEOUT_MS = 120_000L

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

        baseUrl = (options.baseUrl ?: env("PAYLOD_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/')
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
    ): Map<String, Any?> {
        val url = "$baseUrl$path"
        var lastError: PaylodException? = null

        var attempt = 0
        while (attempt <= maxRetries) {
            if (attempt > 0) time.sleep(jitter(250L shl (attempt - 1)))

            val headers = LinkedHashMap<String, String>()
            headers["authorization"] = "Bearer $apiKey"
            headers["accept"] = "application/json"
            if (body != null) headers["content-type"] = "application/json"
            if (idempotencyKey != null) headers["idempotency-key"] = idempotencyKey

            val bodyStr = if (body == null) null else Json.write(body)
            val spec = HttpRequestSpec(method, url, headers, bodyStr, timeoutMs)

            val response: HttpResponseSpec = try {
                transport.execute(spec)
            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            }

            val text = response.body
            val parsed: Any? = try {
                if (text.isEmpty()) null else Json.parse(text)
            } catch (e: Exception) {
                text
            }

            if (response.status in 200..299) {
                @Suppress("UNCHECKED_CAST")
                return parsed as? Map<String, Any?> ?: emptyMap()
            }

            val message = ((parsed as? Map<*, *>)?.get("error") as? String) ?: "paylod responded ${response.status}"
            val apiError = PaylodApiException(message, response.status, parsed, idempotencyKey)

            // 429 / 5xx are transient; everything else is a real answer.
            val transient = response.status == 429 || response.status >= 500
            if (!transient || attempt == maxRetries) throw apiError

            lastError = apiError
            val retryAfter = response.headers["retry-after"]?.toDoubleOrNull()
            if (retryAfter != null && retryAfter > 0) {
                time.sleep(Math.min((retryAfter * 1000).toLong(), 10_000))
            }
            attempt++
        }

        throw lastError ?: PaylodConnectionException("Request to $url failed")
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
        if (params.accountReference != null && params.accountReference.trim().length > 12) {
            throw PaylodInvalidRequestException("accountReference must be 12 characters or fewer.")
        }
        if (params.description != null && params.description.trim().length > 64) {
            throw PaylodInvalidRequestException("description must be 64 characters or fewer.")
        }

        val out = LinkedHashMap<String, Any?>()
        out["amount"] = amount
        out["phone"] = Phone.normalize(params.phone)
        if (params.accountReference != null) out["accountReference"] = params.accountReference
        if (params.description != null) out["description"] = params.description
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
        if (params.idempotencyKey == null) warnMissingIdempotencyKey()
        val idempotencyKey = params.idempotencyKey ?: UUID.randomUUID().toString()

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

        val ack = request("POST", "/collect", body, idempotencyKey)
        return CollectAck(
            paymentId = ack["paymentId"]?.toString() ?: "",
            status = PaymentStatus.PENDING,
            checkoutRequestId = ack["checkoutRequestId"]?.toString() ?: "",
            idempotencyKey = idempotencyKey,
        )
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
    fun status(paymentId: String): Payment {
        if (paymentId.isEmpty()) throw PaylodInvalidRequestException("paymentId is required.")
        val encoded = URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20")
        val p = request("GET", "/status/$encoded", null, null)
        return Payment(
            id = p["id"]?.toString() ?: paymentId,
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
        val startedAt = time.nowMillis()
        val deadline = startedAt + timeout

        var last: Payment? = null
        var attempt = 0
        while (true) {
            val payment = status(paymentId)
            last = payment

            val outcome = Outcomes.of(payment)
            if (outcome.status != OutcomeStatus.PENDING) return outcome
            options.onPoll?.onPoll(payment)

            val delay = pollDelay(attempt)
            if (time.nowMillis() + delay >= deadline) break
            time.sleep(delay)
            attempt++
        }

        throw PaylodTimeoutException(paymentId, last!!, time.nowMillis() - startedAt)
    }

    /**
     * [collect] + [wait]. The one-liner most integrations actually want: ring the phone, wait for
     * the PIN, hand back something you can render.
     */
    @JvmOverloads
    fun collectAndWait(params: CollectParams, options: WaitOptions = WaitOptions.DEFAULT): PaymentOutcome {
        val ack = collect(params)
        return wait(ack.paymentId, options)
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
    ): Boolean = Webhooks.verify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec)

    /** ByteArray overload of [verifyWebhook] — pass the exact raw bytes that arrived. */
    @JvmOverloads
    fun verifyWebhook(
        rawBody: ByteArray,
        signatureHeader: String?,
        secret: String? = null,
        toleranceSec: Long = Webhooks.DEFAULT_TOLERANCE_SEC,
    ): Boolean = Webhooks.verify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec)

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
    ): WebhookEvent = Webhooks.parseAndVerify(rawBody, signatureHeader, secret ?: webhookSecret ?: "", toleranceSec)

    private fun normalizeResultCode(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private companion object {
        val warned = AtomicBoolean(false)

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
