package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ROUND 7 — the last gate before Maven Central, where a publication is PERMANENT.
 *
 * Every test here carries an `nv-…` tag and is wired into `scripts/non-vacuity.py`, which reverts
 * the protection in the source, runs the tagged test alone, and requires that it FAILS. A test that
 * passes with and without its fix proves nothing, and the harness now also refuses to finish while
 * ANY `nv-` tag in this source tree is missing from its case list.
 */
class SeventhRoundTest {

    // ══ HIGH 2 — a FLOAT result code must not select a catalog entry ═════════════════════════
    //
    // Round 6 fixed "normalization before validation" in the ZERO direction. The float path
    // survived it, in the direction that costs more: a raw JSON `1032.0` (or `1.032e3`) was
    // collapsed to the canonical string `"1032"`, which selects the CANCELLED-BY-CUSTOMER entry —
    // `retryable = true`, "offer a clear retry button". A number Daraja never sent, turned into a
    // confident instruction to charge the customer a second time.

    @Test
    @Tag("nv-float-status")
    fun `a FLOAT result code on a status read never selects the retryable cancellation entry`() {
        for (wire in listOf("1032.0", "1.032e3")) {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 200,
                        raw = """{"id":"pay_123","status":"failed","mpesaReceipt":null,""" +
                            """"resultCode":$wire,"resultDesc":"Request cancelled by user"}""",
                    ),
                ),
            )
            val payment = paylod.status("pay_123")

            // The wire type survives: it is NOT laundered into the canonical integer spelling.
            assertNotEquals("1032", payment.resultCode, "float $wire was laundered into \"1032\"")

            val outcome = Outcomes.of(payment)
            assertFalse(outcome.retryable, "float $wire produced a retryable outcome")
            assertFalse(outcome.detail?.retryable ?: false, "float $wire produced a retryable detail")
            assertNotEquals(
                "1032", outcome.code,
                "float $wire selected the cancellation catalog entry",
            )
            assertFalse(outcome.paid)
        }
    }

    @Test
    @Tag("nv-float-status")
    fun `only an INTEGRAL token or an exact canonical string reaches the catalog`() {
        // The control: a genuine integral 1032 still decodes to the cancellation entry, so the fix
        // is a narrowing of the accepted forms and not a blanket refusal.
        assertEquals("1032", DarajaCatalog.decodeError(1032L).code)
        assertEquals("1032", DarajaCatalog.decodeError("1032").code)
        assertTrue(DarajaCatalog.decodeError(1032L).retryable)

        // And the float forms of the very same number do not.
        for (v in listOf(1032.0, 1.032e3, -0.0, 0.0)) {
            assertNotEquals(
                "1032", DarajaCatalog.decodeError(v).code,
                "float $v selected the cancellation entry",
            )
            assertFalse(DarajaCatalog.decodeError(v).retryable, "float $v decoded as retryable")
        }
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(0.0))
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(-0.0))
    }

    @Test
    @Tag("nv-float-webhook")
    fun `a FLOAT result code in a signed webhook never selects the retryable entry`() {
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L
        val body =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                """"status":"failed","amount":100,"resultCode":1032.0,""" +
                """"resultDesc":"Request cancelled by user"}}"""
        val header = Webhooks.sign(body, secret, now)

        // A float code is not a canonical Daraja result code, so the event is refused outright —
        // it announces a failure it cannot evidence. The important part is what does NOT happen:
        // no `decoded.retryable = true` is ever handed to a handler.
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, header, secret, 300, now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)

        // The integral control still parses, and still decodes as the cancellation entry.
        val ok =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                """"status":"failed","amount":100,"resultCode":1032,""" +
                """"resultDesc":"Request cancelled by user"}}"""
        val event = Webhooks.parseAndVerifyAt(ok, Webhooks.sign(ok, secret, now), secret, 300, now)
        assertEquals("1032", event.data.resultCode)
        assertEquals("1032", event.data.decoded?.code)
    }

    // ══ HIGH 3 — EVERY exposed `retryable`, not just the top-level one ═══════════════════════

    @Test
    @Tag("nv-detail-retryable")
    fun `no non-FAILED verdict exposes a retryable detail, over the whole cross-product`() {
        val receipts = listOf(null, "SFF6XYZ123")
        val codes = listOf(null, "0", "1032", "2001", "1", "1037", "4999", "500.001.1001", "1032.0")
        var checked = 0
        var sawIndeterminateWithDetail = false

        for (status in PaymentStatus.entries) {
            for (receipt in receipts) {
                for (code in codes) {
                    val p = Payment("p", status, receipt, code, null)
                    val verdict = PaymentSemantics.judge(p).verdict
                    val o = Outcomes.of(p)
                    checked++

                    // The nested field is the one that leaked. `outcome.retryable` was already
                    // false here; `outcome.detail.retryable` was still true and still documented as
                    // "safe to charge again".
                    if (verdict != PaymentVerdict.FAILED) {
                        assertFalse(
                            o.detail?.retryable ?: false,
                            "$verdict exposed detail.retryable = true: $p",
                        )
                        assertFalse(o.retryable, "$verdict exposed retryable = true: $p")
                        if (verdict == PaymentVerdict.INDETERMINATE && o.detail != null) {
                            sawIndeterminateWithDetail = true
                        }
                    }
                    // No exposed `retryable` may ever disagree with another.
                    if (o.detail != null) {
                        assertEquals(
                            o.retryable, o.detail!!.retryable,
                            "outcome.retryable and detail.retryable disagree: $p",
                        )
                    }
                    // A `toString()` of the outcome must not claim retryability either — this is a
                    // data class, so the generated string prints the nested field verbatim.
                    if (!o.retryable) {
                        assertFalse(
                            o.toString().contains("retryable=true"),
                            "toString() advertises retryability on a non-retryable outcome: $p",
                        )
                    }
                }
            }
        }
        assertEquals(PaymentStatus.entries.size * receipts.size * codes.size, checked)
        // The named case must actually be IN the cross-product, or this test is vacuous by
        // construction: `failed` + receipt + 1032 is INDETERMINATE and carries a decoded detail.
        assertTrue(sawIndeterminateWithDetail, "the cross-product never produced the leaking shape")
    }

    @Test
    @Tag("nv-detail-retryable")
    fun `THE named case - failed plus receipt plus 1032 is retryable nowhere`() {
        val p = Payment("pay_123", PaymentStatus.FAILED, "SFF6XYZ123", "1032", null)
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(p).verdict)
        val o = Outcomes.of(p)
        assertFalse(o.retryable)
        assertFalse(o.detail!!.retryable)
        assertFalse(o.paid)
        assertEquals(OutcomeStatus.PENDING, o.status)
        // The diagnostics survive the adjustment — only the dangerous claim is overridden.
        assertEquals("1032", o.detail!!.code)
        assertTrue(o.detail!!.title.isNotBlank())

        // CONTROL: the same code on a verdict that IS a proven terminal failure keeps the catalog's
        // answer. The fix must not flatten every `retryable` to false.
        val cancelled = Payment("pay_123", PaymentStatus.FAILED, null, "1032", null)
        assertEquals(PaymentVerdict.FAILED, PaymentSemantics.judge(cancelled).verdict)
        val co = Outcomes.of(cancelled)
        assertTrue(co.retryable, "a genuine cancellation must stay retryable")
        assertTrue(co.detail!!.retryable)
        assertEquals(OutcomeStatus.CANCELLED, co.status)
    }

    // ══ MEDIUM 4 — server-controlled fields are never handed back unredacted ═════════════════

    private val token = "Bearer mp_live_abcdef0123456789"

    @Test
    @Tag("nv-echo-ident")
    fun `a credential echoed into an IDENTIFIER is refused as indeterminate, not exposed`() {
        // The collect ack. `CollectAck` is a data class, so its generated toString() prints both
        // of these fields, and logging the ack is the ordinary thing to do with one.
        for (field in listOf("paymentId", "checkoutRequestId")) {
            val ack = ACK.toMutableMap().apply { this[field] = token }
            val (paylod, _) = testClient(listOf(Step(status = 202, json = ack)))
            val err = assertThrows<PaylodApiException> {
                paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k"))
            }
            assertTrue(err.indeterminate, "$field echo was not indeterminate")
            assertFalse(err.toString().contains("mp_live_"), "the credential survived into the error")
        }

        // The status read: the id and the receipt.
        for (field in listOf("id", "mpesaReceipt")) {
            val body = paymentJson(status = "success", mpesaReceipt = "SFF6XYZ123", resultCode = 0)
                .toMutableMap().apply { this[field] = token }
            val (paylod, _) = testClient(listOf(Step(status = 200, json = body)))
            val err = assertThrows<PaylodApiException> { paylod.status("pay_123") }
            assertTrue(err.indeterminate, "$field echo was not indeterminate")
            assertFalse(err.toString().contains("mp_live_"))
        }
    }

    @Test
    @Tag("nv-echo-text")
    fun `a credential echoed into optional server TEXT is redacted before exposure`() {
        val body = paymentJson(
            status = "failed",
            resultCode = 2001,
            resultDesc = "rejected for $token, see docs",
        )
        val (paylod, _) = testClient(listOf(Step(status = 200, json = body)))
        val payment = paylod.status("pay_123")

        assertFalse(payment.resultDesc!!.contains("mp_live_"), "resultDesc leaked the credential")
        assertTrue(payment.resultDesc!!.contains("[redacted]"))
        // The GENERATED toString() is the actual exposure surface, so assert on it directly.
        assertFalse(payment.toString().contains("mp_live_"))
        assertFalse(Outcomes.of(payment).toString().contains("mp_live_"))
        // Still decodable — scrubbing must not destroy the diagnostic.
        assertEquals("2001", Outcomes.of(payment).code)
    }

    @Test
    @Tag("nv-echo-webhook")
    fun `a webhook cannot place a credential into an id, a receipt, or a description`() {
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L

        fun event(extra: String) =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                """"status":"failed","amount":100,"resultCode":2001,$extra}}"""

        for (extra in listOf(
            """"resultDesc":"x","checkoutRequestId":"$token"""",
            """"resultDesc":"x","mpesaReceipt":"$token"""",
        )) {
            val body = event(extra)
            val err = assertThrows<PaylodSignatureVerificationException> {
                Webhooks.parseAndVerifyAt(body, Webhooks.sign(body, secret, now), secret, 300, now)
            }
            assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
        }

        // A credential in `paymentId` is refused too.
        val idBody =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"$token",""" +
                """"status":"failed","amount":100,"resultCode":2001,"resultDesc":"x"}}"""
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(idBody, Webhooks.sign(idBody, secret, now), secret, 300, now)
        }

        // Optional TEXT is scrubbed and delivered, not refused.
        val descBody = event(""""resultDesc":"rejected for $token"""")
        val ev = Webhooks.parseAndVerifyAt(descBody, Webhooks.sign(descBody, secret, now), secret, 300, now)
        assertFalse(ev.data.resultDesc!!.contains("mp_live_"))
        assertFalse(ev.toString().contains("mp_live_"))
        assertEquals("2001", ev.data.decoded?.code)
    }

    // ══ MEDIUM 5 — a FAILED claim with NO evidence is a claim, not a settlement ══════════════

    @Test
    @Tag("nv-failed-noevidence")
    fun `FAILED with no evidence at all is INDETERMINATE, never a terminal failure`() {
        val bare = Payment("pay_123", PaymentStatus.FAILED, null, null, null)
        val j = PaymentSemantics.judge(bare)
        assertEquals(PaymentEvidence.NONE, j.evidence)
        assertEquals(
            PaymentVerdict.INDETERMINATE, j.verdict,
            "a bare {status: failed} was accepted as terminal on its own say-so",
        )

        val o = Outcomes.of(bare)
        assertEquals(OutcomeStatus.PENDING, o.status)
        assertFalse(o.retryable)
        assertFalse(o.detail?.retryable ?: false)
        assertFalse(o.paid)
        assertNull(o.receipt)
    }

    @Test
    @Tag("nv-failed-noevidence")
    fun `CONTROL - FAILED with genuine failure evidence is still retryable where the catalog says so`() {
        // The over-correction guard. Narrowing the FAILED cell must not turn every failure into an
        // un-actionable PENDING: a real failure code still settles, and still carries the catalog's
        // retryability verbatim.
        val retryable = mapOf("1032" to true, "2001" to true, "1037" to true)
        for ((code, expected) in retryable) {
            val p = Payment("pay_123", PaymentStatus.FAILED, null, code, null)
            assertEquals(PaymentVerdict.FAILED, PaymentSemantics.judge(p).verdict, "code $code")
            val o = Outcomes.of(p)
            assertEquals(expected, o.retryable, "code $code retryability changed")
            assertEquals(expected, o.detail!!.retryable, "code $code detail retryability changed")
            assertFalse(o.paid)
        }
    }

    @Test
    @Tag("nv-wh-failed-needscode")
    fun `a payment_failed webhook must carry a canonical failure code`() {
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L

        // No code at all — a failure notice with nothing behind it.
        val bare =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                """"status":"failed","amount":100}}"""
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(bare, Webhooks.sign(bare, secret, now), secret, 300, now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)

        // A non-canonical spelling of a real code is refused too.
        for (code in listOf(""""  2001"""", """"+2001"""", """"0x7d1"""")) {
            val body =
                """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                    """"status":"failed","amount":100,"resultCode":$code}}"""
            assertThrows<PaylodSignatureVerificationException>("code $code was accepted") {
                Webhooks.parseAndVerifyAt(body, Webhooks.sign(body, secret, now), secret, 300, now)
            }
        }

        // CONTROL: a canonical failure code is delivered, decoded, and keeps its retryability.
        val ok =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_123",""" +
                """"status":"failed","amount":100,"resultCode":2001,"resultDesc":"Wrong PIN"}}"""
        val ev = Webhooks.parseAndVerifyAt(ok, Webhooks.sign(ok, secret, now), secret, 300, now)
        assertEquals("2001", ev.data.resultCode)
        assertNotNull(ev.data.decoded)
        assertTrue(ev.data.decoded!!.retryable)
    }
}
