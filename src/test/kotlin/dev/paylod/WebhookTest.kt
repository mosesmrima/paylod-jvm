package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebhookTest {

    private val secret = "whsec_test_secret"

    // A representative success event, compact-serialised so the bytes are stable.
    private val raw =
        """{"type":"payment.success","created":1700000000,"data":{"paymentId":"pay_123","applicationId":"app_1","env":"sandbox","status":"success","amount":100,"phone":"254712345678","accountRef":"order-42","mpesaReceipt":"SFF6XYZ123","checkoutRequestId":"ws_CO_0001","resultCode":0,"resultDesc":"The service request is processed successfully.","decoded":null}}"""
    private val now = 1_700_000_000L

    @Test
    fun `produces exactly HMAC-SHA256 in a t=,v1= header`() {
        val header = Webhooks.sign(raw, secret, now)
        assertTrue(header.startsWith("t=$now,v1="))
        // Re-signing is stable.
        assertEquals(header, Webhooks.sign(raw, secret, now))
    }

    @Test
    fun `uses the x-webhook-signature header name`() {
        assertEquals("x-webhook-signature", Webhooks.SIGNATURE_HEADER)
    }

    // SHARED GOLDEN VECTOR — the SAME secret+timestamp+body+expected-hex pinned, byte-for-byte, in
    // paylod-sdk (test/webhook.test.ts) and paylod-cli, and mirroring the backend signer. If any
    // signing/verifying impl drifts, this fails. DO NOT edit these literals to "fix" a failure.
    @Test
    fun `matches the shared golden vector (cross-repo drift guard)`() {
        val goldenSecret = "whsec_golden_vector_v1"
        val goldenT = 1_700_000_000L
        val goldenBody =
            """{"type":"payment.success","created":1700000000,"data":{"paymentId":"pay_golden","amount":100,"phone":"254712345678"}}"""
        val goldenHeader =
            "t=1700000000,v1=3afe38e4c11734c84fad70dd16bbaeec6057ca998236f253be6bfa09ad2c2eb7"

        assertEquals(goldenHeader, Webhooks.sign(goldenBody, goldenSecret, goldenT))

        // The verifier accepts its own signer's golden output. The fixed vector pins the clock via
        // `nowSec` (freshness is deterministic), the sanctioned way to verify an ancient fixture — a
        // non-positive `toleranceSec` on its own is now refused in production.
        val event = Webhooks.parseAndVerify(
            payload = goldenBody,
            signature = goldenHeader,
            secret = goldenSecret,
            toleranceSec = 0,
            nowSec = goldenT,
        )
        assertEquals("pay_golden", event.data.paymentId)

        // And the client's boolean convenience agrees.
        val (paylod, _) = testClient(emptyList())
        assertTrue(paylod.verifyWebhook(goldenBody, goldenHeader, goldenSecret, toleranceSec = 0, nowSec = goldenT))
    }

    @Test
    fun `accepts a valid signature and returns the typed event`() {
        val event = Webhooks.parseAndVerify(raw, Webhooks.sign(raw, secret, now), secret, nowSec = now)
        assertEquals(WebhookEventType.PAYMENT_SUCCESS, event.type)
        assertEquals("SFF6XYZ123", event.data.mpesaReceipt)
        assertEquals(PaymentStatus.SUCCESS, event.data.status)
    }

    @Test
    fun `rejects a TAMPERED body signed with the original signature`() {
        val header = Webhooks.sign(raw, secret, now)
        val tampered = raw.replace("\"amount\":100", "\"amount\":1")
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(tampered, header, secret, nowSec = now)
        }
        assertEquals(SignatureFailureReason.NO_MATCH, err.reason)
    }

    @Test
    fun `rejects a signature made with the wrong secret`() {
        val header = Webhooks.sign(raw, "whsec_attacker", now)
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, header, secret, nowSec = now)
        }
        assertEquals(SignatureFailureReason.NO_MATCH, err.reason)
    }

    @Test
    fun `rejects a STALE timestamp outside the tolerance (replay)`() {
        val header = Webhooks.sign(raw, secret, now)
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, header, secret, nowSec = now + 301)
        }
        assertEquals(SignatureFailureReason.STALE_TIMESTAMP, err.reason)
    }

    @Test
    fun `rejects a FUTURE-dated timestamp too`() {
        val header = Webhooks.sign(raw, secret, now + 3_600)
        assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, header, secret, nowSec = now)
        }
    }

    @Test
    fun `accepts a timestamp just inside the tolerance`() {
        val header = Webhooks.sign(raw, secret, now)
        val event = Webhooks.parseAndVerify(raw, header, secret, nowSec = now + 299)
        assertEquals("pay_123", event.data.paymentId)
    }

    @Test
    fun `rejects a missing or malformed header`() {
        val missing = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, null, secret)
        }
        assertEquals(SignatureFailureReason.MISSING_SIGNATURE, missing.reason)

        val malformed = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, "deadbeef", secret)
        }
        assertEquals(SignatureFailureReason.MALFORMED_SIGNATURE, malformed.reason)
    }

    @Test
    fun `refuses to verify when no signing secret is configured`() {
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, Webhooks.sign(raw, secret, now), "")
        }
        assertEquals(SignatureFailureReason.MISSING_SIGNATURE, err.reason)
    }

    @Test
    fun `rejects a correctly-signed body that is not a paylod event`() {
        val body = """{"hello":"world"}"""
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(body, Webhooks.sign(body, secret, now), secret, nowSec = now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
    }

    @Test
    fun `rejects a non-numeric timestamp`() {
        val good = Webhooks.sign(raw, secret, now)
        val bad = good.replaceFirst(Regex("^t=\\d+"), "t=abc")
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, bad, secret)
        }
        assertEquals(SignatureFailureReason.MALFORMED_SIGNATURE, err.reason)
    }

    @Test
    fun `verifies an ancient fixture by pinning the clock with nowSec (toleranceSec 0 + fixed clock)`() {
        val header = Webhooks.sign(raw, secret, 1) // ancient
        val event = Webhooks.parseAndVerify(raw, header, secret, toleranceSec = 0, nowSec = 1)
        assertEquals("pay_123", event.data.paymentId)
    }

    @Test
    fun `REFUSES toleranceSec 0 in production (no injected clock) — replay protection stays on`() {
        val header = Webhooks.sign(raw, secret, now)
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, header, secret, toleranceSec = 0)
        }
        assertEquals(SignatureFailureReason.INSECURE_TOLERANCE, err.reason)
    }

    @Test
    fun `REFUSES a negative tolerance too, unless a fixed nowSec is injected`() {
        val header = Webhooks.sign(raw, secret, now)
        assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, header, secret, toleranceSec = -5)
        }
        // …but a fixed clock makes it a legitimate deterministic fixture again.
        val event = Webhooks.parseAndVerify(raw, header, secret, toleranceSec = -5, nowSec = now)
        assertEquals("pay_123", event.data.paymentId)
    }

    @Test
    fun `rejects duplicate t or v1 (comma-combined multi-value header)`() {
        val header = Webhooks.sign(raw, secret, now)
        val v1 = header.substringAfter("v1=")
        // A second, forged pair appended — last-value-wins must NOT accept it.
        val combined = "t=$now,v1=$v1,t=9999999999,v1=$v1"
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, combined, secret, nowSec = now)
        }
        assertEquals(SignatureFailureReason.MALFORMED_SIGNATURE, err.reason)
        assertFalse(Webhooks.verify(raw, combined, secret, nowSec = now))
    }

    @Test
    fun `rejects a v1 that is not exactly 64 lowercase hex chars`() {
        val header = Webhooks.sign(raw, secret, now)
        val v1 = header.substringAfter("v1=")
        // Too short.
        assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, "t=$now,v1=deadbeef", secret, nowSec = now)
        }
        // Upper-case hex is not the lowercase digest we emit.
        val upper = "t=$now,v1=${v1.uppercase()}"
        val err = assertThrows(PaylodSignatureVerificationException::class.java) {
            Webhooks.parseAndVerify(raw, upper, secret, nowSec = now)
        }
        assertEquals(SignatureFailureReason.MALFORMED_SIGNATURE, err.reason)
    }

    @Test
    fun `verifies a ByteArray payload identically to a string`() {
        val header = Webhooks.sign(raw, secret, now)
        val event = Webhooks.parseAndVerify(raw.toByteArray(Charsets.UTF_8), header, secret, nowSec = now)
        assertEquals("pay_123", event.data.paymentId)
    }

    @Test
    fun `fails a re-serialised body whose bytes differ`() {
        // Signed over the compact bytes; verify a spaced variant.
        val header = Webhooks.sign(raw, secret, now)
        val spaced = raw.replace(",", ", ")
        assertFalse(Webhooks.verify(spaced, header, secret, nowSec = now))
    }

    @Test
    fun `verify returns false (not throws) for a correctly-signed body with a schema type failure`() {
        // Correctly signed, valid JSON, type+data present — but decoded.category is not a known enum
        // value, which would blow up parsing with an IllegalArgumentException. The boolean convenience
        // must convert that to a plain verification failure, uniformly with a bad signature.
        val body =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"p","decoded":{"category":"bogus","retryable":false}}}"""
        val header = Webhooks.sign(body, secret, now)
        assertFalse(Webhooks.verify(body, header, secret, nowSec = now))
    }

    @Test
    fun `failed events carry the decoded error identical to the offline catalog`() {
        val (paylod, _) = testClient(emptyList())
        val decoded = paylod.decodeError(1037)
        val failedBody =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123","status":"failed","resultCode":1037,"resultDesc":"DS timeout","decoded":{"code":"${decoded.code}","title":"${decoded.title}","cause":"","fix":"","category":"customer","retryable":true,"customerMessage":"${decoded.customerMessage}"}}}"""
        val event = Webhooks.parseAndVerify(failedBody, Webhooks.sign(failedBody, secret, now), secret, nowSec = now)
        assertEquals(WebhookEventType.PAYMENT_FAILED, event.type)
        assertEquals(
            "The M-Pesa prompt expired before it was answered. Check your phone is on, then try again " +
                "and enter your PIN when it appears.",
            event.data.decoded?.customerMessage,
        )
    }
}
