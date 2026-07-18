package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.RealTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient
import java.time.Duration
import java.util.Random

/**
 * Round-3 regressions. Every test here pins a hole a codex review found in 0.3.0; each one is
 * written so that reverting its fix makes it fail.
 */
class ThirdRoundTest {

    // ── CRITICAL: a status body is validated as a COMPLETE, state-dependent schema ───────────

    @Test
    fun `a success status with NO receipt and NO result code is never reported as paid`() {
        // THE false-paid bug. `{"id":"pay_123","status":"success"}` validated on `id` alone, and
        // `Outcomes.of` then trusted the status STRING: paid = true, receipt = null, code = null.
        val (paylod, _) = testClient(
            listOf(Step(json = mapOf("id" to "pay_123", "status" to "success"))),
        )
        // The rule now lives in the SEMANTIC MODEL (law L2) rather than in the shape validator: the
        // body is well-formed, so it is accepted and then JUDGED. A claim with nothing behind it is
        // INDETERMINATE — rendered as PENDING so `wait()` keeps polling and a webhook can settle it,
        // never as a paid payment a caller would fulfil.
        val outcome = paylod.check("pay_123")
        assertFalse(outcome.paid, "a bare status:success with no evidence must never be paid")
        assertFalse(outcome.retryable, "an indeterminate payment is never safe to charge again")
        assertEquals(OutcomeStatus.PENDING, outcome.status)
        assertEquals(
            PaymentVerdict.INDETERMINATE,
            PaymentSemantics.judge(outcome.payment).verdict,
        )
    }

    @Test
    fun `a success status is accepted on either piece of evidence alone`() {
        // A receipt alone is proof enough...
        val (a, _) = testClient(
            listOf(Step(json = mapOf("id" to "pay_1", "status" to "success", "mpesaReceipt" to "SFF6XYZ123"))),
        )
        val byReceipt = a.check("pay_1")
        assertTrue(byReceipt.paid)
        assertEquals("SFF6XYZ123", byReceipt.receipt)

        // ...and so is a result code, which the classifier can adjudicate.
        val (b, _) = testClient(
            listOf(Step(json = mapOf("id" to "pay_2", "status" to "success", "resultCode" to 0))),
        )
        assertTrue(b.check("pay_2").paid)
    }

    @Test
    fun `every malformed 2xx status shape is an indeterminate error, not a silent Payment`() {
        val malformed = mapOf(
            "status missing" to mapOf("id" to "pay_1"),
            "status not a string" to mapOf("id" to "pay_1", "status" to 1),
            "status unknown" to mapOf("id" to "pay_1", "status" to "paid"),
            "receipt not a string" to mapOf("id" to "pay_1", "status" to "success", "mpesaReceipt" to 42),
            "receipt blank" to mapOf("id" to "pay_1", "status" to "success", "mpesaReceipt" to "   "),
            "resultCode a boolean" to mapOf("id" to "pay_1", "status" to "failed", "resultCode" to true),
            "resultCode an object" to
                mapOf("id" to "pay_1", "status" to "failed", "resultCode" to mapOf("a" to 1)),
            "resultCode blank" to mapOf("id" to "pay_1", "status" to "failed", "resultCode" to ""),
            "resultDesc not a string" to mapOf("id" to "pay_1", "status" to "pending", "resultDesc" to 7),
            "id blank" to mapOf("id" to "  ", "status" to "pending"),
        )
        for ((name, body) in malformed) {
            val (paylod, _) = testClient(listOf(Step(json = body)))
            val err = assertThrows<PaylodApiException>("accepted a body with $name") {
                paylod.status("pay_1")
            }
            assertTrue(err.indeterminate, "$name was not flagged indeterminate")
        }
    }

    @Test
    fun `a 2xx status body that is not JSON at all is indeterminate`() {
        // Reaches the validator as an empty map, because the raw text is not a JSON object.
        val (paylod, _) = testClient(listOf(Step(raw = "<html>502 Bad Gateway</html>")))
        val err = assertThrows<PaylodApiException> { paylod.status("pay_1") }
        assertTrue(err.indeterminate)
    }

    // ── HIGH: collectAndWait keeps the acknowledged idempotency key on every failure ─────────

    @Test
    fun `collectAndWait attaches the acknowledged key and payment id when the wait TIMES OUT`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 202, json = ACK), Step(json = paymentJson(status = "pending"))),
        )
        val err = assertThrows<PaylodTimeoutException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-live"),
                WaitOptions.of(timeoutMs = 3_000),
            )
        }
        // Without this, a caller retrying "with the key from the exception" finds null, mints a fresh
        // key, and fires a SECOND STK prompt at a customer whose first prompt is still live.
        assertEquals("k-live", err.idempotencyKey)
        assertEquals("pay_123", err.paymentId)
    }

    @Test
    fun `collectAndWait attaches the key when the POLL fails at the transport`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(throwable = PaylodConnectionException("socket reset")),
            ),
        )
        val err = assertThrows<PaylodConnectionException> {
            paylod.collectAndWait(CollectParams("0712345678", 100, idempotencyKey = "k-net"))
        }
        assertEquals("k-net", err.idempotencyKey)
        assertEquals("pay_123", err.paymentId)
    }

    @Test
    fun `collectAndWait attaches the key when the POLL returns an HTTP error`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 202, json = ACK), Step(status = 500, json = mapOf("error" to "boom"))),
        )
        val err = assertThrows<PaylodApiException> {
            paylod.collectAndWait(CollectParams("0712345678", 100, idempotencyKey = "k-500"))
        }
        assertEquals("k-500", err.idempotencyKey)
        assertEquals("pay_123", err.paymentId)
    }

    @Test
    fun `collectAndWait attaches the key when the POLL body is malformed`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                // A body describing a DIFFERENT payment — law L1. It is not merely malformed, it is
                // an answer to another question, and it must reach the caller with the handles to
                // the charge that is live right now.
                Step(json = mapOf("id" to "pay_SOMEONE_ELSE", "status" to "success", "resultCode" to "0")),
            ),
        )
        val err = assertThrows<PaylodApiException> {
            paylod.collectAndWait(CollectParams("0712345678", 100, idempotencyKey = "k-mal"))
        }
        assertTrue(err.indeterminate)
        assertEquals("k-mal", err.idempotencyKey)
        assertEquals("pay_123", err.paymentId)
    }

    @Test
    fun `collectAndWait attaches the GENERATED key too, so a retry cannot mint a fresh one`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 202, json = ACK), Step(json = paymentJson(status = "pending"))),
        )
        val err = assertThrows<PaylodTimeoutException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, unsafeGeneratedIdempotencyKey = true),
                WaitOptions.of(timeoutMs = 2_000),
            )
        }
        assertNotNull(err.idempotencyKey)
        assertTrue(err.idempotencyKey!!.matches(Regex("^[0-9a-f-]{36}$")))
    }

    // ── HIGH: the WHOLE collect acknowledgement is validated ─────────────────────────────────

    @Test
    fun `every malformed 2xx collect ack is an indeterminate error carrying the key`() {
        val malformed = mapOf(
            "no checkoutRequestId" to mapOf("paymentId" to "pay_1", "status" to "pending"),
            "blank checkoutRequestId" to
                mapOf("paymentId" to "pay_1", "status" to "pending", "checkoutRequestId" to "  "),
            "checkoutRequestId not a string" to
                mapOf("paymentId" to "pay_1", "status" to "pending", "checkoutRequestId" to 99),
            "no status" to mapOf("paymentId" to "pay_1", "checkoutRequestId" to "ws_1"),
            "status not a string" to
                mapOf("paymentId" to "pay_1", "status" to 1, "checkoutRequestId" to "ws_1"),
            "status unknown" to
                mapOf("paymentId" to "pay_1", "status" to "queued", "checkoutRequestId" to "ws_1"),
            // `POST /collect` hardcodes `status: "pending"`, and an idempotent replay returns the
            // STORED original ack — never the settled state. A terminal status here is malformed.
            "status success" to
                mapOf("paymentId" to "pay_1", "status" to "success", "checkoutRequestId" to "ws_1"),
            "status failed" to
                mapOf("paymentId" to "pay_1", "status" to "failed", "checkoutRequestId" to "ws_1"),
            "paymentId not a string" to
                mapOf("paymentId" to 5, "status" to "pending", "checkoutRequestId" to "ws_1"),
        )
        for ((name, body) in malformed) {
            val (paylod, _) = testClient(listOf(Step(status = 202, json = body)))
            val err = assertThrows<PaylodApiException>("accepted an ack with $name") {
                paylod.collect("0712345678", 100, idempotencyKey = "k-ack")
            }
            assertTrue(err.indeterminate, "$name was not flagged indeterminate")
            // The key must survive: the charge behind an unreadable ack may well be live.
            assertEquals("k-ack", err.idempotencyKey, "$name lost the idempotency key")
        }
    }

    // ── HIGH: interrupts never escape raw from a sleep ───────────────────────────────────────

    @Test
    fun `an interrupted sleep restores the flag and surfaces as PaylodInterruptedException`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows<PaylodInterruptedException> { RealTimeSource.sleep(50) }
            // `interrupted()` both reads AND clears, so this asserts the flag was restored.
            assertTrue(Thread.interrupted(), "the interrupt flag was swallowed")
        } finally {
            Thread.interrupted() // never leak a set flag into the next test
        }
    }

    @Test
    fun `an interrupt during a retry backoff still arrives with the idempotency key attached`() {
        // A real sleeper, so the interrupt lands inside RealTimeSource.sleep between attempts. The
        // request has already DISPATCHED by then — losing the key here loses the double-charge guard.
        val transport = HttpTransport { _ ->
            Thread.currentThread().interrupt()
            HttpResponseSpec(503, emptyMap(), Json.write(mapOf("error" to "upstream")))
        }
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(maxRetries = 2, transport = transport, allowCustomTransport = true),
            RealTimeSource,
            Random(1),
        )
        try {
            val err = assertThrows<PaylodInterruptedException> {
                paylod.collect("0712345678", 100, idempotencyKey = "k-int")
            }
            assertEquals("k-int", err.idempotencyKey)
            assertTrue(Thread.interrupted(), "the interrupt flag was swallowed")
        } finally {
            Thread.interrupted()
        }
    }

    // ── HIGH: the API key never reaches a JDK header error, and headers never print ──────────

    @Test
    fun `an API key carrying a control character is refused at CONSTRUCTION, without echoing it`() {
        val secret = "mp_test_supersecret\nvalue"
        val err = assertThrows<PaylodConfigException> {
            testClient(emptyList(), apiKey = secret)
        }
        assertFalse(err.message!!.contains("supersecret"), "the API key leaked: ${err.message}")
        assertTrue(err.message!!.contains("U+000A"), "message should name the code point: ${err.message}")
    }

    @Test
    fun `the raw headers map redacts secrets when PRINTED, while lookups still work`() {
        val headers = RedactingHeaders(
            linkedMapOf(
                "authorization" to "Bearer mp_live_supersecret",
                "idempotency-key" to "attempt-1",
                "accept" to "application/json",
            ),
        )
        // HttpRequestSpec.toString() was already safe; `spec.headers.toString()` was not.
        val printed = headers.toString()
        assertFalse(printed.contains("mp_live_supersecret"), "bearer token leaked: $printed")
        assertFalse(printed.contains("attempt-1"), "idempotency key leaked: $printed")
        assertTrue(printed.contains("application/json"), "a non-secret header should still print")
        // The transport must still be able to READ the real value.
        assertEquals("Bearer mp_live_supersecret", headers["authorization"])
    }

    @Test
    fun `the client hands the transport a header map that cannot print the bearer token`() {
        var printed = ""
        val transport = HttpTransport { req ->
            printed = req.headers.toString()
            HttpResponseSpec(202, emptyMap(), Json.write(ACK))
        }
        val paylod = Paylod("mp_test_supersecret", PaylodOptions.of(transport = transport, allowCustomTransport = true))
        paylod.collect("0712345678", 100, idempotencyKey = "k1")
        assertFalse(printed.contains("mp_test_supersecret"), "bearer token leaked: $printed")
        assertFalse(printed.contains("k1"), "idempotency key leaked: $printed")
    }

    // ── HIGH: the no-redirect guarantee is not bypassable ────────────────────────────────────
    //
    // `JdkHttpTransport` no longer exists as a public class. The guarantees it used to carry —
    // Redirect.NEVER, and a header-assembly guard that never echoes a secret-bearing value — moved
    // INSIDE the SDK-owned `Transport`, where a caller cannot reach them to replace them. The tests
    // below pin the guarantees rather than the vanished class.

    @Test
    fun `the SDK-owned dispatch never follows a redirect`() {
        // There is no constructor, factory or option that yields a credentialed dispatch on a
        // redirect-following client: the only HttpClient the SDK ever builds is built here, once,
        // with Redirect.NEVER. Reverting that line is caught by the mutation harness.
        val source = java.io.File("src/main/kotlin/dev/paylod/Transport.kt").readText()
        assertTrue(
            source.contains(".followRedirects(HttpClient.Redirect.NEVER)"),
            "the SDK-owned dispatch must pin Redirect.NEVER",
        )
        // There is exactly ONE `followRedirects` call in the whole SDK, so there is no second,
        // laxer client for a caller (or a future edit) to reach.
        assertEquals(
            1,
            Regex("\\.followRedirects\\(").findAll(source).count(),
            "more than one redirect policy is configured",
        )
    }

    @Test
    fun `a header the JDK refuses is reported WITHOUT its value`() {
        // The API key is validated at construction, so the only way to reach the transport's header
        // guard is an idempotency key the JDK rejects — and its value must not be echoed either.
        val transport = Transport(
            apiKey = "mp_test_abc123",
            baseUrl = "https://paylod.dev/functions/v1",
            custom = null,
            redact = dev.paylod.internal.Redactor(listOf("mp_test_abc123")),
        )
        val err = assertThrows<PaylodConfigException> {
            transport.send(
                TransportRequest(
                    method = "POST",
                    path = "/collect",
                    body = "{}",
                    idempotencyKey = "bad\u0000value-supersecret",
                    timeoutMs = 1_000,
                ),
            )
        }
        assertFalse(err.message!!.contains("supersecret"), "the header value leaked: ${err.message}")
        assertTrue(err.message!!.contains("idempotency-key"), "should name the header: ${err.message}")
    }

    @Test
    fun `a redirect from ANY transport is refused, never followed`() {
        // The SDK cannot stop an injected transport from following a 3xx itself, but it must never
        // treat one as a response to chase — and must not retry it either.
        val transport = StubTransport(
            listOf(Step(status = 302, headers = mapOf("location" to "https://attacker.example/collect"))),
        )
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(maxRetries = 3, transport = transport, allowCustomTransport = true),
        )
        val err = assertThrows<PaylodApiException> {
            paylod.collect("0712345678", 100, idempotencyKey = "k-302")
        }
        assertEquals(302, err.status)
        assertTrue(err.indeterminate)
        assertEquals("k-302", err.idempotencyKey)
        assertEquals(1, transport.count, "a redirect must not be retried")
    }

    // ── MEDIUM: the JSON reader is RFC-strict ────────────────────────────────────────────────

    @Test
    fun `the parser rejects unescaped control characters inside a string`() {
        val bad = listOf(
            "{\"a\":\"x y\"}",
            "{\"a\":\"x\ny\"}",
            "{\"a\":\"x\ty\"}",
            "{\"a\":\"xy\"}",
        )
        for (text in bad) {
            assertThrows<Json.JsonParseException>("accepted ${text.replace("\n", "\\n")}") {
                Json.parse(text)
            }
        }
        // The ESCAPED forms remain valid, and still decode to the real characters.
        assertEquals(mapOf("a" to "x\ny"), Json.parse("""{"a":"x\ny"}"""))
    }

    @Test
    fun `the parser rejects malformed numbers`() {
        val bad = listOf(
            "-", "1.", ".5", "1e", "1e+", "1E-", "007", "-007", "00", "01.5", "1.2.3", "--1",
        )
        for (text in bad) {
            assertThrows<Json.JsonParseException>("accepted the number \"$text\"") { Json.parse(text) }
        }
        // The legal shapes still parse.
        assertEquals(0L, Json.parse("0"))
        assertEquals(-1L, Json.parse("-1"))
        assertEquals(1.5, Json.parse("1.5"))
        assertEquals(1000.0, Json.parse("1e3"))
        assertEquals(0.5, Json.parse("0.5"))
    }

    @Test
    fun `the parser rejects a malformed unicode escape`() {
        assertThrows<Json.JsonParseException> { Json.parse("""{"a":"\u12g4"}""") }
        assertThrows<Json.JsonParseException> { Json.parse("""{"a":"\u 123"}""") }
        assertEquals(mapOf("a" to "ሴ"), Json.parse("""{"a":"ሴ"}"""))
    }

    @Test
    fun `a 2xx whose required fields are fine but whose JSON is invalid does NOT validate`() {
        // The whole point of strictness: the required fields below are present and well-formed. Only
        // the trailing member is bad JSON. A lenient parser accepted the document, the validator saw
        // its required fields, and a payment was reported as settled off a body no conforming parser
        // would have read.
        val raw = "{\"id\":\"pay_1\",\"status\":\"success\",\"mpesaReceipt\":\"SFF6XYZ123\"," +
            "\"note\":\"line1\nline2\"}"
        val (paylod, _) = testClient(listOf(Step(raw = raw)))
        val err = assertThrows<PaylodApiException> { paylod.status("pay_1") }
        assertTrue(err.indeterminate)

        // Same body with the control character properly escaped parses, and settles normally.
        val (ok, _) = testClient(listOf(Step(raw = raw.replace("line1\nline2", "line1\\nline2"))))
        assertTrue(ok.check("pay_1").paid)
    }

    // ── MEDIUM: deadlines are monotonic ──────────────────────────────────────────────────────

    @Test
    fun `a backward wall-clock jump cannot extend a wait past its deadline`() {
        val transport = StubTransport(
            listOf(Step(status = 202, json = ACK), Step(json = paymentJson(status = "pending"))),
        )
        val clock = FakeTimeSource(now = 1_700_000_000_000L, mono = 0)
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(transport = transport, allowCustomTransport = true),
            clock,
            Random(1),
        )
        // Every poll drags the wall clock an hour BACKWARDS — an NTP correction, a VM resume, or a
        // sysadmin. On a wall-clock deadline, `remaining` GROWS on each poll and the wait never ends
        // at all, so the poll count is capped here: without the monotonic deadline this test would
        // otherwise spin until the JVM ran out of heap instead of failing an assertion.
        var polls = 0
        val onPoll = PollListener {
            clock.now -= 3_600_000L
            if (++polls > 10) {
                throw AssertionError(
                    "the wait was still polling after $polls polls — a backward wall-clock jump " +
                        "extended a 3000ms deadline indefinitely",
                )
            }
        }

        val startMono = clock.mono
        assertThrows<PaylodTimeoutException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-clock"),
                WaitOptions.of(timeoutMs = 3_000, onPoll = onPoll),
            )
        }
        val elapsed = clock.mono - startMono
        assertTrue(elapsed <= 3_000, "the wait ran ${elapsed}ms past a 3000ms budget")
    }

    @Test
    fun `elapsed time reported by a timeout is measured monotonically`() {
        val transport = StubTransport(
            listOf(Step(status = 202, json = ACK), Step(json = paymentJson(status = "pending"))),
        )
        val clock = FakeTimeSource(now = 1_700_000_000_000L, mono = 0)
        val paylod = Paylod("mp_test_abc123", PaylodOptions.of(transport = transport, allowCustomTransport = true), clock, Random(1))
        val onPoll = PollListener { clock.now += 86_400_000L } // wall clock leaps a day forward

        val err = assertThrows<PaylodTimeoutException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-clock2"),
                WaitOptions.of(timeoutMs = 3_000, onPoll = onPoll),
            )
        }
        // A wall-clock measurement would report "waited a day" in a call the caller capped at 3s.
        assertTrue(err.waitedMs <= 3_000, "reported waitedMs=${err.waitedMs} for a 3000ms budget")
        assertNull(err.payment.mpesaReceipt)
    }
}
