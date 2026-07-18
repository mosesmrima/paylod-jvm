package dev.paylod

import dev.paylod.internal.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `writes flat objects compactly and in insertion order`() {
        val out = Json.write(linkedMapOf("amount" to 100, "phone" to "254712345678"))
        assertEquals("""{"amount":100,"phone":"254712345678"}""", out)
    }

    @Test
    fun `writes nested maps for metadata`() {
        val out = Json.write(mapOf("metadata" to mapOf("orderId" to "42", "n" to 3)))
        assertEquals("""{"metadata":{"orderId":"42","n":3}}""", out)
    }

    @Test
    fun `escapes strings correctly`() {
        assertEquals(""""a\"b\\c\n"""", Json.write("a\"b\\c\n"))
    }

    @Test
    fun `round-trips a parsed object`() {
        val map = Json.parseObject("""{"id":"pay_1","status":"success","resultCode":0,"receipt":null}""")
        assertEquals("pay_1", map["id"])
        assertEquals("success", map["status"])
        assertEquals(0L, map["resultCode"])
        assertNull(map["receipt"])
    }

    @Test
    fun `parses arrays, booleans and doubles`() {
        val v = Json.parse("""[true,false,1.5,-2,"x"]""") as List<*>
        assertEquals(true, v[0])
        assertEquals(false, v[1])
        assertEquals(1.5, v[2])
        assertEquals(-2L, v[3])
        assertEquals("x", v[4])
    }

    @Test
    fun `parses nested structures`() {
        val v = Json.parseObject("""{"a":{"b":[1,{"c":"d"}]}}""")
        @Suppress("UNCHECKED_CAST")
        val a = v["a"] as Map<String, Any?>
        val b = a["b"] as List<*>
        assertEquals(1L, b[0])
        @Suppress("UNCHECKED_CAST")
        assertEquals("d", (b[1] as Map<String, Any?>)["c"])
    }

    @Test
    fun `handles unicode escapes`() {
        assertEquals("A", Json.parse("\"\\u0041\""))
    }

    @Test
    fun `whole doubles serialise without a trailing point-zero`() {
        assertTrue(Json.write(100.0) == "100")
    }
}
