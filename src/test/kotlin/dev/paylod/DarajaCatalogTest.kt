package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `an unknown code is indeterminate — failed and NOT retryable`() {
        val d = DarajaCatalog.decodeError(4242, "Something odd happened")
        assertEquals("4242", d.code)
        assertEquals("Payment failed", d.title)
        assertEquals("Something odd happened", d.cause)
        assertFalse(d.retryable)
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
}
