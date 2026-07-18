package dev.paylod

/**
 * Error taxonomy.
 *
 * DESIGN RULE (mirrors @paylod/node): a *payment* that fails (wrong PIN, cancelled, low balance) is
 * NOT thrown — it is an expected business outcome, returned as a renderable [PaymentOutcome] from
 * [Paylod.collectAndWait] with `status = FAILED` and a customer-facing `message`. Everything in this
 * file is a *programmer, transport, or indeterminate* problem: the kind of thing you genuinely want
 * to blow up a request handler.
 */
open class PaylodException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * The effective `Idempotency-Key` of the request that failed, when known. If `collect()` throws
     * (network, timeout, 5xx, malformed 2xx), the SDK attaches the key here so the caller can retry
     * with the SAME key rather than mint a fresh one and double-charge.
     */
    @JvmField
    var idempotencyKey: String? = null
}

/** Bad input caught locally, before any network call (invalid amount, unparseable phone). */
class PaylodInvalidRequestException internal constructor(message: String) : PaylodException(message)

/**
 * The FULL Unicode control ranges — C0 (U+0000..U+001F), DEL (U+007F) and C1 (U+0080..U+009F).
 *
 * The C1 range matters as much as C0: those code points are invalid in an HTTP field value, and some
 * intermediaries transcode or strip them. A key that is silently altered in transit is a key that no
 * longer matches the one the first attempt used — which is precisely how a "protected" retry becomes
 * a second charge.
 */
private val CONTROL_CHAR_RE = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

/**
 * Unicode-only whitespace and invisible formatting characters: NBSP, the en/em spaces, line/paragraph
 * separators, the zero-width set and BOM. `isBlank()` catches a key made ENTIRELY of these, but a key
 * like `"order-123​"` looks identical to `"order-123"` in every log and dashboard while being a
 * different key on the wire. Two attempts that a human would swear used "the same key" would then
 * charge twice, so any such character is fatal.
 */
private val UNICODE_WHITESPACE_RE = Regex("[\\u00a0\\u1680\\u2000-\\u200f\\u2028\\u2029\\u202f\\u205f\\u2060\\u3000\\ufeff]")

/** An idempotency key travels in an HTTP header; bound it in BYTES, which is what a server counts. */
private const val MAX_IDEMPOTENCY_KEY_BYTES = 255

/**
 * The only characters an HTTP header value can carry unambiguously: printable US-ASCII, 0x20..0x7E.
 *
 * A printable NON-ASCII key (`"ordr-café-1"`) passes every other check here and still breaks the
 * guarantee. Either the transport refuses to encode it — an obscure failure nowhere near the real
 * cause — or a laxer stack silently re-encodes it, and two requests the caller intended to share ONE
 * key arrive carrying different bytes. The server sees two distinct keys, and the duplicate-charge
 * guard is gone without a single error anywhere. Refuse it here, where the message can say why.
 */
private val PRINTABLE_ASCII_RE = Regex("^[\\u0020-\\u007e]+$")

/**
 * Reject an idempotency key that would silently drop double-charge protection: blank/whitespace keys,
 * keys carrying control characters or invisible Unicode, anything outside printable US-ASCII (none of
 * which survives an HTTP header round-trip intact), and over-long values. A caller-supplied key is the
 * ONE thing standing between a double-click and a double-charge, so a bad one must fail loudly rather
 * than be quietly accepted. Shared by `collect()` and `simulate.collect()`.
 *
 * The checks run narrowest-first: the printable-ASCII rule below subsumes the control-character and
 * invisible-Unicode rules, but those fire first so the caller gets a message naming the actual problem
 * instead of a generic "not ASCII".
 */
internal fun assertValidIdempotencyKey(key: String) {
    if (key.isBlank()) {
        throw PaylodInvalidRequestException(
            "idempotencyKey must be a non-empty, non-whitespace string — a blank key silently drops " +
                "double-charge protection.",
        )
    }
    if (CONTROL_CHAR_RE.containsMatchIn(key)) {
        throw PaylodInvalidRequestException(
            "idempotencyKey must not contain control characters (C0/C1 ranges, DEL — tabs, newlines, " +
                "NULs, etc.). They cannot travel in an HTTP header unaltered.",
        )
    }
    if (UNICODE_WHITESPACE_RE.containsMatchIn(key)) {
        throw PaylodInvalidRequestException(
            "idempotencyKey must not contain non-ASCII whitespace or zero-width/invisible characters " +
                "(NBSP, zero-width space, BOM, …) — they make two different keys look identical.",
        )
    }
    if (!PRINTABLE_ASCII_RE.matches(key)) {
        val offending = key.first { it.code < 0x20 || it.code > 0x7e }
        throw PaylodInvalidRequestException(
            "idempotencyKey must be printable ASCII only (0x20-0x7E) — found '%c' (U+%04X). HTTP header "
                .format(offending, offending.code) +
                "values are ASCII on the wire, so a key like \"ordr-café-1\" either fails to encode at " +
                "the transport (an error nowhere near this line) or gets silently re-encoded — and two " +
                "requests you intended to share ONE key then arrive with different bytes, dropping the " +
                "double-charge guard with no error at all. Use A-Z a-z 0-9 and punctuation; a UUID is " +
                "the safest choice.",
        )
    }
    val bytes = key.toByteArray(Charsets.UTF_8).size
    if (bytes > MAX_IDEMPOTENCY_KEY_BYTES) {
        throw PaylodInvalidRequestException(
            "idempotencyKey must be $MAX_IDEMPOTENCY_KEY_BYTES bytes or fewer when UTF-8 encoded " +
                "(got $bytes).",
        )
    }
}

/** Configuration problem — e.g. no API key supplied and `PAYLOD_API_KEY` is unset. */
open class PaylodConfigException internal constructor(message: String) : PaylodException(message)

/**
 * A simulator call was made with a key that is not a sandbox (`mp_test_`) key.
 *
 * Thrown LOCALLY, before any request leaves the process — the key's own prefix is enough to know.
 * Extends [PaylodConfigException] because that is what it is: the wrong credential, not a transient
 * failure. Retrying will never help.
 */
class PaylodSandboxOnlyException internal constructor(message: String) : PaylodConfigException(message)

/** The API returned a non-2xx response (or an indeterminate malformed 2xx). */
class PaylodApiException internal constructor(
    message: String,
    /** HTTP status code. */
    @JvmField val status: Int,
    /** The parsed JSON body, when the response had one (a `Map`, `List`, scalar, or `null`). */
    @JvmField val body: Any?,
    /** `Idempotency-Key` sent with the offending request, if any — useful for support tickets. */
    idempotencyKey: String? = null,
    /**
     * `true` when the money state cannot be proven either way — set for a malformed 2xx (a success
     * response with no `paymentId`): the charge may or may not have been raised, so this is a STOP
     * signal. Read the payment status with [idempotencyKey]; do NOT blindly retry with a new key.
     */
    @JvmField val indeterminate: Boolean = false,
) : PaylodException(message) {

    init {
        this.idempotencyKey = idempotencyKey
    }

    /** 401 — the API key is missing or invalid. */
    val isAuthError: Boolean get() = status == 401

    /** 429 — you are being rate limited. Back off. */
    val isRateLimited: Boolean get() = status == 429

    /** Any 409. Every 409 on a money-moving route comes from the idempotency layer. */
    val isIdempotencyConflict: Boolean get() = status == 409

    /**
     * `409` indeterminate — a previous request under this key died while the call to Daraja was in
     * flight, so it may or may not have moved money. paylod refuses to re-dispatch it.
     *
     * This is a STOP signal, not a retry signal. Read the payment status first ([Paylod.check]); if
     * nothing happened, open a NEW attempt with a NEW key.
     */
    val isIdempotencyIndeterminate: Boolean
        get() = status == 409 && INDETERMINATE_RE.containsMatchIn(message ?: "")

    /**
     * `409` in progress — the first request under this key is still running. Honour `Retry-After`
     * and retry the *same* key: you will get the winner's answer.
     */
    val isIdempotencyInProgress: Boolean
        get() = status == 409 && IN_PROGRESS_RE.containsMatchIn(message ?: "")

    /**
     * `409` body conflict — the same `Idempotency-Key` was reused with a *different* body. Always a
     * bug in your code: two different charges collided on one key.
     */
    val isIdempotencyBodyConflict: Boolean
        get() = status == 409 && !isIdempotencyIndeterminate && !isIdempotencyInProgress

    private companion object {
        val INDETERMINATE_RE = Regex("interrupted while the provider call was", RegexOption.IGNORE_CASE)
        val IN_PROGRESS_RE = Regex("already in progress", RegexOption.IGNORE_CASE)
    }
}

/** The request could not be completed at the transport layer (DNS, TLS, socket, abort). */
class PaylodConnectionException internal constructor(
    message: String,
    cause: Throwable? = null,
) : PaylodException(message, cause)

/**
 * The calling thread was interrupted mid-request. Deliberately NOT a [PaylodConnectionException]:
 * an interrupt is a cancellation, not a transient blip, so the client must NOT retry it. The
 * interrupt flag is restored before this is thrown.
 */
class PaylodInterruptedException internal constructor(
    message: String,
    cause: Throwable? = null,
) : PaylodException(message, cause)

/**
 * [Paylod.collectAndWait] / [Paylod.wait] gave up before the payment reached a terminal state.
 *
 * This deliberately THROWS rather than returning `status = FAILED`. A timeout is not a failed
 * payment — the customer may still be staring at the STK prompt, and may still pay. Handle it
 * explicitly: keep the order pending and let the webhook settle it.
 */
class PaylodTimeoutException internal constructor(
    @JvmField val paymentId: String,
    /** The last `pending` snapshot we read before giving up. */
    @JvmField val payment: Payment,
    @JvmField val waitedMs: Long,
) : PaylodException(
    "Payment $paymentId was still pending after ${Math.round(waitedMs / 1000.0)}s. " +
        "It is NOT failed — the customer may still complete it. Leave the order pending and " +
        "let the webhook (or a later paylod.status() call) settle it.",
)

/** Why a webhook signature failed to verify. */
enum class SignatureFailureReason {
    MISSING_SIGNATURE,
    MALFORMED_SIGNATURE,
    STALE_TIMESTAMP,
    NO_MATCH,
    INVALID_PAYLOAD,

    /**
     * A non-positive `toleranceSec` was used outside a fixed-clock test — replay protection would
     * have been silently disabled. Configure a positive tolerance in production.
     */
    INSECURE_TOLERANCE,
}

/** A webhook request could not be verified. Respond 400 and do not process the body. */
class PaylodSignatureVerificationException internal constructor(
    @JvmField val reason: SignatureFailureReason,
    message: String,
) : PaylodException(message)
