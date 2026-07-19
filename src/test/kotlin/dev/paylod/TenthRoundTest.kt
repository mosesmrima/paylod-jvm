package dev.paylod

import dev.paylod.internal.CredentialShapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ROUND 10 — conformance with `docs/SDK-CONFORMANCE.md`.
 *
 * ── Why this file is organised by REQUIREMENT and not by finding ──────────────────────────────
 * Nine rounds of review produced 95 findings across four SDKs, and the dominant failure was never
 * that a defect was hard to fix. It was that a fix landing in one SDK never reached the other
 * three: PHP closed the redaction-placeholder-as-receipt defect in round 9, and round 10 found the
 * IDENTICAL defect still open in Node, Python and this SDK — because each SDK was working from its
 * own findings list rather than from a shared contract.
 *
 * So the unit of work is the specification, and each test below names the requirement it proves.
 * A requirement with no test here is a requirement this SDK cannot claim.
 */
class TenthRoundTest {

    private fun paidPayment(receipt: String?, code: Any? = "0") = Payment(
        id = "pay_123",
        status = PaymentStatus.SUCCESS,
        mpesaReceipt = receipt,
        resultCode = code?.toString(),
        resultDesc = null,
    )

    // ── §3.3 — a receipt is validated against a POSITIVE grammar ────────────────────────────

    /**
     * §3.3. The grammar is `^[A-Z0-9]{10}\z`, derived from every real receipt in the paylod
     * fixtures, and it is the same grammar the PHP, Node and Python SDKs enforce.
     */
    @Test
    @Tag("nv-receipt-grammar")
    fun `a receipt must be ten uppercase alphanumerics, and nothing else counts`() {
        // Real fixtures — these MUST still be evidence, or the grammar is over-corrected.
        for (real in listOf("SFF6XYZ123", "RGH4TYU789", "QK21ABCD99", "0123456789", "ABCDEFGHIJ")) {
            assertTrue(PaymentSemantics.isValidReceipt(real), "real receipt rejected: $real")
        }

        // Everything a non-emptiness test used to accept.
        val impostors = listOf(
            "[redacted]",           // THE defect: the SDK's own mask satisfied the old check
            "***",
            "REDACTED",
            "null", "undefined", "N/A", "-", "pending", "0", "",
            "   ",
            "SFF6XYZ12",            // nine — too short
            "SFF6XYZ1234",          // eleven — too long
            "sff6xyz123",           // lowercase
            "SFF6XYZ-23",           // punctuation
            "SFF6 YZ123",           // embedded space
            "SFF6XYZ12é",      // non-ASCII
        )
        for (bad in impostors) {
            assertFalse(PaymentSemantics.isValidReceipt(bad), "impostor accepted as a receipt: $bad")
        }
        assertFalse(PaymentSemantics.isValidReceipt(null))
    }

    /**
     * §3.3 + §7.1. THE anchoring trap, stated as its own test because it is invisible in the
     * pattern.
     *
     * In Java (as in PCRE and Python `re`) `$` also matches immediately before a trailing newline,
     * so a `^[A-Z0-9]{10}$` pattern would ACCEPT `"SFF6XYZ123\n"`. A receipt carrying a smuggled
     * newline is precisely the re-encoded value the grammar exists to reject, and a reviewer reading
     * the pattern cannot see the difference. `Regex.matches` is a full-region match, which is the
     * Kotlin equivalent of `\z`-anchoring.
     */
    @Test
    @Tag("nv-receipt-grammar-anchor")
    fun `a receipt with a trailing newline is refused (the Java dollar-anchor trap)`() {
        assertFalse(PaymentSemantics.isValidReceipt("SFF6XYZ123\n"))
        assertFalse(PaymentSemantics.isValidReceipt("SFF6XYZ123\r\n"))
        assertFalse(PaymentSemantics.isValidReceipt("\nSFF6XYZ123"))
        // The demonstration that this is not a theoretical concern: the `$`-anchored spelling a
        // reviewer would wave through DOES accept it, in this language, today.
        assertTrue(Regex("^[A-Z0-9]{10}$").containsMatchIn("SFF6XYZ123\n"))
    }

    // ── §3.4 — sanitizer output satisfies NOTHING ───────────────────────────────────────────

    /**
     * §3.4. The defect this requirement exists for, end to end: a server echoes a credential into
     * `mpesaReceipt`, the SDK's redaction rewrites it to `[redacted]`, and the resulting non-blank
     * string used to satisfy the evidence check — so HIDING a secret manufactured proof of payment
     * and a `success` claim resolved to PAID.
     */
    @Test
    @Tag("nv-receipt-placeholder")
    fun `a redaction placeholder in the receipt is never payment evidence`() {
        for (mask in listOf("[redacted]", CredentialShapes.MASK, "***", "[FILTERED]", "XXXXXXXXXX")) {
            val p = paidPayment(mask, code = null)
            assertFalse(PaymentSemantics.hasReceipt(p), "$mask counted as a receipt")
            assertEquals(PaymentEvidence.NONE, PaymentSemantics.evidenceFor(p))
            // A `success` claim with a placeholder receipt and NO result code proves nothing.
            assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(p).verdict)
            val outcome = Outcomes.of(p)
            assertFalse(outcome.paid, "$mask produced a PAID outcome")
            assertFalse(outcome.retryable)
            assertNull(outcome.receipt)
        }
    }

    /**
     * §3.4. `XXXXXXXXXX` is the case that proves the placeholder check is INDEPENDENT of the
     * grammar rather than an accident of which characters the current mask happens to use: it
     * satisfies `[A-Z0-9]{10}` exactly, and is still refused.
     */
    @Test
    @Tag("nv-receipt-placeholder")
    fun `a mask that satisfies the receipt grammar is still refused`() {
        assertTrue(Regex("[A-Z0-9]{10}").matches("XXXXXXXXXX"))
        assertFalse(PaymentSemantics.isValidReceipt("XXXXXXXXXX"))
    }

    /** §3.4 — the identifier and correlation surfaces, not just the receipt. */
    @Test
    @Tag("nv-ident-placeholder")
    fun `a sanitizer placeholder is refused as a paymentId, checkoutRequestId and idempotency key`() {
        // Collect ack: a placeholder paymentId names no payment we could ever poll.
        val (client, _) = testClient(
            listOf(Step(status = 202, json = ACK + mapOf("paymentId" to "[redacted]"))),
        )
        val e = assertThrows<PaylodApiException> {
            client.collect("254712345678", 10, idempotencyKey = "key-1")
        }
        assertTrue(e.indeterminate)
        assertTrue(e.message!!.contains("placeholder"), e.message)

        val (client2, _) = testClient(
            listOf(Step(status = 202, json = ACK + mapOf("checkoutRequestId" to "***"))),
        )
        assertThrows<PaylodApiException> {
            client2.collect("254712345678", 10, idempotencyKey = "key-1")
        }

        // The idempotency key itself: two attempts masked the same way would collide.
        val bad = assertThrows<PaylodInvalidRequestException> {
            val (c, _) = testClient(listOf(Step(status = 202, json = ACK)))
            c.collect("254712345678", 10, idempotencyKey = "[redacted]")
        }
        assertTrue(bad.message!!.contains("placeholder"), bad.message)
    }

    // ── §2.6 — malformed bytes are refused, never decoded with replacement semantics ────────

    /**
     * §2.6. The demonstration first: replacement decoding turns unreadable bytes into an ordinary
     * nonblank string, and collapses distinct inputs into an identical one.
     */
    @Test
    @Tag("nv-strict-utf8")
    fun `replacement decoding is exactly the laundering this refuses`() {
        val a = byteArrayOf(-1, -2, -1, -2, -1, -2, -1, -2, -1, -2)      // FF FE x5
        val b = byteArrayOf(-64, -128, -64, -128, -64, -128, -64, -128, -64, -128) // C0 80 x5

        // What the JDK's lossy constructor does with them, which is why it may not be used here.
        val lossyA = String(a, Charsets.UTF_8)
        val lossyB = String(b, Charsets.UTF_8)
        assertTrue(lossyA.isNotBlank(), "unreadable bytes became a blank string, not the hazard")
        assertEquals(lossyA, lossyB, "distinct invalid sequences did NOT collapse — fixture is stale")

        // What this SDK does with them.
        for (bytes in listOf(a, b)) {
            assertThrows<dev.paylod.internal.Utf8.InvalidUtf8Exception> {
                dev.paylod.internal.Utf8.decode(bytes, "test input")
            }
        }
        // Valid UTF-8, including multi-byte, still decodes.
        assertEquals("héllo ✓", dev.paylod.internal.Utf8.decode("héllo ✓".toByteArray(Charsets.UTF_8), "x"))
    }

    /** §2.6 — on the real webhook path, end to end, with a genuinely valid signature. */
    @Test
    @Tag("nv-strict-utf8")
    fun `a correctly signed webhook body that is not valid UTF-8 is refused`() {
        val secret = "whsec_test_secret_value"
        // A body that is well-formed JSON in ASCII except for raw invalid bytes inside the receipt.
        val prefix = """{"id":"evt_1","type":"payment.success","data":{"mpesaReceipt":""""
        val suffix = """"}}"""
        val body = prefix.toByteArray(Charsets.UTF_8) +
            byteArrayOf(-1, -2, -1, -2, -1, -2, -1, -2, -1, -2) +
            suffix.toByteArray(Charsets.UTF_8)

        // Signed for real, so the refusal cannot be mistaken for a signature failure.
        val sig = Webhooks.sign(body, secret)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body, sig, secret)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, e.reason)
        assertTrue(e.message!!.contains("UTF-8"), e.message)

        // CONTROL (§8.5): the identical body with a VALID receipt in place of the bad bytes must be
        // accepted, or this test would pass for the wrong reason.
        val good = (prefix + "SFF6XYZ123" + suffix).toByteArray(Charsets.UTF_8)
        val map = Webhooks.verifySignature(good, Webhooks.sign(good, secret), secret)
        assertEquals("evt_1", map["id"])
    }

    // ── §3.7 — a non-retryable outcome never invites another attempt ────────────────────────

    /**
     * §3.7, enforced as a CATALOG-WIDE INVARIANT rather than as a list of codes.
     *
     * The spec names 17, 26, 1025 and 9999. Seventeen entries actually violated it, which is the
     * argument for the invariant form: a test that checks the four named codes passes while the
     * other thirteen keep telling customers to pay again, and the next entry someone adds is
     * unguarded by construction.
     *
     * Why it matters concretely: `retryable` is a boolean a MERCHANT branches on, but `message` is
     * the sentence a CUSTOMER reads. When they disagree, the customer wins — they tap Pay a second
     * time. None of these codes proves no debit occurred, so the second tap can be a second charge
     * for a payment the first tap may already have completed.
     */
    @Test
    @Tag("nv-no-retry-language")
    fun `no non-retryable catalog entry invites another payment attempt`() {
        val invitation = Regex("try again|retry|pay again|again", RegexOption.IGNORE_CASE)
        val offenders = DarajaCatalog.allEntries
            .filter { !it.retryable && invitation.containsMatchIn(it.customerMessage) }
            .map { "${it.code} (${it.family}): ${it.customerMessage}" }
        assertTrue(
            offenders.isEmpty(),
            "non-retryable entries whose customer message invites another attempt:\n" +
                offenders.joinToString("\n"),
        )
        // The fixture must be able to discriminate (§8.5): assert the catalog is actually populated
        // and actually contains non-retryable entries, so "no offenders" cannot mean "nothing was
        // examined".
        assertTrue(DarajaCatalog.allEntries.size >= 30, "catalog did not load")
        assertTrue(DarajaCatalog.allEntries.count { !it.retryable } >= 15, "nothing to check")
    }

    /** §3.7 — the same invariant on the two synthesised decodes, which are not catalog rows. */
    @Test
    @Tag("nv-no-retry-language")
    fun `the fallback decodes never invite another payment attempt either`() {
        val invitation = Regex("try again|retry|pay again|again", RegexOption.IGNORE_CASE)
        // Unknown, absent, non-canonical and malformed — every route to a synthesised decode.
        for (code in listOf<Any?>(null, "", 4242, "999999", " 0", "0.0", "500.0", "1032\n", true, 1032.0)) {
            val d = DarajaCatalog.decodeError(code, null)
            assertFalse(d.retryable, "synthesised decode for $code was retryable")
            assertFalse(
                invitation.containsMatchIn(d.customerMessage),
                "decode for $code invited another attempt: ${d.customerMessage}",
            )
        }
    }

    /** §3.6 — `retryable` agrees with the verdict at EVERY nesting level, over the cross-product. */
    @Test
    @Tag("nv-retryable-agrees")
    fun `no outcome exposes a nested retryable that contradicts the top-level one`() {
        val codes = listOf(null, "0", "1032", "2001", "4999", "999999", " 0", "0.0", "17", "9999")
        val receipts = listOf(null, "SFF6XYZ123", "[redacted]", "not-a-receipt")
        var checked = 0
        for (claim in PaymentStatus.entries) {
            for (code in codes) {
                for (receipt in receipts) {
                    val out = Outcomes.of(Payment("pay_1", claim, receipt, code, null))
                    if (!out.retryable) {
                        assertFalse(
                            out.detail?.retryable ?: false,
                            "claim=$claim code=$code receipt=$receipt: top-level retryable=false " +
                                "but detail.retryable=true",
                        )
                    }
                    if (out.paid) assertFalse(out.retryable, "a PAID outcome was retryable")
                    checked++
                }
            }
        }
        // §8.5 — the sweep must actually have swept something.
        assertEquals(PaymentStatus.entries.size * codes.size * receipts.size, checked)
        assertTrue(checked >= 100, "cross-product too small to discriminate")
    }

    // ── §3.5 — all seven rows of the required resolution table ──────────────────────────────

    /**
     * §3.5. Every row, INCLUDING the three controls.
     *
     * The controls are the half of the table that a naive over-correction fails: an SDK that
     * answers INDETERMINATE to everything is exactly as non-conformant as one that answers PAID to
     * everything, because it never settles a payment and never lets a merchant retry a genuine
     * failure. Both halves are asserted here so neither can be traded for the other.
     */
    @Test
    @Tag("nv-resolution-table")
    fun `the seven required resolutions, controls included`() {
        fun p(status: PaymentStatus, receipt: String? = null, code: String? = null) =
            Payment("pay_123", status, receipt, code, null)

        // 1. success + code 0 + valid receipt -> PAID.
        val paid = Outcomes.of(p(PaymentStatus.SUCCESS, "SFF6XYZ123", "0"))
        assertTrue(paid.paid)
        assertEquals(OutcomeStatus.SUCCEEDED, paid.status)
        assertEquals("SFF6XYZ123", paid.receipt)
        assertFalse(paid.retryable)

        // 2. pending + code 0 -> NOT paid, indeterminate.
        val pendingZero = Outcomes.of(p(PaymentStatus.PENDING, null, "0"))
        assertFalse(pendingZero.paid)
        assertEquals(OutcomeStatus.PENDING, pendingZero.status)
        assertFalse(pendingZero.retryable)
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(p(PaymentStatus.PENDING, null, "0")).verdict)

        // 3. pending + receipt -> indeterminate.
        val pendingReceipt = Outcomes.of(p(PaymentStatus.PENDING, "SFF6XYZ123", null))
        assertFalse(pendingReceipt.paid)
        assertFalse(pendingReceipt.retryable)
        assertEquals(
            PaymentVerdict.INDETERMINATE,
            PaymentSemantics.judge(p(PaymentStatus.PENDING, "SFF6XYZ123", null)).verdict,
        )

        // 4. failed + receipt + code 1032 -> indeterminate, NOT retryable. The double-charge case.
        val conflict = p(PaymentStatus.FAILED, "SFF6XYZ123", "1032")
        assertEquals(PaymentEvidence.CONFLICT, PaymentSemantics.evidenceFor(conflict))
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(conflict).verdict)
        val conflictOut = Outcomes.of(conflict)
        assertFalse(conflictOut.paid)
        assertFalse(conflictOut.retryable)
        // §3.6 — at EVERY nesting level, not just the top one.
        assertFalse(conflictOut.detail?.retryable ?: false)

        // 5. failed + no evidence -> indeterminate, NOT terminal.
        val bare = p(PaymentStatus.FAILED, null, null)
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(bare).verdict)
        val bareOut = Outcomes.of(bare)
        assertEquals(OutcomeStatus.PENDING, bareOut.status)
        assertFalse(bareOut.retryable)

        // ── CONTROL 6. failed + a genuine catalog failure code -> retryable where the catalog says
        // so. 1032 (customer cancelled) is retryable in the catalog; without a contradicting
        // receipt it must stay that way, or the SDK never lets a merchant re-offer payment.
        val genuineCancel = p(PaymentStatus.FAILED, null, "1032")
        assertEquals(PaymentVerdict.FAILED, PaymentSemantics.judge(genuineCancel).verdict)
        val cancelOut = Outcomes.of(genuineCancel)
        assertEquals(OutcomeStatus.CANCELLED, cancelOut.status)
        assertTrue(cancelOut.retryable, "over-correction: a genuine cancellation is no longer retryable")

        // ── CONTROL 6b. A genuine NON-retryable catalog failure is still a terminal FAILED.
        val wrongPin = p(PaymentStatus.FAILED, null, "2001")
        val pinOut = Outcomes.of(wrongPin)
        assertEquals(OutcomeStatus.FAILED, pinOut.status)
        assertFalse(pinOut.paid)

        // ── CONTROL 7. any claim + an unknown/non-canonical code -> indeterminate (§1.5).
        for (code in listOf("999999", " 0", "0.0", "500.0", "1032\n", "+0", "00")) {
            for (claim in PaymentStatus.entries) {
                val out = Outcomes.of(p(claim, null, code))
                assertFalse(out.paid, "code $code with claim $claim produced PAID")
                assertFalse(out.retryable, "code $code with claim $claim invited a retry")
                assertNotEquals(
                    OutcomeStatus.FAILED, out.status,
                    "code $code with claim $claim became a confident terminal failure",
                )
            }
        }
    }
}
