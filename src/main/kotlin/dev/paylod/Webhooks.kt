package dev.paylod

import dev.paylod.internal.Json
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook signature verification.
 *
 * Ported from the Node SDK's `webhook.ts`, which mirrors the backend signer
 * (`supabase/functions/_shared/webhooks/sign.ts`):
 *
 *   header:    x-webhook-signature: t=<unix-seconds>,v1=<hex>
 *   signed:    HMAC-SHA256( secret, `${t}.${rawBody}` )   -> lowercase hex
 *   also sent: x-webhook-id, x-webhook-event
 *   tolerance: reject a `t` more than `toleranceSec` from now (default 300).
 *
 * THE RAW BODY IS LOAD-BEARING. Re-serialising a parsed body is not guaranteed to reproduce the
 * same bytes, so always hand [verify]/[parseAndVerify] the exact bytes that arrived.
 */
object Webhooks {

    const val SIGNATURE_HEADER = "x-webhook-signature"
    const val EVENT_ID_HEADER = "x-webhook-id"
    const val EVENT_TYPE_HEADER = "x-webhook-event"

    /** Default anti-replay window, seconds. Mirrors the server's `maxSkewSeconds`. */
    const val DEFAULT_TOLERANCE_SEC = 300L

    /**
     * The widest anti-replay window this SDK will accept: **one hour**.
     *
     * Rejecting `toleranceSec <= 0` was only half the rule. Replay protection is a WINDOW, and a
     * window can be disabled by making it enormous just as effectively as by making it zero — the
     * check is `abs(now - t) > toleranceSec`, so `toleranceSec = Long.MAX_VALUE` accepts a captured
     * webhook of literally any age while looking, in a config file, like a positive number that
     * passed validation. `31536000` ("a year, to stop the flaky timestamp errors") is the realistic
     * version of that mistake and it is the same hole.
     *
     * An hour is far beyond any legitimate clock skew between a webhook sender and a receiver, so
     * nothing real is refused, and the window can no longer be widened into non-existence.
     */
    const val MAX_TOLERANCE_SEC = 3_600L

    private fun hmacHex(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        mac.update("$timestamp.".toByteArray(StandardCharsets.UTF_8))
        mac.update(body)
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    /**
     * Sign a payload the way the paylod webhook worker does. Exposed so you can build realistic
     * fixtures in your own tests — you never need this in production code.
     */
    @JvmStatic
    @JvmOverloads
    fun sign(
        payload: ByteArray,
        secret: String,
        timestampSec: Long = System.currentTimeMillis() / 1000,
    ): String {
        val v1 = hmacHex(secret, timestampSec.toString(), payload)
        return "t=$timestampSec,v1=$v1"
    }

    @JvmStatic
    @JvmOverloads
    fun sign(
        payload: String,
        secret: String,
        timestampSec: Long = System.currentTimeMillis() / 1000,
    ): String = sign(payload.toByteArray(StandardCharsets.UTF_8), secret, timestampSec)

    private data class Header(val t: String, val v1: String)

    /** A well-formed `v1` is 64 lowercase hex chars (an HMAC-SHA256 digest). */
    private val V1_RE = Regex("^[0-9a-f]{64}$")

    /**
     * A well-formed `t` is plain decimal unix seconds — DIGITS ONLY. No sign, no exponent, no hex,
     * no underscores. Bounded to 19 digits so it cannot overflow a `Long`.
     */
    private val T_RE = Regex("^[0-9]{1,19}$")

    /**
     * Parse the signature header STRICTLY. The header is `t=<unix>,v1=<hex>` and nothing else that
     * matters — so we require EXACTLY ONE `t` and EXACTLY ONE `v1`, and reject anything else.
     *
     * This closes a last-value-wins hole: two `x-webhook-signature` headers combined into one
     * comma-joined value (`t=1,v1=<real>,t=9999999999,v1=<forged>`) must NOT be accepted by silently
     * taking the last pair. Duplicates of either key are fatal, as is a malformed `v1`.
     */
    private fun parseHeader(header: String): Header? {
        var t: String? = null
        var v1: String? = null
        var tCount = 0
        var v1Count = 0
        for (seg in header.split(",")) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            val idx = s.indexOf("=")
            if (idx <= 0) continue
            val key = s.substring(0, idx).trim()
            val value = s.substring(idx + 1).trim()
            when (key) {
                "t" -> { t = value; tCount++ }
                "v1" -> { v1 = value; v1Count++ }
                // Unknown keys are ignored for forward-compatibility; a duplicate t/v1 is fatal below.
            }
        }
        if (tCount != 1 || v1Count != 1 || t.isNullOrEmpty() || v1.isNullOrEmpty()) return null
        // `v1` must be exactly one 64-char lowercase-hex digest. `t` is validated (integer) by the caller.
        if (!V1_RE.matches(v1)) return null
        return Header(t, v1)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    /**
     * Verify a paylod webhook and return the typed event. Throws
     * [PaylodSignatureVerificationException] on any failure — never returns a half-trusted value.
     *
     * ── There is deliberately no `nowSec` parameter ────────────────────────────────────────
     * Clock injection used to be a PUBLIC parameter on every entry point here. That makes "what time
     * is it, for replay-protection purposes" an argument an application supplies — and the anti-replay
     * check is `abs(nowSec - t) > toleranceSec`, so a caller-supplied clock can move the window
     * anywhere, including onto a captured webhook from last year. It existed for one reason, which
     * was verifying pinned fixtures in this SDK's own tests, and a test-only need does not justify a
     * production parameter that can switch off a security control. It now lives behind an internal
     * seam ([parseAndVerifyAt]) that only this module can reach.
     *
     * Note what this is NOT claiming: `internal` compiles to a public JVM method and is not a
     * security boundary against in-process code. See SECURITY.md. It is an API-hygiene boundary —
     * the parameter is gone from the surface an ordinary application can reach through the SDK's
     * documented API, so it cannot be passed by accident, by a config binding, or by a well-meaning
     * "make the flaky timestamp test pass" edit.
     */
    @JvmStatic
    @JvmOverloads
    fun parseAndVerify(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): WebhookEvent = parseAndVerifyAt(payload, signature, secret, toleranceSec, null)

    /** The internal fixed-clock seam. Test-only; see the note on [parseAndVerify]. */
    internal fun parseAndVerifyAt(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): WebhookEvent {
        val root = verifySignatureAt(payload, signature, secret, toleranceSec, nowSec)
        assertEventSchema(root)
        return toEvent(root)
    }

    /** The internal fixed-clock seam, `String` overload. */
    internal fun parseAndVerifyAt(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): WebhookEvent = parseAndVerifyAt(payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec)

    /**
     * Verify ONLY the signature and return the parsed JSON body, without the event-schema checks.
     *
     * This is the signature layer on its own. It exists because the two questions are genuinely
     * separate — "did paylod send these bytes?" and "do these bytes mean what my handler assumes?" —
     * and because the cross-repo golden vector pins the SIGNING SCHEME with a minimal fixture that
     * is deliberately not a representative event.
     *
     * Production handlers should call [parseAndVerify]: a verified signature alone is not a licence
     * to act on a body.
     */
    @JvmStatic
    @JvmOverloads
    fun verifySignature(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): Map<String, Any?> = verifySignatureAt(payload, signature, secret, toleranceSec, null)

    /** The internal fixed-clock seam. Test-only; see the note on [parseAndVerify]. */
    internal fun verifySignatureAt(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): Map<String, Any?> {
        if (secret.isEmpty()) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.MISSING_SIGNATURE,
                "No webhook signing secret configured. Pass a secret or set PAYLOD_WEBHOOK_SECRET.",
            )
        }
        if (signature.isNullOrEmpty()) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.MISSING_SIGNATURE,
                "Missing $SIGNATURE_HEADER header.",
            )
        }

        val parsed = parseHeader(signature)
            ?: throw PaylodSignatureVerificationException(
                SignatureFailureReason.MALFORMED_SIGNATURE,
                "Malformed $SIGNATURE_HEADER header — expected \"t=<unix>,v1=<hex>\".",
            )

        // `t` is validated LEXICALLY, not just by `toLongOrNull()`. Kotlin's parser accepts a leading
        // `+`/`-` sign, and a looser numeric parse would accept `1e3` or hex. The signed header field
        // is specified as plain decimal unix seconds, so anything else is a malformed header from a
        // non-conforming (or hostile) signer and must be refused rather than coerced.
        if (!T_RE.matches(parsed.t)) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.MALFORMED_SIGNATURE,
                "Signature timestamp must be plain decimal unix seconds (digits only).",
            )
        }
        val t = parsed.t.toLongOrNull()
            ?: throw PaylodSignatureVerificationException(
                SignatureFailureReason.MALFORMED_SIGNATURE,
                "Signature timestamp is not a number.",
            )

        // Replay protection is NOT optional and cannot be switched off — not by a caller, not by a
        // test. A zero/negative tolerance would accept a captured webhook of ANY age, so it is refused
        // unconditionally. (A `Long` is inherently finite on the JVM, so "non-finite" is unrepresentable
        // here; the equivalent hole in a floating-point port is closed by the same `<= 0` rejection.)
        // A fixed-vector test pins the clock with `nowSec` and keeps a NORMAL positive window.
        if (toleranceSec <= 0) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INSECURE_TOLERANCE,
                "toleranceSec must be a positive number of seconds — a non-positive tolerance would " +
                    "disable webhook replay protection entirely. To verify a pinned fixture, keep a " +
                    "normal tolerance and use the SDK's internal fixed-clock test seam.",
            )
        }
        // BOUNDED ABOVE AS WELL AS BELOW. A window is disabled just as thoroughly by making it
        // enormous as by making it zero, and an enormous one has the advantage of looking valid.
        if (toleranceSec > MAX_TOLERANCE_SEC) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INSECURE_TOLERANCE,
                "toleranceSec must be at most ${MAX_TOLERANCE_SEC}s (one hour) — a wider window " +
                    "accepts a captured webhook of essentially any age, which is replay protection " +
                    "in name only. An hour already exceeds any legitimate clock skew; if timestamps " +
                    "are failing, the clock is wrong, not the window.",
            )
        }
        // An injected clock is still a clock: a negative or absurd `nowSec` is a caller bug that would
        // silently widen (or invert) the window, so validate it rather than trust it.
        if (nowSec != null && nowSec < 0) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INSECURE_TOLERANCE,
                "nowSec must be a non-negative unix timestamp in seconds (got $nowSec).",
            )
        }
        val now = nowSec ?: (System.currentTimeMillis() / 1000)
        if (Math.abs(now - t) > toleranceSec) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.STALE_TIMESTAMP,
                "Signature timestamp is outside the ${toleranceSec}s tolerance (replay?).",
            )
        }

        val expected = hmacHex(secret, parsed.t, payload)
        val a = expected.toByteArray(StandardCharsets.UTF_8)
        val b = parsed.v1.toByteArray(StandardCharsets.UTF_8)
        if (!constantTimeEquals(a, b)) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.NO_MATCH,
                "Webhook signature does not match. Check the signing secret, and make sure you are " +
                    "passing the RAW request body (not a re-serialised object).",
            )
        }

        val text = String(payload, StandardCharsets.UTF_8)
        val parsedBody: Any? = try {
            Json.parse(text)
        } catch (e: Exception) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INVALID_PAYLOAD,
                "Webhook body is signed correctly but is not valid JSON.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        return parsedBody as? Map<String, Any?> ?: invalid("the body is not a JSON object")
    }

    /** String overload of [verifySignature]. */
    @JvmStatic
    @JvmOverloads
    fun verifySignature(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): Map<String, Any?> = verifySignatureAt(
        payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, null,
    )

    /** The internal fixed-clock seam, `String` overload. */
    internal fun verifySignatureAt(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): Map<String, Any?> = verifySignatureAt(
        payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec,
    )

    @JvmStatic
    @JvmOverloads
    fun parseAndVerify(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): WebhookEvent = parseAndVerifyAt(
        payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, null,
    )

    /**
     * Verify a webhook and return `true`/`false` instead of throwing. Mirrors the task's
     * `verifyWebhook(...) -> Boolean` convenience; use [parseAndVerify] when you want the event.
     */
    @JvmStatic
    @JvmOverloads
    fun verify(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): Boolean = verifyAt(payload, signature, secret, toleranceSec, null)

    /** The internal fixed-clock seam. Test-only; see the note on [parseAndVerify]. */
    internal fun verifyAt(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): Boolean = try {
        parseAndVerifyAt(payload, signature, secret, toleranceSec, nowSec)
        true
    } catch (e: PaylodSignatureVerificationException) {
        false
    } catch (e: ClassCastException) {
        // A schema/type mismatch in an otherwise-correctly-signed body is a verification failure, not
        // a crash. The boolean convenience must return false for ALL parse/schema failures, uniformly.
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    @JvmStatic
    @JvmOverloads
    fun verify(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
    ): Boolean = verifyAt(payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, null)

    /** The internal fixed-clock seam, `String` overload. */
    internal fun verifyAt(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long,
        nowSec: Long?,
    ): Boolean = verifyAt(payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec)

    /** Reject with a consistent, non-leaking message. */
    private fun invalid(detail: String): Nothing = throw PaylodSignatureVerificationException(
        SignatureFailureReason.INVALID_PAYLOAD,
        "Webhook body is signed correctly but is not a valid paylod event: $detail. A signature " +
            "proves WHO sent the body, not that the body MEANS what your handler assumes — so the " +
            "event is rejected rather than passed on half-understood.",
    )

    private fun optionalString(v: Any?, field: String) {
        if (v != null && v !is String) invalid("$field is not a string")
    }

    /**
     * Validate the FULL event schema, then its internal consistency, then its evidence.
     *
     * ── Why this exists ───────────────────────────────────────────────────────────────────
     * The previous version checked that `type` was a String and `data` was a Map, then built a
     * [WebhookEvent] out of whatever else had arrived — coercing every remaining field with
     * `toString()` / `as?` / `?: ""`. So `event.data.status`, `event.data.amount` and
     * `event.data.mpesaReceipt` were typed as `PaymentStatus?`, `Int?` and `String?` while actually
     * being whatever the sender felt like. A handler written against those types — which is every
     * handler, because that is what the types are FOR — would branch on
     * `data.status == PaymentStatus.SUCCESS` and fulfil an order on a field nothing had checked.
     *
     * A valid signature does NOT make that safe. It proves the body came from paylod; it says
     * nothing about whether the body is COHERENT. A bug upstream, a partially-written row, a schema
     * change, or a compromised signing key all produce correctly-signed nonsense, and the handler is
     * the last place that can refuse it.
     *
     * Three layers:
     *   1. SHAPE       — every field present is the type the data class promises.
     *   2. CONSISTENCY — `type` and `data.status` must agree. A `payment.success` carrying
     *                    `status: "failed"` is not an event we can act on in either direction.
     *   3. EVIDENCE    — a `payment.success` must satisfy the SAME semantic model a status read
     *                    does. The event type is a CLAIM, and a claim is not evidence for itself:
     *                    this is law L2 from `Semantics.kt`, applied to the delivery channel that
     *                    most often triggers order fulfilment. Reusing [PaymentSemantics.judge]
     *                    rather than re-deriving the rule is the whole point of having a model —
     *                    the webhook path and the status-read path cannot drift into disagreeing
     *                    about what proves a payment.
     */
    private fun assertEventSchema(root: Map<String, Any?>) {
        val type = root["type"]
        if (type != "payment.success" && type != "payment.failed") {
            invalid("type was \"$type\", expected payment.success or payment.failed")
        }
        val created = root["created"]
        if (created !is Number || created.toLong() < 0 || created.toDouble() != Math.floor(created.toDouble())) {
            invalid("created is not a non-negative whole number of unix seconds")
        }

        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as? Map<String, Any?> ?: invalid("data is not an object")

        val paymentId = data["paymentId"]
        if (paymentId !is String || paymentId.isBlank()) invalid("data.paymentId is missing or empty")

        val status = data["status"]
        if (status !is String) invalid("data.status is missing or is not a string")
        val parsedStatus = PaymentStatus.parseWire(status)
            ?: invalid("data.status was \"$status\", not one of pending/success/failed")

        // Not merely "is a Number": an exact, positive, whole, in-range KES amount. See [wholeAmount].
        val amount = data["amount"]
        if (wholeAmount(amount) == null) {
            invalid(
                "data.amount is not a positive whole number of KES within the supported range " +
                    "(1..$MAX_AMOUNT) — a fractional or out-of-range amount cannot be delivered " +
                    "without silently truncating or wrapping the value a merchant reconciles against",
            )
        }

        val envValue = data["env"]
        if (envValue != null && envValue != "sandbox" && envValue != "production") {
            invalid("data.env was \"$envValue\", expected sandbox or production")
        }
        optionalString(data["applicationId"], "data.applicationId")
        optionalString(data["phone"], "data.phone")
        optionalString(data["accountRef"], "data.accountRef")
        optionalString(data["mpesaReceipt"], "data.mpesaReceipt")
        optionalString(data["checkoutRequestId"], "data.checkoutRequestId")
        optionalString(data["resultDesc"], "data.resultDesc")
        val resultCode = data["resultCode"]
        if (resultCode != null && resultCode !is String && resultCode !is Number) {
            invalid("data.resultCode is neither a string/number nor null")
        }

        // 2. CONSISTENCY.
        val expectedStatus = if (type == "payment.success") PaymentStatus.SUCCESS else PaymentStatus.FAILED
        if (parsedStatus != expectedStatus) {
            invalid(
                "type is \"$type\" but data.status is \"$status\" — the event contradicts itself, " +
                    "so neither field can be trusted",
            )
        }

        // 3. EVIDENCE, via the one semantic model.
        val judgement = PaymentSemantics.judge(
            Payment(
                id = paymentId,
                status = parsedStatus,
                mpesaReceipt = data["mpesaReceipt"] as? String,
                resultCode = normalizeCode(resultCode),
                resultDesc = data["resultDesc"] as? String,
            ),
        )
        if (type == "payment.success" && judgement.verdict != PaymentVerdict.PAID) {
            invalid(
                "it announces a successful payment but the record does not prove one " +
                    "(${judgement.reason}). Refusing to hand your handler an unevidenced success — " +
                    "that is how an order gets fulfilled for a payment that never settled",
            )
        }
        if (type == "payment.failed" && judgement.verdict != PaymentVerdict.FAILED) {
            invalid(
                "it announces a failed payment but the record does not support that " +
                    "(${judgement.reason}). In particular a failure notice carrying a RECEIPT, or " +
                    "one carrying a still-in-flight result code, must not be delivered as a settled " +
                    "failure — acting on it is how a paid customer gets charged again",
            )
        }
    }

    private fun asString(v: Any?): String? = v?.toString()

    /** The largest amount a paylod event can carry, in whole KES. Comfortably past any M-Pesa limit. */
    private const val MAX_AMOUNT = Int.MAX_VALUE.toLong()

    /**
     * `data.amount` as an EXACT positive whole number of KES, or `null` if it is not one.
     *
     * The old check was `if (amount !is Number) invalid(...)`, and the conversion was
     * `asInt`: `is Double -> v.toInt()`, `is Long -> v.toInt()`. Both of those are lossy in ways
     * that change the number a merchant reconciles against:
     *
     *   • `100.7` TRUNCATED to `100` — a fractional amount is not a KES amount at all, and silently
     *     dropping the fraction is worse than refusing it.
     *   • `4294967396` (2^32 + 100) WRAPPED to `100` via `Long.toInt()`. A four-billion-shilling
     *     event was delivered to a handler as a hundred-shilling one, with no error anywhere.
     *   • `0` and negatives sailed through as perfectly good `Number`s.
     *
     * So the value must be finite, whole, at least 1, and within range — and it is then converted
     * exactly, or refused. The `d != floor(d)` test runs before the range test so a `Double` too
     * large to be whole cannot pass on rounding.
     */
    private fun wholeAmount(v: Any?): Int? {
        if (v !is Number) return null
        val d = v.toDouble()
        if (!d.isFinite() || d != Math.floor(d)) return null
        val l = when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            else -> {
                // A non-integral Number type (Double, BigDecimal, …). It is already known to be whole
                // and finite; reject anything that cannot be represented exactly as a Long.
                if (d < Long.MIN_VALUE.toDouble() || d > Long.MAX_VALUE.toDouble()) return null
                d.toLong()
            }
        }
        if (l < 1 || l > MAX_AMOUNT) return null
        return l.toInt()
    }

    private fun asLong(v: Any?): Long? = when (v) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    /**
     * As `PaymentValidators.normalizeResultCode`, including the zero exception: a whole `Double`
     * collapses to its integer form for catalog lookup, but a float ZERO keeps its own
     * representation so it cannot be laundered into the canonical success code `"0"`. See
     * [DarajaCatalog.isCanonicalSuccessCode].
     */
    private fun normalizeCode(v: Any?): String? = when (v) {
        null -> null
        // `v == 0.0` is true for -0.0 as well, which is deliberate: a raw JSON `-0` now survives
        // the reader as -0.0 rather than as an integral zero, and it must be kept out of the
        // integer form on this path too — `(-0.0).toLong().toString()` is the literal "0".
        is Double ->
            if (v == Math.floor(v) && v != 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun toEvent(root: Map<String, Any?>): WebhookEvent {
        val rawType = root["type"] as String
        val data = root["data"] as Map<String, Any?>

        // ── `decoded` IS RECOMPUTED, NEVER READ FROM THE PAYLOAD ──────────────────────────────
        //
        // It used to be built field-by-field out of `data.decoded`, which made the payload the
        // authority on two things it must never be the authority on:
        //
        //   • `retryable`. That field means SAFE TO CHARGE AGAIN. A payload advertising
        //     `retryable: true` beside result code 1032 (customer cancelled — non-retryable in the
        //     canonical table) went straight to `event.data.decoded.retryable`, and a handler doing
        //     the documented `if (decoded.retryable) recharge()` charged a second time on the
        //     sender's say-so. A signature proves WHO sent the body; it does not make the body's
        //     claims about M-Pesa semantics true, and a compromised signer or an upstream bug
        //     produces correctly-signed nonsense.
        //
        //   • the shape. `DarajaCategory.fromWire` THROWS `IllegalArgumentException` on an unknown
        //     category, and `retryable` was an unchecked cast — so a malformed value escaped as a
        //     raw parser exception from inside a webhook handler rather than as a typed rejection.
        //
        // The result code is signed and IS authoritative. Everything derived from it comes from the
        // offline catalog — the same table `paylod.decodeError()` and the status path use — so the
        // payload's own `decoded` object is ignored entirely. There is nothing left for a hostile
        // payload to assert.
        val decoded = if (rawType == "payment.failed") {
            val code = data["resultCode"]
            if (code == null) null else DarajaCatalog.decodeError(normalizeCode(code), asString(data["resultDesc"]))
        } else {
            null
        }

        return WebhookEvent(
            type = WebhookEventType.fromWire(rawType),
            rawType = rawType,
            created = asLong(root["created"]),
            data = WebhookEventData(
                paymentId = asString(data["paymentId"]) ?: "",
                applicationId = asString(data["applicationId"]),
                env = asString(data["env"]),
                // `parseWire`, the STRICT parse. This was `fromWire`, which mapped anything it did
                // not recognise to `PENDING`. `assertEventSchema` has already required this field to
                // be one of the three known values, so the lenient fallback could only ever have
                // masked a disagreement between the two — and the lenient function no longer exists.
                status = PaymentStatus.parseWire(asString(data["status"])),
                amount = wholeAmount(data["amount"]),
                phone = asString(data["phone"]),
                accountRef = asString(data["accountRef"]),
                mpesaReceipt = asString(data["mpesaReceipt"]),
                checkoutRequestId = asString(data["checkoutRequestId"]),
                resultCode = normalizeCode(data["resultCode"]),
                resultDesc = asString(data["resultDesc"]),
                decoded = decoded,
            ),
        )
    }
}
