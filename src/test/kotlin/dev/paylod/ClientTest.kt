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

    // ── 0.3.0: baseUrl is an ORIGIN ALLOWLIST, not just an https:// check ───────────────────

    @Test
    fun `refuses an arbitrary https origin — a live key must never leave for a foreign host`() {
        // This is the whole point: HTTPS to attacker.example is still a full credential handover.
        for (bad in listOf(
            "https://attacker.example/v1",
            "https://paylod.dev.attacker.example/v1",
            "https://evil-paylod.dev/v1",
        )) {
            assertThrows(PaylodConfigException::class.java, { testClient(emptyList(), baseUrl = bad) }, bad)
        }
    }

    @Test
    fun `accepts exactly the two allowed paylod hosts`() {
        testClient(emptyList(), baseUrl = "https://paylod.dev/functions/v1")
        testClient(emptyList(), baseUrl = "https://api.paylod.dev/v1")
        testClient(emptyList(), baseUrl = "https://paylod.dev:443/functions/v1")
        // …and even a live key is fine, because these are the origins it is FOR.
        testClient(emptyList(), apiKey = "mp_live_abc", baseUrl = "https://paylod.dev/functions/v1")
    }

    @Test
    fun `the allowlist is an exact host set, not a suffix match`() {
        // A suffix match on ".paylod.dev" would wave through any subdomain, including one an attacker
        // can get pointed elsewhere. Only the two named hosts are allowed.
        for (bad in listOf("https://staging.paylod.dev/v1", "https://a.api.paylod.dev/v1")) {
            assertThrows(PaylodConfigException::class.java, { testClient(emptyList(), baseUrl = bad) }, bad)
        }
    }

    @Test
    fun `https loopback needs the same opt-in as plaintext, and never takes a live key`() {
        // An https:// dev server must NOT slip through on the strength of its scheme alone.
        assertThrows(PaylodConfigException::class.java) {
            testClient(emptyList(), baseUrl = "https://localhost:54321/v1")
        }
        assertThrows(PaylodConfigException::class.java) {
            testClient(emptyList(), baseUrl = "https://127.0.0.1:54321/v1")
        }
        // With the opt-in and a sandbox key it is allowed…
        testClient(emptyList(), baseUrl = "https://localhost:54321/v1", allowInsecureBaseUrl = true)
        // …but never with a live key.
        assertThrows(PaylodConfigException::class.java) {
            testClient(
                emptyList(),
                apiKey = "mp_live_abc",
                baseUrl = "https://localhost:54321/v1",
                allowInsecureBaseUrl = true,
            )
        }
    }

    @Test
    fun `refuses userinfo, an unexpected port, a query, a fragment, and IP literals`() {
        for (bad in listOf(
            // The real host here is attacker.example — `paylod.dev` is only the username.
            "https://paylod.dev@attacker.example/v1",
            "https://paylod.dev:8443/v1",
            "https://paylod.dev/v1?next=https://attacker.example",
            "https://paylod.dev/v1#x",
            // Raw IPs are never the canonical origin — including private and loopback ranges.
            "https://10.0.0.1/v1",
            "https://192.168.1.1/v1",
            "https://169.254.169.254/v1",
            "https://127.0.0.1/v1",
        )) {
            assertThrows(PaylodConfigException::class.java, { testClient(emptyList(), baseUrl = bad) }, bad)
        }
    }

    @Test
    fun `keeps the test-only loopback exception, and still never allows it with a live key`() {
        testClient(emptyList(), baseUrl = "http://localhost:54321/v1", allowInsecureBaseUrl = true)
        testClient(emptyList(), baseUrl = "http://127.0.0.1:54321/v1", allowInsecureBaseUrl = true)
        assertThrows(PaylodConfigException::class.java) {
            testClient(
                emptyList(),
                apiKey = "mp_live_abc",
                baseUrl = "http://localhost:54321/v1",
                allowInsecureBaseUrl = true,
            )
        }
    }

    // ── 0.3.0: Retry-After parsing ─────────────────────────────────────────────────────────

    @Test
    fun `honours Retry-After regardless of header case, and past the old 10s truncation`() {
        val (paylod, _, clock) = testClientWithClock(
            listOf(Step(status = 429, headers = mapOf("Retry-After" to "45")), Step(status = 202, json = ACK)),
            maxRetries = 1,
        )
        val start = clock.now
        paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k1"))
        // 45s was honoured in full. The old code lowercased-only (missing this header entirely) and
        // then truncated every value to 10s.
        assertTrue(clock.now - start >= 45_000, "waited only ${clock.now - start}ms")
    }

    @Test
    fun `honours the HTTP-date form of Retry-After`() {
        // 2015-10-21T07:28:00Z. The virtual clock is parked 30s before it.
        val target = 1_445_412_480_000L
        val (paylod, _, clock) = testClientWithClock(
            listOf(
                Step(status = 429, headers = mapOf("retry-after" to "Wed, 21 Oct 2015 07:28:00 GMT")),
                Step(status = 202, json = ACK),
            ),
            maxRetries = 1,
            now = target - 30_000,
        )
        paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k1"))
        assertTrue(clock.now >= target, "clock did not reach the Retry-After date: ${clock.now}")
    }

    @Test
    fun `ignores an unusable or past Retry-After rather than misreading it as a delay`() {
        // "1e3" must not become 1000 seconds; a date in the past must not become a negative sleep.
        for (header in listOf("1e3", "not-a-date", "-5", "Wed, 21 Oct 2015 07:28:00 GMT")) {
            val (paylod, _, clock) = testClientWithClock(
                listOf(Step(status = 429, headers = mapOf("retry-after" to header)), Step(status = 202, json = ACK)),
                maxRetries = 1,
                now = 2_000_000_000_000L, // well past 2015
            )
            val start = clock.now
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k1"))
            // Only the ordinary jittered backoff (a few hundred ms) elapsed — no bogus long sleep.
            assertTrue(clock.now - start < 5_000, "header \"$header\" caused a ${clock.now - start}ms sleep")
        }
    }

    @Test
    fun `ceilings a huge Retry-After even when the operation has NO deadline`() {
        // A bare collect() has no absolute deadline, so there is nothing to clamp against. Without a
        // ceiling, `Retry-After: 86400` from a broken or hostile intermediary parks the caller's
        // thread for a full day inside what looks like an ordinary call.
        val (paylod, _, clock) = testClientWithClock(
            listOf(Step(status = 429, headers = mapOf("retry-after" to "86400")), Step(status = 202, json = ACK)),
            maxRetries = 1,
        )
        val start = clock.now
        paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k1"))
        // The 86400s ask is ceilinged to 60s. The bound allows for the ordinary jittered backoff that
        // also runs before the second attempt; what matters is that a DAY did not elapse.
        val elapsed = clock.now - start
        assertTrue(elapsed <= 61_000, "slept ${elapsed}ms with no deadline to clamp to")
    }

    @Test
    fun `clamps a huge Retry-After to the wait deadline instead of overrunning it`() {
        val (paylod, _, clock) = testClientWithClock(
            listOf(
                Step(status = 202, json = ACK),
                // Every status poll is throttled with an hour-long Retry-After.
                Step(status = 429, headers = mapOf("retry-after" to "3600")),
            ),
            maxRetries = 2,
        )
        val start = clock.now
        assertThrows(PaylodException::class.java) {
            paylod.collectAndWait(CollectParams("0712345678", 100, idempotencyKey = "k1"), WaitOptions.of(timeoutMs = 5_000))
        }
        // The 5s wait budget bounds the whole operation. Without the clamp this would have slept an
        // hour per attempt inside a call the caller capped at five seconds.
        assertTrue(clock.now - start <= 5_000, "overran the deadline by ${clock.now - start - 5_000}ms")
    }

    // ── 0.3.0: idempotency-key charset ─────────────────────────────────────────────────────

    @Test
    fun `rejects C1 control characters and invisible Unicode in an idempotency key`() {
        val (paylod, _) = testClient(emptyList())
        // Written as escapes so the intent survives any editor that would "helpfully" normalise them.
        val bad = mapOf(
            "C1 NEL" to "order\u0085123",
            "C1 APC" to "order\u009f123",
            "DEL" to "order\u007f123",
            "NBSP" to "order\u00a0123",
            // Visually IDENTICAL to "order123" in every log and dashboard, but a different key.
            "zero-width space" to "order\u200b123",
            "BOM" to "order\ufeff123",
            "line separator" to "order\u2028123",
            "ideographic space" to "order\u3000123",
        )
        for ((name, k) in bad) {
            assertThrows(PaylodInvalidRequestException::class.java, {
                paylod.collect(CollectParams("0712345678", 100, idempotencyKey = k))
            }, "accepted a key containing $name")
        }
    }

    @Test
    fun `rejects printable NON-ASCII, which no other rule catches`() {
        val (paylod, t) = testClient(emptyList())
        // These pass every other check: not blank, no control chars, no invisible/zero-width
        // characters, well under the length bound. They are still unsendable in an HTTP header.
        val bad = mapOf(
            "accented latin" to "ordr-caf\u00e9-1",
            "CJK" to "order-\u4e2d\u6587-1",
            "symbol outside latin" to "order-\u2764-1",
            "cyrillic homoglyph" to "\u043erder-123", // Cyrillic o, indistinguishable from ASCII o
        )
        for ((name, k) in bad) {
            val err = assertThrows(PaylodInvalidRequestException::class.java, {
                paylod.collect(CollectParams("0712345678", 100, idempotencyKey = k))
            }, "accepted a key containing $name")
            assertTrue(err.message!!.contains("printable ASCII"), "unhelpful message: ${err.message}")
        }
        // Rejected LOCALLY — nothing was dispatched, so no charge could have been started under a key
        // the server would have seen differently.
        assertEquals(0, t.count)
    }

    @Test
    fun `bounds an idempotency key length`() {
        val (paylod, _) = testClient(emptyList())
        // Printable ASCII, so it clears every charset rule and reaches the length bound. (With ASCII
        // enforced, one char is one byte — the byte bound and the char bound now coincide by
        // construction rather than by accident.)
        val key = "a".repeat(256)
        assertThrows(PaylodInvalidRequestException::class.java) {
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = key))
        }
        // 255 is the boundary and must still be accepted; it fails later, on the empty step list.
        assertThrows(IllegalStateException::class.java) {
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "a".repeat(255)))
        }
    }

    // ── 0.3.0: the deadline bounds ALL in-flight work ──────────────────────────────────────

    @Test
    fun `a slow transport cannot overrun the wait deadline`() {
        val (paylod, t, clock) = testClientWithClock(
            listOf(Step(status = 202, json = ACK), Step(json = paymentJson(status = "pending"))),
        )
        val start = clock.now
        assertThrows(PaylodTimeoutException::class.java) {
            paylod.collectAndWait(CollectParams("0712345678", 100, idempotencyKey = "k1"), WaitOptions.of(timeoutMs = 3_000))
        }
        assertTrue(clock.now - start <= 3_000, "wait ran ${clock.now - start}ms past a 3000ms budget")
        // Every status read was itself capped to the remaining budget — none could hang for the full
        // 30s per-request default and blow through the deadline on its own.
        for (call in t.calls.filter { it.method == "GET" }) {
            assertTrue(call.timeoutMs in 1..3_000, "poll timeout was ${call.timeoutMs}")
        }
    }
}
