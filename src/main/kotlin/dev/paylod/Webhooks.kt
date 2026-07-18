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

        // `t` must always be an integer, regardless of tolerance — a non-numeric timestamp is malformed.
        val t = parsed.t.toLongOrNull()
            ?: throw PaylodSignatureVerificationException(
                SignatureFailureReason.MALFORMED_SIGNATURE,
                "Signature timestamp is not a number.",
            )

        if (toleranceSec > 0) {
            val now = nowSec ?: (System.currentTimeMillis() / 1000)
            if (Math.abs(now - t) > toleranceSec) {
                throw PaylodSignatureVerificationException(
                    SignatureFailureReason.STALE_TIMESTAMP,
                    "Signature timestamp is outside the ${toleranceSec}s tolerance (replay?).",
                )
            }
        } else if (nowSec == null) {
            // A non-positive tolerance would DISABLE replay protection. That is only ever acceptable
            // with a fixed, injected clock (a pinned test vector). In production — no `nowSec` — refuse
            // it loudly rather than silently accept replays of any age.
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INSECURE_TOLERANCE,
                "toleranceSec must be a positive number of seconds. A non-positive tolerance disables " +
                    "webhook replay protection and is only permitted in tests that inject a fixed nowSec.",
            )
        }
        // else: toleranceSec <= 0 AND a fixed `nowSec` was injected — a deterministic fixed-vector test.

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
        if (parsedBody !is Map<*, *> || parsedBody["type"] !is String || parsedBody["data"] !is Map<*, *>) {
            throw PaylodSignatureVerificationException(
                SignatureFailureReason.INVALID_PAYLOAD,
                "Webhook body is not a paylod event (missing `type`/`data`).",
            )
        }

        @Suppress("UNCHECKED_CAST")
        return toEvent(parsedBody as Map<String, Any?>)
    }

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
