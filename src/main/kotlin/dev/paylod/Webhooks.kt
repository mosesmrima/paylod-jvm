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
     * @param nowSec injectable clock (unix seconds); defaults to the system clock.
     */
    @JvmStatic
    @JvmOverloads
    fun parseAndVerify(
        payload: ByteArray,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
        nowSec: Long? = null,
    ): WebhookEvent {
        val root = verifySignature(payload, signature, secret, toleranceSec, nowSec)
        assertEventSchema(root)
        return toEvent(root)
    }

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
        nowSec: Long? = null,
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
                    "normal tolerance and inject the fixture's own clock via nowSec.",
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
        nowSec: Long? = null,
    ): Map<String, Any?> = verifySignature(
        payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec,
    )

    @JvmStatic
    @JvmOverloads
    fun parseAndVerify(
        payload: String,
        signature: String?,
        secret: String,
        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,
        nowSec: Long? = null,
    ): WebhookEvent = parseAndVerify(
        payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec,
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
        nowSec: Long? = null,
    ): Boolean = try {
        parseAndVerify(payload, signature, secret, toleranceSec, nowSec)
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
        nowSec: Long? = null,
    ): Boolean = verify(payload.toByteArray(StandardCharsets.UTF_8), signature, secret, toleranceSec, nowSec)

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

        val amount = data["amount"]
        if (amount !is Number) invalid("data.amount is not a number")

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

    private fun asInt(v: Any?): Int? = when (v) {
        null -> null
        is Long -> v.toInt()
        is Int -> v
        is Double -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asLong(v: Any?): Long? = when (v) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun normalizeCode(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun toEvent(root: Map<String, Any?>): WebhookEvent {
        val rawType = root["type"] as String
        val data = root["data"] as Map<String, Any?>

        val decodedMap = data["decoded"] as? Map<String, Any?>
        val decoded = if (decodedMap != null) {
            DecodedError(
                code = decodedMap["code"]?.toString() ?: "",
                title = decodedMap["title"]?.toString() ?: "",
                cause = decodedMap["cause"]?.toString() ?: "",
                fix = decodedMap["fix"]?.toString() ?: "",
                category = DarajaCategory.fromWire(decodedMap["category"]?.toString() ?: "mpesa_system"),
                retryable = decodedMap["retryable"] as? Boolean ?: false,
                customerMessage = decodedMap["customerMessage"]?.toString() ?: "",
            )
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
                status = if (data.containsKey("status")) PaymentStatus.fromWire(asString(data["status"])) else null,
                amount = asInt(data["amount"]),
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
