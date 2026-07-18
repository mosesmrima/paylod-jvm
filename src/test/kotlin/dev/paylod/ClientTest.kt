package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientTest {

    private val key = "mp_test_abc123"

    // ── construction ──────────────────────────────────────────────────────────────────────

    @Test
    fun `takes a bare API key and bakes in the base URL`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        paylod.collect("0712345678", 1, idempotencyKey = "k")
        assertEquals("https://paylod.dev/functions/v1/collect", t.calls[0].url)
        assertEquals("Bearer $key", t.calls[0].headers["authorization"])
    }

    @Test
    fun `fails loudly when the key is blank`() {
        assertThrows(PaylodConfigException::class.java) {
            testClient(emptyList(), apiKey = "   ")
        }
    }

    @Test
    fun `allows a base URL override`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), baseUrl = "https://api.paylod.dev/v1/")
        paylod.collect("0712345678", 1, idempotencyKey = "k")
        assertEquals("https://api.paylod.dev/v1/collect", t.calls[0].url)
    }

    // ── collect ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `posts a normalised body and returns the 202 ack`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        val ack = paylod.collect("0712345678", 100, idempotencyKey = "k")

        assertEquals("pay_123", ack.paymentId)
        assertEquals(PaymentStatus.PENDING, ack.status)
        assertEquals("ws_CO_0001", ack.checkoutRequestId)

        val call = t.calls[0]
        assertEquals("POST", call.method)
        assertEquals(mapOf("amount" to 100L, "phone" to "254712345678"), call.body)
    }

    @Test
    fun `rejects bad input locally, before any network call`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 0) }
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 150_001) }
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0812345678", 10) }
        assertEquals(0, t.count)
    }

    @Test
    fun `surfaces an API error with its status and message`() {
        val (paylod, _) = testClient(listOf(Step(status = 401, json = mapOf("error" to "invalid API key"))))
        val err = assertThrows(PaylodApiException::class.java) { paylod.collect("0712345678", 10, idempotencyKey = "k") }
        assertEquals(401, err.status)
        assertTrue(err.isAuthError)
        assertEquals("invalid API key", err.message)
    }

    // ── idempotency ───────────────────────────────────────────────────────────────────────

    @Test
    fun `generates an Idempotency-Key by default and returns it on the ack`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        val ack = paylod.collect(CollectParams("0712345678", 100))
        val sent = t.calls[0].headers["idempotency-key"]
        assertEquals(ack.idempotencyKey, sent)
        assertTrue(sent!!.matches(Regex("^[0-9a-f-]{36}$")))
    }

    @Test
    fun `uses a caller-supplied key verbatim and keeps it out of the body`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        paylod.collect("0712345678", 100, idempotencyKey = "order-42")
        assertEquals("order-42", t.calls[0].headers["idempotency-key"])
        assertEquals(mapOf("amount" to 100L, "phone" to "254712345678"), t.calls[0].body)
    }

    @Test
    fun `generates a different key per call`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK), Step(status = 202, json = ACK)))
        paylod.collect(CollectParams("0712345678", 100))
        paylod.collect(CollectParams("0712345678", 100))
        assertNotEquals(t.calls[0].headers["idempotency-key"], t.calls[1].headers["idempotency-key"])
    }

    @Test
    fun `replays the same key on a transient retry, so a retry cannot double-charge`() {
        val (paylod, t) = testClient(
            listOf(Step(status = 503, json = mapOf("error" to "upstream")), Step(status = 202, json = ACK)),
            maxRetries = 2,
        )
        val ack = paylod.collect("0712345678", 100, idempotencyKey = "k1")
        assertEquals(2, t.calls.size)
        assertEquals(t.calls[0].headers["idempotency-key"], t.calls[1].headers["idempotency-key"])
        assertEquals("pay_123", ack.paymentId)
    }

    @Test
    fun `does NOT retry a 409 idempotency conflict`() {
        val (paylod, t) = testClient(
            listOf(Step(status = 409, json = mapOf("error" to "Idempotency-Key was reused with a different request body"))),
            maxRetries = 3,
        )
        val err = assertThrows(PaylodApiException::class.java) {
            paylod.collect("0712345678", 100, idempotencyKey = "order-42")
        }
        assertTrue(err.isIdempotencyConflict)
        assertTrue(err.isIdempotencyBodyConflict)
        assertEquals("order-42", err.idempotencyKey)
        assertEquals(1, t.count)
    }

    // ── status ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `GETs status by id`() {
        val (paylod, t) = testClient(
            listOf(Step(json = paymentJson(status = "success", mpesaReceipt = "SFF6XYZ123", resultCode = 0))),
        )
        val p = paylod.status("pay_123")
        assertEquals("https://paylod.dev/functions/v1/status/pay_123", t.calls[0].url)
        assertEquals("GET", t.calls[0].method)
        assertEquals(PaymentStatus.SUCCESS, p.status)
        assertEquals("SFF6XYZ123", p.mpesaReceipt)
    }

    // ── collectAndWait ────────────────────────────────────────────────────────────────────

    @Test
    fun `happy path polls past pending and returns succeeded with the receipt`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
                Step(json = paymentJson(status = "pending")),
                Step(json = paymentJson(status = "success", mpesaReceipt = "SFF6XYZ123", resultCode = 0)),
            ),
        )
        val polls = mutableListOf<Payment>()
        val outcome = paylod.collectAndWait(
            CollectParams("0712345678", 100),
            WaitOptions.of(onPoll = { polls.add(it) }),
        )
        assertEquals(OutcomeStatus.SUCCEEDED, outcome.status)
        assertTrue(outcome.paid)
        assertEquals("SFF6XYZ123", outcome.receipt)
        assertFalse(outcome.retryable)
        assertEquals(2, polls.size)
    }

    @Test
    fun `wrong PIN (2001) is a renderable failure with a safe retry`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "failed", resultCode = 2001, resultDesc = "The initiator information is invalid.")),
            ),
        )
        val r = paylod.collectAndWait(CollectParams("0712345678", 100))
        assertEquals(OutcomeStatus.FAILED, r.status)
        assertFalse(r.paid)
        assertEquals("That M-Pesa PIN was incorrect. Please try again and enter the right PIN.", r.message)
        assertTrue(r.retryable)
        assertEquals("2001", r.code)
        assertEquals("Wrong M-Pesa PIN", r.detail?.title)
    }

    @Test
    fun `cancelled (1032) gets its own status and does not throw`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "failed", resultCode = 1032, resultDesc = "Request cancelled by user")),
            ),
        )
        val r = paylod.collectAndWait(CollectParams("0712345678", 100))
        assertEquals(OutcomeStatus.CANCELLED, r.status)
        assertTrue(r.retryable)
        assertEquals("Payment cancelled — you can try again whenever you're ready.", r.message)
    }

    // THE regression that shipped twice: a pending code masquerading as status:failed.
    @Test
    fun `4999 masquerading as failed keeps polling, then settles on the real outcome`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "failed", resultCode = 4999, resultDesc = "The transaction is still under processing")),
                Step(json = paymentJson(status = "success", resultCode = 0, mpesaReceipt = "SFF6XYZ123")),
            ),
        )
        val r = paylod.collectAndWait(CollectParams("0712345678", 100))
        assertEquals(OutcomeStatus.SUCCEEDED, r.status)
        assertEquals("SFF6XYZ123", r.receipt)
    }

    @Test
    fun `check reports a pending-coded failed row as pending and never retryable`() {
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "failed", resultCode = "500.001.1001", resultDesc = "The transaction is being processed"))),
        )
        val r = paylod.check("pay_123")
        assertEquals(OutcomeStatus.PENDING, r.status)
        assertFalse(r.retryable)
        assertEquals("Check your phone and enter your M-Pesa PIN to complete this payment.", r.message)
    }

    @Test
    fun `timeout throws PaylodTimeoutException carrying the still-pending payment`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
            ),
        )
        val err = assertThrows(PaylodTimeoutException::class.java) {
            paylod.collectAndWait(CollectParams("0712345678", 100), WaitOptions.of(timeoutMs = 8_000))
        }
        assertEquals("pay_123", err.paymentId)
        assertEquals(PaymentStatus.PENDING, err.payment.status)
        assertTrue(err.message!!.contains("NOT failed"))
    }

    @Test
    fun `keeps polling while pending and stops on the first terminal state`() {
        val (paylod, t) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
                Step(json = paymentJson(status = "success", mpesaReceipt = "R1", resultCode = 0)),
                Step(json = paymentJson(status = "success", mpesaReceipt = "R1", resultCode = 0)),
            ),
        )
        paylod.collectAndWait(CollectParams("0712345678", 1))
        assertEquals(3, t.count) // collect + 2 status reads, no more
    }

    // ── retries ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `retries a network failure and then succeeds`() {
        val (paylod, t) = testClient(
            listOf(
                Step(throwable = PaylodConnectionException("socket reset")),
                Step(status = 202, json = ACK),
            ),
            maxRetries = 2,
        )
        val ack = paylod.collect("0712345678", 1, idempotencyKey = "k")
        assertEquals("pay_123", ack.paymentId)
        assertEquals(2, t.count)
    }

    @Test
    fun `does not retry a 422 validation error from the server`() {
        val (paylod, t) = testClient(
            listOf(Step(status = 422, json = mapOf("error" to "invalid Kenyan phone number"))),
            maxRetries = 3,
        )
        assertThrows(PaylodApiException::class.java) { paylod.collect("0712345678", 1, idempotencyKey = "k") }
        assertEquals(1, t.count)
    }

    // ── decodeError ───────────────────────────────────────────────────────────────────────

    @Test
    fun `decodeError works offline with no network`() {
        val (paylod, _) = testClient(emptyList())
        val e = paylod.decodeError(1032)
        assertEquals("1032", e.code)
        assertEquals(DarajaCategory.CUSTOMER, e.category)
        assertTrue(e.retryable)
    }

    @Test
    fun `rejects an empty paymentId on status`() {
        val (paylod, _) = testClient(emptyList())
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.status("") }
    }

    @Test
    fun `rejects over-long accountReference and description before the network`() {
        val (paylod, t) = testClient(emptyList())
        assertThrows(PaylodInvalidRequestException::class.java) {
            paylod.collect("0712345678", 1, accountReference = "x".repeat(13))
        }
        assertThrows(PaylodInvalidRequestException::class.java) {
            paylod.collect("0712345678", 1, description = "x".repeat(65))
        }
        assertEquals(0, t.count)
    }

    // ── 0.2.0: accountReference/description trimmed on the wire ─────────────────────────────

    @Test
    fun `transmits the trimmed accountReference and description, and rejects a blank one`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)))
        paylod.collect(
            CollectParams.builder("0712345678", 100)
                .accountReference("  INV-9  ")
                .description("  Coffee  ")
                .idempotencyKey("k")
                .build(),
        )
        assertEquals("INV-9", t.calls[0].body!!["accountReference"])
        assertEquals("Coffee", t.calls[0].body!!["description"])

        val (paylod2, t2) = testClient(emptyList())
        assertThrows(PaylodInvalidRequestException::class.java) {
            paylod2.collect("0712345678", 1, accountReference = "   ")
        }
        assertEquals(0, t2.count)
    }

    // ── 0.2.0: secrets never printed by HttpRequestSpec.toString() ─────────────────────────

    @Test
    fun `HttpRequestSpec toString redacts the bearer token and idempotency key`() {
        val spec = HttpRequestSpec(
            "POST",
            "https://paylod.dev/functions/v1/collect",
            linkedMapOf("authorization" to "Bearer mp_live_supersecret", "idempotency-key" to "attempt-1"),
            "{}",
            1_000,
        )
        val s = spec.toString()
        assertFalse(s.contains("mp_live_supersecret"), "bearer token leaked: $s")
        assertFalse(s.contains("attempt-1"), "idempotency key leaked: $s")
        assertTrue(s.contains("[redacted]"))
    }

    // ── 0.2.0: HTTPS enforcement on baseUrl ────────────────────────────────────────────────

    @Test
    fun `refuses a plaintext http baseUrl`() {
        assertThrows(PaylodConfigException::class.java) {
            testClient(emptyList(), baseUrl = "http://paylod.dev/v1")
        }
    }

    @Test
    fun `allows loopback http only behind the explicit test flag`() {
        // Without the flag, even loopback http is refused.
        assertThrows(PaylodConfigException::class.java) {
            testClient(emptyList(), baseUrl = "http://localhost:54321/v1")
        }
        // With the flag and a test key, it is allowed.
        testClient(emptyList(), baseUrl = "http://localhost:54321/v1", allowInsecureBaseUrl = true)
    }

    @Test
    fun `never allows plaintext with a live key, even loopback with the flag`() {
        assertThrows(PaylodConfigException::class.java) {
            testClient(
                emptyList(),
                apiKey = "mp_live_abc",
                baseUrl = "http://localhost:54321/v1",
                allowInsecureBaseUrl = true,
            )
        }
    }

    // ── 0.2.0: idempotency-key validation ──────────────────────────────────────────────────

    @Test
    fun `rejects a blank, whitespace, or control-char idempotency key`() {
        val (paylod, t) = testClient(emptyList())
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 1, idempotencyKey = "") }
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 1, idempotencyKey = "   ") }
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 1, idempotencyKey = "a\tb") }
        assertThrows(PaylodInvalidRequestException::class.java) { paylod.collect("0712345678", 1, idempotencyKey = "x".repeat(256)) }
        assertEquals(0, t.count)
    }

    // ── 0.2.0: effective key is never lost on failure ──────────────────────────────────────

    @Test
    fun `attaches the effective idempotency key to a thrown connection error`() {
        val (paylod, _) = testClient(
            listOf(Step(throwable = PaylodConnectionException("socket reset"))),
            maxRetries = 0,
        )
        val err = assertThrows(PaylodConnectionException::class.java) {
            paylod.collect("0712345678", 100, idempotencyKey = "attempt-77")
        }
        assertEquals("attempt-77", err.idempotencyKey)
    }

    @Test
    fun `attaches a GENERATED key to a thrown error so a retry cannot mint a fresh one`() {
        val (paylod, _) = testClient(
            listOf(Step(throwable = PaylodConnectionException("socket reset"))),
            maxRetries = 0,
        )
        val err = assertThrows(PaylodConnectionException::class.java) {
            paylod.collect(CollectParams("0712345678", 100)) // no key -> SDK generates one
        }
        assertTrue(err.idempotencyKey!!.matches(Regex("^[0-9a-f-]{36}$")))
    }

    // ── 0.2.0: malformed 2xx is indeterminate, never a silent empty success ────────────────

    @Test
    fun `a 2xx collect with no paymentId is an indeterminate error carrying the key`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 202, json = mapOf("status" to "pending"))), // no paymentId
        )
        val err = assertThrows(PaylodApiException::class.java) {
            paylod.collect("0712345678", 100, idempotencyKey = "k-indef")
        }
        assertTrue(err.indeterminate)
        assertEquals("k-indef", err.idempotencyKey)
    }

    @Test
    fun `a 2xx status body with no id is a malformed-response error`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 200, json = mapOf("status" to "pending"))), // no id
        )
        assertThrows(PaylodApiException::class.java) { paylod.status("pay_123") }
    }

    // ── 0.2.0: in-progress 409 is retried; body-conflict 409 is not ────────────────────────

    @Test
    fun `retries ONLY an explicit in-progress 409, honouring the same key`() {
        val (paylod, t) = testClient(
            listOf(
                Step(status = 409, json = mapOf("error" to "an idempotent request is already in progress")),
                Step(status = 202, json = ACK),
            ),
            maxRetries = 2,
        )
        val ack = paylod.collect("0712345678", 100, idempotencyKey = "k-prog")
        assertEquals("pay_123", ack.paymentId)
        assertEquals(2, t.count)
        assertEquals(t.calls[0].headers["idempotency-key"], t.calls[1].headers["idempotency-key"])
    }

    // ── 0.2.0: raw status can no longer override the classifier ─────────────────────────────

    @Test
    fun `status success carrying a pending code (4999) is NOT reported as paid`() {
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "success", resultCode = 4999, resultDesc = "still under processing"))),
        )
        val r = paylod.check("pay_123")
        assertEquals(OutcomeStatus.PENDING, r.status)
        assertFalse(r.paid)
        assertFalse(r.retryable)
    }

    @Test
    fun `status success carrying a failure code (1032) is a contradiction — indeterminate, not paid`() {
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "success", resultCode = 1032, resultDesc = "cancelled by user"))),
        )
        val r = paylod.check("pay_123")
        assertEquals(OutcomeStatus.PENDING, r.status) // surfaced as pending so wait() lets it settle
        assertFalse(r.paid)
        assertFalse(r.retryable)
        assertEquals(
            "We couldn't confirm this payment yet. Please wait — do not retry — while it settles.",
            r.message,
        )
    }

    @Test
    fun `status failed carrying a success code (0) is a contradiction — indeterminate, not failed`() {
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "failed", resultCode = 0, resultDesc = "ok"))),
        )
        val r = paylod.check("pay_123")
        assertEquals(OutcomeStatus.PENDING, r.status)
        assertFalse(r.paid)
        assertFalse(r.retryable)
    }

    // ── 0.2.0: wait propagates its deadline into each poll's request timeout ────────────────

    @Test
    fun `caps a poll's per-request timeout to the wait deadline`() {
        val (paylod, t) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
            ),
        )
        assertThrows(PaylodTimeoutException::class.java) {
            paylod.collectAndWait(CollectParams("0712345678", 100), WaitOptions.of(timeoutMs = 8_000))
        }
        // The first status read (a GET) is capped to the 8s wait budget, not the 30s per-request default.
        val firstStatus = t.calls.first { it.method == "GET" }
        assertTrue(firstStatus.timeoutMs <= 8_000, "expected <= 8000, was ${firstStatus.timeoutMs}")
    }
}
