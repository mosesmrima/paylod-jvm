package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DarajaCatalogTest {

    @Test
    fun `decodes success (0)`() {
        val d = DarajaCatalog.decodeError(0)
        assertEquals("0", d.code)
        assertEquals(DarajaCategory.SUCCESS, d.category)
        assertFalse(d.retryable)
    }

    @Test
    fun `decodes cancelled (1032) with the exact customer message`() {
        val d = DarajaCatalog.decodeError(1032)
        assertEquals("Payment cancelled by the customer", d.title)
        assertEquals(DarajaCategory.CUSTOMER, d.category)
        assertTrue(d.retryable)
        assertEquals("Payment cancelled — you can try again whenever you're ready.", d.customerMessage)
    }

    @Test
    fun `wrong PIN (2001) on the STK path is a customer error, not a credentials error`() {
        val d = DarajaCatalog.decodeError(2001)
        assertEquals("Wrong M-Pesa PIN", d.title)
        assertEquals(DarajaCategory.CUSTOMER, d.category)
        assertTrue(d.retryable)
    }

    @Test
    fun `insufficient balance (1) is retryable with a top-up message`() {
        val d = DarajaCatalog.decodeError(1)
        assertEquals(DarajaCategory.BALANCE, d.category)
        assertTrue(d.retryable)
        assertEquals("Your M-Pesa balance is too low. Please top up and try again.", d.customerMessage)
    }

    @Test
    fun `1037 unanswered prompt has the observed-live customer message`() {
        val d = DarajaCatalog.decodeError(1037)
        assertEquals(
            "The M-Pesa prompt expired before it was answered. Check your phone is on, then try again " +
                "and enter your PIN when it appears.",
            d.customerMessage,
        )
    }

    // THE regression that shipped twice: 4999 / 500.001.1001 are PENDING and NEVER retryable.
    @Test
    fun `4999 is pending and never retryable`() {
        assertEquals(StkOutcome.PENDING, DarajaCatalog.classifyStkResult(4999))
        val d = DarajaCatalog.decodeError(4999)
        assertEquals(DarajaCategory.PENDING, d.category)
        assertFalse(d.retryable)
    }

    @Test
    fun `500_001_1001 pending on the STK path, but its terminal message is a failure`() {
        assertEquals(StkOutcome.PENDING, DarajaCatalog.classifyStkResult("500.001.1001", "The transaction is being processed"))
        val pending = DarajaCatalog.decodeError("500.001.1001", "The transaction is being processed")
        assertEquals(DarajaCategory.PENDING, pending.category)
        assertFalse(pending.retryable)

        // Overloaded code: a terminal config message must NOT be treated as pending.
        assertEquals(StkOutcome.FAILED, DarajaCatalog.classifyStkResult("500.001.1001", "merchant does not exist"))
    }

    @Test
    fun `an unknown code is INDETERMINATE — not a failure, and it never invites a retry`() {
        val d = DarajaCatalog.decodeError(4242, "Something odd happened")
        assertEquals("4242", d.code)
        // Conformance 1.5: a canonically shaped code that is not in the catalog is NOT evidence, so
        // it must not become a confident terminal failure. This used to read "Payment failed".
        assertEquals("Payment state unknown", d.title)
        // The operator's own words survive, which is why they were worth keeping.
        assertTrue(d.cause.contains("Something odd happened"))
        assertFalse(d.retryable)
        // Conformance 3.7: the sentence a CUSTOMER reads must agree with `retryable = false`. The
        // old message was "The payment didn't go through. Please try again." beside this same
        // `false` — a merchant renders the message, not the boolean.
        assertFalse(
            Regex("try again|pay again|retry", RegexOption.IGNORE_CASE).containsMatchIn(d.customerMessage),
            "a non-retryable decode invited another attempt: ${d.customerMessage}",
        )
    }

    @Test
    fun `a null code is treated as unknown, not pending`() {
        val d = DarajaCatalog.decodeError(null)
        assertEquals("unknown", d.code)
        assertFalse(d.retryable)
    }

    @Test
    fun `pendingResultCodes is derived from the table`() {
        assertTrue(DarajaCatalog.pendingResultCodes.contains("4999"))
        assertTrue(DarajaCatalog.pendingResultCodes.contains("500.001.1001"))
    }

    @Test
    fun `the whole catalog loaded from resources`() {
        // The Node table ships 32 entries; assert we loaded a full, non-trivial table.
        assertTrue(DarajaCatalog.allEntries.size >= 30)
    }

    // ── 0.2.0: corrected retryable flags (owner-approved) ──────────────────────────────────
    // 17 / 26 / 1025 / 9999 were retryable:true on non-authoritative community evidence. Until
    // no-debit is proven, false is the safe money call.
    @Test
    fun `mpesa-system codes 17, 26, 1025, 9999 are NOT retryable`() {
        for (code in listOf(17, 26, 1025, 9999)) {
            val d = DarajaCatalog.decodeError(code)
            assertEquals(DarajaCategory.MPESA_SYSTEM, d.category, "category for $code")
            assertFalse(d.retryable, "code $code must not be retryable")
        }
    }

    // ── 0.2.0: family-aware decoding ───────────────────────────────────────────────────────
    @Test
    fun `a b2c_c2b_result code decodes terminally, not as pending`() {
        val d = DarajaCatalog.decodeError("C2B00011", family = DarajaFamily.B2C_C2B_RESULT)
        assertEquals("C2B00011", d.code)
        // Non-STK families carry NO pending semantics — it must not become "payment still in progress".
        assertEquals(DarajaCategory.CUSTOMER, d.category)
        assertFalse(d.retryable)
    }

    @Test
    fun `overloaded 500_001_1001 decodes terminally on the api_error surface`() {
        val d = DarajaCatalog.decodeError("500.001.1001", "merchant does not exist", DarajaFamily.API_ERROR)
        assertEquals(DarajaCategory.MPESA_SYSTEM, d.category)
        assertFalse(d.retryable)
        // …while the STK surface (default) keeps its pending meaning.
        val stk = DarajaCatalog.decodeError("500.001.1001", "The transaction is being processed")
        assertEquals(DarajaCategory.PENDING, stk.category)
    }

    @Test
    fun `an STK-defaulted lookup of a non-STK-only code decodes by its real family`() {
        // C2B00011 exists only on b2c_c2b_result; asking for the default STK family must not route it
        // through the STK unknown->pending rule.
        val d = DarajaCatalog.decodeError("C2B00011")
        assertEquals(DarajaCategory.CUSTOMER, d.category)
        assertFalse(d.retryable)
    }

    @Test
    fun `a 500 with an insufficient-funds message is terminal, not pending`() {
        assertEquals(
            StkOutcome.FAILED,
            DarajaCatalog.classifyStkResult("500.001.1001", "The balance is insufficient funds"),
        )
    }

    // ── 0.3.0: an explicit non-STK decode can NEVER return an STK pending entry ─────────────

    @Test
    fun `4999 on the api_error surface decodes terminally, never as STK pending`() {
        val d = DarajaCatalog.decodeError("4999", null, DarajaFamily.API_ERROR)
        assertEquals("4999", d.code)
        // 4999 exists ONLY as an STK *pending* entry. Asked about it on a terminal surface, the
        // decoder must not hand back "payment still in progress" — that would tell a caller to keep
        // polling a code that arrived on a surface with no in-flight semantics at all.
        assertNotEquals(DarajaCategory.PENDING, d.category)
        // No STK pending semantics — and no confident failure claim either, because 4999 is not a
        // code this catalog defines on THIS surface. "Not in flight" and "failed" are different
        // answers; only the first is established here. (Conformance 1.5.)
        assertEquals("Payment state unknown", d.title)
        // And it must never be advertised as safe to charge again.
        assertFalse(d.retryable)
    }

    @Test
    fun `4999 on the b2c_c2b_result surface decodes terminally too`() {
        val d = DarajaCatalog.decodeError("4999", "Some B2C result", DarajaFamily.B2C_C2B_RESULT)
        assertNotEquals(DarajaCategory.PENDING, d.category)
        assertFalse(d.retryable)
        // The raw description survives into the cause, so the caller keeps the operator's own words.
        assertTrue(d.cause.contains("Some B2C result"))
    }

    @Test
    fun `the STK surface still treats 4999 as pending — family-awareness cuts one way only`() {
        val d = DarajaCatalog.decodeError("4999")
        assertEquals(DarajaCategory.PENDING, d.category)
        assertFalse(d.retryable)
    }

    @Test
    fun `exposed catalog collections are unmodifiable`() {
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (DarajaCatalog.allEntries as MutableList<CatalogEntry>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (DarajaCatalog.pendingResultCodes as MutableSet<String>).clear()
        }
    }
}
