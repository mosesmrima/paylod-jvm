package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneTest {

    @Test
    fun `normalises every accepted Kenyan form to the wire format`() {
        assertEquals("254712345678", Phone.normalize("0712345678"))
        assertEquals("254712345678", Phone.normalize("+254712345678"))
        assertEquals("254712345678", Phone.normalize("254712345678"))
        assertEquals("254712345678", Phone.normalize("712345678"))
        assertEquals("254110123456", Phone.normalize("0110 123 456"))
    }

    @Test
    fun `rejects blank, non-Kenyan and wrong-length numbers`() {
        assertThrows(PaylodInvalidRequestException::class.java) { Phone.normalize("") }
        assertThrows(PaylodInvalidRequestException::class.java) { Phone.normalize("+1 415 555 0100") }
        assertThrows(PaylodInvalidRequestException::class.java) { Phone.normalize("07123") }
        assertThrows(PaylodInvalidRequestException::class.java) { Phone.normalize("0812345678") }
    }

    @Test
    fun `isValidMsisdn does not throw`() {
        assertTrue(Phone.isValidMsisdn("0712345678"))
        assertTrue(Phone.isValidMsisdn("+254712345678"))
        assertFalse(Phone.isValidMsisdn("0812345678"))
        assertFalse(Phone.isValidMsisdn(null))
    }
}
