package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.RealTimeSource
import dev.paylod.internal.Redactor
import dev.paylod.internal.TimeSource
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The base URL. Identical for every paylod customer, so it is baked in — you never pass it. */
const val DEFAULT_BASE_URL = "https://paylod.dev/functions/v1"

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
    private val transport: Transport
    private val simulateMode: Boolean

    /**
     * Scrubs this client's own secrets out of anything derived from a response before it can reach
     * an exception message, a public `body` field, or a log sink.
     */
    private val redact: Redactor

    /**
     * The sandbox simulator: drive a payment to any of the five outcomes from a test file, with no
     * phone. Every method refuses a `mp_live_` key locally.
     */
    @JvmField
    val simulate: Simulator

    init {
        val env: (String) -> String? = { System.getenv(it) }
        val key = apiKeyArg ?: options.apiKeyOrNull() ?: env("PAYLOD_API_KEY")
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
        webhookSecret = options.webhookSecretOrNull() ?: env("PAYLOD_WEBHOOK_SECRET")
        // BOUNDED AT CONSTRUCTION, not trusted at use. Both of these were taken verbatim, so
        // `timeoutMs = 0` (or negative) built a client whose every request timed out instantly —
        // `Duration.ofMillis(0)` is not "no timeout", it is "already expired" — and `timeoutMs =
        // Long.MAX_VALUE` built one that could park a request thread effectively forever. A negative
        // `maxRetries` skipped the loop entirely and threw a `PaylodConnectionException("Request to
        // … failed")` with no attempt ever made, while a huge one turned a transient 5xx into a
        // multi-hour retry storm under a single idempotency key. None of those are configurations
        // anyone wants; all of them were reachable by a typo in a config file, and every one of them
        // failed in a way that pointed nowhere near the setting that caused it.
        if (options.timeoutMs < MIN_TIMEOUT_MS || options.timeoutMs > MAX_TIMEOUT_MS) {
            throw PaylodConfigException(
                "timeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS (got " +
                    "${options.timeoutMs}). A non-positive timeout expires every request " +
                    "immediately rather than disabling the limit, and an unbounded one can hold a " +
                    "request thread open indefinitely.",
            )
        }
        if (options.maxRetries < 0 || options.maxRetries > MAX_RETRIES_LIMIT) {
            throw PaylodConfigException(
                "maxRetries must be between 0 and $MAX_RETRIES_LIMIT (got ${options.maxRetries}). " +
                    "A negative value would skip the request loop entirely and report a failure " +
                    "that never happened; a very large one turns a transient error into a long " +
                    "retry storm under a single idempotency key.",
            )
        }
        timeoutMs = options.timeoutMs
        maxRetries = options.maxRetries
        redact = Redactor(listOf(apiKey, webhookSecret))

        // ── ROOT 1: the custom-transport gate ────────────────────────────────────────────────
        // A custom HttpTransport is a TEST SEAM. It is refused unless it was asked for explicitly,
        // and refused outright for a production key. This is the same posture `allowInsecureBaseUrl`
        // already had, and it is enforced HERE and AGAIN inside `Transport` — reverting either one
        // alone must not open the hole, because a guarantee with a single implementation is a
        // guarantee with a single point of failure.
        val custom = options.transportOrNull()
        if (custom != null && !options.allowCustomTransport) {
            throw PaylodConfigException(
                "A custom HttpTransport was supplied without allowCustomTransport = true. It is a " +
                    "TEST-ONLY seam, not a production extension point: the SDK pins its own origin " +
                    "and never follows a redirect, and it will not delegate a charge path to code it " +
                    "cannot make those promises about. Set allowCustomTransport = true in a test, or " +
                    "drop the transport.",
            )
        }
        if (custom != null && apiKey.startsWith("mp_live_")) {
            throw PaylodConfigException(
                "A custom HttpTransport may never be used with an mp_live_ key. The API key is a " +
                    "bearer credential; a live charge path must run through the SDK's own dispatch.",
            )
        }
        transport = Transport(apiKey, baseUrl, custom, redact)

        // Simulator mode is a TEST posture, fenced off from production at CONSTRUCTION time.
        simulateMode = options.simulate
        if (simulateMode) assertSandboxKey(apiKey, "Paylod(options.simulate = true)")

        simulate = Simulator(
            apiKey = apiKey,
            redact = redact,
            requester = { method, path, body, idem, validate ->
                request(method, path, body, idem, null, validate)
            },
        )
    }

    /** Construct with an explicit API key (or `null` to read `PAYLOD_API_KEY`) and options. */
    @JvmOverloads
    constructor(apiKey: String? = null, options: PaylodOptions = PaylodOptions.of()) :
        this(apiKey, options, RealTimeSource, java.util.Random())

    /** Everything-in-one-object form. */
    constructor(options: PaylodOptions) :
        this(options.apiKeyOrNull(), options, RealTimeSource, java.util.Random())

    // ── HTTP ────────────────────────────────────────────────────────────────────────────────

    /**
     * [request], but the validator PRODUCES the typed value the caller goes on to use.
     *
     * The point is that validating and constructing are the same act. When they were separate, the
     * caller re-read the raw map after the check and could — and did — interpret it differently
     * from the validator that approved it.
     */
    private fun <T> requestValidated(
        method: String,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        deadlineMs: Long? = null,
        validate: (Map<String, Any?>, Int) -> T,
    ): T {
        var produced: Any? = NOT_PRODUCED
        request(method, path, body, idempotencyKey, deadlineMs) { map, status ->
            produced = validate(map, status)
        }
        if (produced === NOT_PRODUCED) {
            // Unreachable: `request` either invokes the validator or throws. It is a hard error rather
            // than a silent default because the alternative on a money path is inventing a record.
            throw PaylodIndeterminateException(
                "paylod returned a response that was never validated — refusing to act on it. The " +
                    "request WAS dispatched, so read the payment rather than retrying.",
                idempotencyKey,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return produced as T
    }

    private fun request(
        method: String,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        deadlineMs: Long? = null,
        validate: ((Map<String, Any?>, Int) -> Unit)? = null,
    ): Map<String, Any?> {
        var lastError: PaylodException? = null

        // Serialize the body ONCE, before the retry loop. Re-serialising on each attempt would let a
        // mutable `metadata` map be mutated under a FIXED Idempotency-Key — the second attempt would
        // send a different body than the first, defeating the double-charge guard.
        //
        // A body the writer refuses — too large, too deep, or self-referencing `metadata` — becomes
        // a TYPED, PRE-DISPATCH validation error. This is the one place where refusing is free:
        // nothing has been sent, so no charge can exist, and there is no idempotency key to lose.
        // Letting the raw writer exception out instead would hand the caller a bare
        // `RuntimeException` (or, before the writer had limits at all, a `StackOverflowError`) from
        // inside a payments call, which is neither catchable as a paylod error nor classifiable as a
        // money state.
        val bodyStr = if (body == null) {
            null
        } else {
            try {
                Json.write(body)
            } catch (e: Json.JsonWriteException) {
                throw PaylodInvalidRequestException(redact.text(e.message))
            }
        }

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

            // The caller supplies a METHOD, a PATH and a BODY. Not a URL, not headers, and above all
            // not the credential — the transport owns all three, so no code path here (and no custom
            // transport) can address the key anywhere. Origin pinning and the layered redirect
            // refusal happen inside `send`, on every dispatch, with no way to opt out.
            val response: TransportResponse = try {
                transport.send(
                    TransportRequest(
                        method = method,
                        path = path,
                        body = bodyStr,
                        idempotencyKey = idempotencyKey,
                        timeoutMs = perRequestTimeout,
                    ),
                )
            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            }
            // A PaylodInterruptedException from the transport is deliberately NOT caught here: an
            // interrupt is a cancellation, not a transient blip, so it propagates without a retry.
            // A 3xx (or a followed redirect) throws from inside `send` and is likewise terminal.

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

            // BOTH the message and the stored body are built from a REDACTED view of the response.
            // `error` is a server-controlled string and `body` is a public field that integrators log
            // wholesale, so an API that echoes the request back — a validation error quoting the
            // offending headers, a debug envelope, a gateway rendering the whole request on a 502 —
            // would otherwise carry the live bearer token into the exception and from there into
            // every log sink downstream. A server cannot be trusted not to echo.
            val rawMessage = ((parsed as? Map<*, *>)?.get("error") as? String)
                ?: "paylod responded ${response.status}"
            val message = redact.text(rawMessage)
            val apiError = PaylodApiException(message, response.status, redact.body(parsed), idempotencyKey)

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

        throw lastError ?: PaylodConnectionException("Request to $path failed")
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

    /**
     * THE shared body builder — the same one `simulate.collect()` runs. See [CollectBody] for why
     * this stopped being a private method with a looser twin on the simulator.
     */
    private fun buildCollectBody(params: CollectParams): Map<String, Any?> = CollectBody.build(
        phone = params.phone,
        amount = params.amount,
        accountReference = params.accountReference,
        description = params.description,
        metadata = params.metadata,
        accountRefField = "accountReference",
        label = "collect()",
    )

    // ── Public API ──────────────────────────────────────────────────────────────────────────

    /**
     * Send an STK Push. Returns as soon as the prompt is on the customer's phone — the payment is
     * pending. Settle it with [status], [wait], or a webhook.
     *
     * `idempotencyKey` is REQUIRED — mint ONE KEY PER PAYMENT ATTEMPT. See
     * [CollectParams.idempotencyKey] and [Idempotency].
     */
    fun collect(params: CollectParams): CollectAck {
        val body = buildCollectBody(params)
        // THE double-charge guard, resolved by the one shared implementation the simulator also
        // uses. Omitting the key without the explicit opt-out throws here, before any network call.
        val idempotencyKey = Idempotency.resolve(
            params.idempotencyKey,
            params.unsafeGeneratedIdempotencyKey,
            "collect()",
        )

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

            // THE shared validator — the same one `simulate.collect()` runs, HTTP status included.
            val ack = request("POST", "/collect", body, idempotencyKey) { map, status ->
                PaymentValidators.assertCollectAck(map, status, idempotencyKey, "paylod", redact)
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
        unsafeGeneratedIdempotencyKey: Boolean = false,
    ): CollectAck = collect(
        CollectParams(
            phone, amount, accountReference, description, idempotencyKey,
            unsafeGeneratedIdempotencyKey,
        ),
    )

    /** Read a payment. `GET /status/:id`. */
    fun status(paymentId: String): Payment = readPayment(paymentId, null)

    private fun readPayment(paymentId: String, deadlineMs: Long?): Payment {
        if (paymentId.isEmpty()) throw PaylodInvalidRequestException("paymentId is required.")
        val encoded = URLEncoder.encode(paymentId, StandardCharsets.UTF_8).replace("+", "%20")
        // THE shared validator, with law L1 (ID BINDING) at the front: the body must describe the
        // payment that was ASKED about. Whether it proves settlement is a separate question, decided
        // afterwards by the one semantic model in `Semantics.kt` — shape here, meaning there.
        // The validator BUILDS the record it approved and hands it back. This function no longer
        // re-reads the map, so there is no second, laxer interpretation of the same bytes for the
        // money path to use instead of the one that was checked.
        return requestValidated("GET", "/status/$encoded", null, null, deadlineMs) { map, status ->
            PaymentValidators.assertPaymentBody(map, status, paymentId, "paylod", redact)
        }
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
        // `options.timeoutMs` is validated at WaitOptions CONSTRUCTION, so it is positive and bounded
        // by the time it arrives here. It used to be `takeIf { it > 0 } ?: DEFAULT_WAIT_TIMEOUT_MS`:
        // a caller who passed 0 or a negative value — a units mix-up, an unset config field — silently
        // got the 120s default instead of the timeout they asked for, and nothing anywhere said so.
        // Silently substituting a DIFFERENT value for the one a caller supplied is the failure mode,
        // not the absence of a default.
        val timeout = options.timeoutMs
        // Monotonic: the caller's timeout is a DURATION, and must not be lengthened or shortened by a
        // wall-clock adjustment landing mid-wait.
        val startedAt = time.monotonicMillis()
        // `monotonicMillis` is nanoTime-derived, so its origin is arbitrary and it can sit anywhere
        // in the `Long` range — including near the top, where this sum WRAPS NEGATIVE.
        //
        // That wrap is deliberately left alone, because it is harmless PROVIDED every comparison
        // against this value is done by SUBTRACTION. Two's-complement subtraction cancels the wrap
        // exactly: `(startedAt + timeout) - now` yields the true remaining milliseconds even when the
        // sum itself overflowed. This is the standard `System.nanoTime()` deadline idiom, and it is
        // why `remaining()` below has always been correct here.
        //
        // What is NOT safe is comparing the absolute values directly (`now + delay >= deadline`), and
        // that is the bug this replaces — near the top of the range that comparison put a huge
        // positive against a huge negative and answered "out of time" on the first poll, so `wait()`
        // gave up on a payment it had looked at exactly once and `collectAndWait` reported a timeout
        // for a live charge.
        //
        // Saturating the sum is NOT the fix and was tried first: it makes `deadline` Long.MAX_VALUE,
        // but `now + delay` saturates to Long.MAX_VALUE too, so `>= deadline` becomes true and the
        // loop breaks after one poll — the same bug, reached differently. Subtraction is the fix.
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
            // BY SUBTRACTION, never by comparing absolute values. `remaining()` is the same modular
            // arithmetic the rest of the deadline handling uses, and it is exact even when
            // `startedAt + timeout` overflowed. Asking "is there room for another delay?" is the
            // same question as "is the remaining budget bigger than the delay?", and only the
            // subtraction form survives the wrap.
            val left = remaining(deadline) ?: break
            if (left <= delay) break
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
        } catch (e: Throwable) {
            // ── The rest of the failures, which used to escape with no handles at all ──────────
            //
            // Catching only `PaylodException` covered the SDK's own errors and nothing else. A custom
            // transport throwing an `IOException`, an `onPoll` listener throwing anything at all, an
            // `IllegalStateException` from a stub, an OOM in a logging call — every one of those
            // unwound straight past this block, and the caller was handed an exception with NO
            // idempotency key and NO payment id for a charge that is live on a handset right now.
            // The narrow catch made the guarantee conditional on which layer failed, which is exactly
            // the kind of "safe most of the time" that charges people twice.
            //
            // ── `Error` IS NOT A FREE PASS ────────────────────────────────────────────────────
            //
            // This used to be `if (e is Error) throw e` — every `Error` rethrown bare, with no
            // idempotency key and no payment id, for a charge that is live on a handset. The
            // justification was "the JVM is not in a state where wrapping helps", and that is true of
            // exactly one branch of the `Error` hierarchy: `VirtualMachineError` (OOM, StackOverflow,
            // InternalError). It is not true of the rest, and `Error` is not a closed set:
            //
            //   • `AssertionError` is thrown by ordinary application code — `assert`, Kotlin's
            //     `error()`-adjacent helpers, most assertion libraries. An `onPoll` listener doing
            //     `assert(payment.status == PENDING)` is a caller-controlled, entirely routine way to
            //     land here, in a perfectly healthy JVM.
            //   • `LinkageError` / `NoClassDefFoundError` is a classpath problem, not a dying VM.
            //
            // So the split is by whether the VM is actually in trouble, not by whether the class name
            // happens to end in "Error". Everything else is wrapped exactly like a `RuntimeException`
            // would be: a `PaylodConnectionException` carrying BOTH handles, with a REDACTED message
            // and NO cause attached (an arbitrary third-party throwable can embed a request line,
            // headers, or a URL with credentials in it).
            //
            // And a genuine `VirtualMachineError` still does not escape unlabelled. It is rethrown —
            // wrapping it would allocate on the way out of an OOM, which is precisely the wrong move —
            // but the charge handles are attached to it first, as a SUPPRESSED exception. That costs
            // one small object, is itself guarded, and means the key and the payment id appear in the
            // stack trace the caller logs instead of being lost with the only handles on a live charge.
            if (e is VirtualMachineError) {
                try {
                    e.addSuppressed(chargeContext(ack, e))
                } catch (ignored: Throwable) {
                    // If even that allocation fails, the original Error still propagates untouched.
                    // Losing the annotation is acceptable; swallowing the OOM would not be.
                }
                throw e
            }
            // ── THE HANDLES ARE ATTACHED BEFORE ANY DIAGNOSTIC IS COMPUTED ────────────────────
            //
            // This used to build the message FIRST and assign `idempotencyKey`/`paymentId` after.
            // Every term in that message is safe except one: `e.message`. `Throwable.getMessage()`
            // is an ORDINARY OVERRIDABLE METHOD, and a third-party throwable is free to compute it
            // — lazily formatting a response, dereferencing a field a failed constructor never set,
            // consulting a closed resource. If it throws, the throw happens INSIDE the string
            // template, before `wrapped` exists, so this catch block itself unwinds and the caller
            // is handed that second throwable with NO idempotency key and NO payment id — for a
            // charge that is live on a handset. The whole point of this block is that no failure
            // after acknowledgement loses the handles, and the block was reachable in a state where
            // it lost them.
            //
            // So every term of the message is obtained through a helper that CANNOT propagate:
            // [safeTypeName] and [safeMessage] both swallow whatever the throwable does and fall
            // back to a placeholder. The diagnostic is worth having, but not at the price of the
            // only two handles on a live charge.
            val kind = safeTypeName(e)
            val detail = safeMessage(e)
            val wrapped = PaylodConnectionException(
                redact.text(
                    "The collect was ACKNOWLEDGED and an STK prompt is live, but waiting for it to " +
                        "settle failed with an unexpected $kind: $detail. " +
                        "The charge state is INDETERMINATE. Read payment ${ack.paymentId} (or retry " +
                        "with THIS idempotencyKey) before starting any new attempt — minting a fresh " +
                        "key would risk a second charge.",
                ),
            )
            wrapped.idempotencyKey = ack.idempotencyKey
            wrapped.paymentId = ack.paymentId
            throw wrapped
        }
    }

    /**
     * The charge handles, as a throwable that can be attached to something we must not replace.
     *
     * Used for a [VirtualMachineError], which is rethrown as-is: this rides along as a suppressed
     * exception so the idempotency key and the payment id are in the stack trace rather than lost.
     */
    private fun chargeContext(ack: CollectAck, cause: Throwable): PaylodException {
        val ctx = PaylodIndeterminateException(
            "The collect was ACKNOWLEDGED and an STK prompt is live for payment ${ack.paymentId}, " +
                "but waiting for it to settle died with a ${safeTypeName(cause)}. The charge " +
                "state is INDETERMINATE. Read that payment, or retry with the idempotencyKey on " +
                "this exception — never a fresh one.",
            ack.idempotencyKey,
        )
        ctx.paymentId = ack.paymentId
        return ctx
    }

    /**
     * The throwable's type name, or a placeholder — NEVER a throw.
     *
     * `getClass()` cannot fail, but `Class.getSimpleName()` can: for a malformed or
     * synthetically-named class the JDK throws `InternalError`/`ClassFormatError` out of the name
     * parser. That is a remote possibility and it is on the post-acknowledgement path, where the
     * cost of the remote possibility is a live charge whose idempotency key nobody holds.
     */
    private fun safeTypeName(e: Throwable): String = try {
        e.javaClass.simpleName.ifEmpty { "throwable" }
    } catch (ignored: Throwable) {
        "throwable"
    }

    /**
     * The throwable's message, or a placeholder — NEVER a throw.
     *
     * `Throwable.getMessage()` is overridable ordinary code. A third-party throwable can compute it
     * lazily and fail while doing so, and a throw from inside a string template on this path takes
     * the idempotency key and the payment id down with it. Nothing this function is asked for is
     * worth that, so nothing it does can escape.
     */
    private fun safeMessage(e: Throwable): String = try {
        e.message ?: "(no message)"
    } catch (ignored: Throwable) {
        "(the throwable's own getMessage() failed)"
    }

    /** Java-friendly overload of [collectAndWait]. */
    @JvmOverloads
    fun collectAndWait(
        phone: String,
        amount: Int,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
        unsafeGeneratedIdempotencyKey: Boolean = false,
        options: WaitOptions = WaitOptions.DEFAULT,
    ): PaymentOutcome = collectAndWait(
        CollectParams(
            phone, amount, accountReference, description, idempotencyKey,
            unsafeGeneratedIdempotencyKey,
        ),
        options,
    )

    /**
     * Decode an M-Pesa result code offline. No network, no API key needed at call time. The strings
     * are identical to the ones the API puts in `event.data.decoded`.
     */
    @JvmOverloads
    fun decodeError(resultCode: Any?, rawDesc: String? = null): DecodedError {
        // ── THE CLIENT'S OWN REDACTOR, on an offline surface (requirements 4.1 and 4.9) ────────
        //
        // This used to be a bare delegation to the static decoder. The static decoder now masks
        // credential SHAPES for itself, which is everything a function with no client can do — but
        // it holds no secret, so it cannot recognise THIS client's configured API key or webhook
        // secret by value. A key that does not match `mp_live_`/`mp_test_`/`whsec_`/`sk_`/`Bearer `
        // (a self-hosted deployment's key, a rotated format, a test fixture) passed straight
        // through into `DecodedError.code` and `.cause`, and from there into the generated
        // `toString()` of a public data class.
        //
        // `this` HAS the configured values. An instance method that declined to use them while a
        // static one did its best is the wrong way round, so the result goes through both: shapes
        // from the decoder, exact configured secrets here.
        val decoded = DarajaCatalog.decodeError(resultCode, rawDesc)
        return decoded.copy(
            code = redact.text(decoded.code),
            cause = redact.text(decoded.cause),
        )
    }

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

    private companion object {
        /**
         * A sentinel distinct from every value a validator could legitimately produce — including
         * `null`. `null` would be indistinguishable from a validator that legitimately returned it.
         */
        val NOT_PRODUCED = Any()

        /** A per-request timeout below this is not a timeout, it is an immediate failure. */
        const val MIN_TIMEOUT_MS = 1L

        /** Ten minutes. Past this a "timeout" is not bounding anything a caller can wait for. */
        const val MAX_TIMEOUT_MS = 600_000L

        /** Retries are for transient blips. Beyond ten, the answer is not going to change. */
        const val MAX_RETRIES_LIMIT = 10

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
        /**
         * Render a rejected URL with the credential-bearing parts REMOVED.
         *
         * This message is an ordinary configuration error. It is thrown at construction — before a
         * [dev.paylod.internal.Redactor] exists — and it goes wherever the application logs startup
         * failures, which is routinely a shared aggregator. Echoing the raw value therefore
         * published exactly the things people embed in a base URL and never intend to log:
         *
         *   • userinfo — `https://svc:sup3rsecret@host/…`, a password in the authority
         *   • the query string — `?token=…`, `?apikey=…`
         *   • the fragment — the same, for a caller who put it there
         *
         * And these are not exotic inputs; they are three of the shapes this very function REJECTS,
         * so the rejection was the thing that leaked them. Scheme, host, port and path survive,
         * because the operator still has to be able to tell which origin was refused and why — a
         * sanitized message that says nothing is a message that gets replaced by a raw one.
         */
        private fun sanitizeUrl(parsed: URI): String {
            val scheme = parsed.scheme ?: "?"
            val host = parsed.host ?: "?"
            val port = if (parsed.port != -1) ":${parsed.port}" else ""
            val path = parsed.path ?: ""
            val redactedUserInfo = if (parsed.userInfo != null) "<redacted>@" else ""
            val suffix = if (parsed.rawQuery != null || parsed.rawFragment != null) {
                " (query/fragment omitted)"
            } else {
                ""
            }
            return "$scheme://$redactedUserInfo$host$port$path$suffix"
        }

        fun assertSecureBaseUrl(baseUrl: String, apiKey: String, allowInsecure: Boolean) {
            val parsed = try {
                URI(baseUrl)
            } catch (e: Exception) {
                // Not the raw value: a URL that failed to PARSE can still contain a credential, and
                // this message is headed for a log.
                throw PaylodConfigException("baseUrl is not a valid URL.")
            }
            val scheme = parsed.scheme?.lowercase()
            val isLive = apiKey.startsWith("mp_live_")

            // THE DIAGNOSTICS CHOKE POINT, applied to a CALLER-controlled value.
            //
            // `sanitizeUrl` removes userinfo, query and fragment — but keeps the PATH verbatim, and
            // a path is a perfectly ordinary place for a credential to end up: a copied signed URL,
            // a callback endpoint with the key in it, an environment variable assembled wrong. The
            // adversarial sweep produced exactly that and watched the key land in this message,
            // which is the first thing a misconfigured integration prints at startup.
            //
            // Redacting rather than refusing, because this is the message that TELLS the caller
            // their configuration is wrong — it has to remain readable enough to act on.
            val redact = Redactor(listOf(apiKey))
            fun reject(why: String): Nothing = throw PaylodConfigException(
                "baseUrl is not an allowed paylod origin: $why (got ${redact.field(sanitizeUrl(parsed))}). The API key can move " +
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
    }
}
