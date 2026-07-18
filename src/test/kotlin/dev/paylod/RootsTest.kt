package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.Redactor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The two architectural roots, and the boundary fixes that came with them.
 *
 * Every test here carries a `@Tag("nv-…")`, and `scripts/non-vacuity.sh` uses that tag to select
 * exactly this test, REVERT the protection it guards in the source, and require the test to FAIL.
 * A test that passes both with and without its fix proves nothing; the harness is what settles the
 * question rather than the author's confidence.
 */
class RootsTest {

    private val testKey = "mp_test_supersecretkey123"
    private val liveKey = "mp_live_supersecretkey123"
    private val redactor = Redactor(listOf(testKey))

    private fun transportWith(response: HttpResponseSpec) = Transport(
        apiKey = testKey,
        baseUrl = "https://paylod.dev/functions/v1",
        custom = { response },
        redact = redactor,
    )

    private val collectRequest = TransportRequest("POST", "/collect", "{}", "k1", 1_000)

    // ══ ROOT 1 — the transport owns the credential ═══════════════════════════════════════════

    @Test
    @Tag("nv-r1-gate")
    fun `ROOT 1 - a custom transport is a gated, test-only seam`() {
        // Wiring a transport in is not enough. It has to be ASKED for, so it can never be acquired
        // by accident — by a DI container, a config profile, or a well-meaning wrapper.
        val err = assertThrows<PaylodConfigException> {
            Paylod(testKey, PaylodOptions.of(transport = { HttpResponseSpec(202, emptyMap(), "{}") }))
        }
        assertTrue(
            err.message!!.contains("allowCustomTransport"),
            "the message must name the opt-in: ${err.message}",
        )
        // With the opt-in it constructs.
        Paylod(testKey, PaylodOptions.of(transport = { HttpResponseSpec(202, emptyMap(), "{}") }, allowCustomTransport = true))
    }

    @Test
    @Tag("nv-r1-live")
    fun `ROOT 1 - a custom transport is refused for a LIVE key`() {
        // Enforced in the client AND again inside Transport, so reverting either alone leaves the
        // guarantee standing. The harness reverts BOTH — it tests the guarantee, not one of its
        // two implementations.
        assertThrows<PaylodConfigException> {
            Paylod(
                liveKey,
                PaylodOptions.of(
                    transport = { HttpResponseSpec(200, emptyMap(), "{}") },
                    allowCustomTransport = true,
                ),
            )
        }
        assertThrows<PaylodConfigException> {
            Transport(
                apiKey = liveKey,
                baseUrl = "https://paylod.dev/functions/v1",
                custom = { HttpResponseSpec(200, emptyMap(), "{}") },
                redact = Redactor(listOf(liveKey)),
            )
        }
    }

    @Test
    @Tag("nv-r1-nocred")
    fun `ROOT 1 - a custom transport never receives the credential`() {
        // THE root cause. A seam that receives `Authorization: Bearer …` has already been handed
        // the key before any redirect check can run — checking after the fact is not a control.
        var seen: HttpRequestSpec? = null
        val paylod = Paylod(
            testKey,
            PaylodOptions.of(
                transport = { req ->
                    seen = req
                    HttpResponseSpec(202, emptyMap(), Json.write(ACK))
                },
                allowCustomTransport = true,
            ),
        )
        paylod.collect("0712345678", 100, idempotencyKey = "k1")

        val spec = seen!!
        assertNull(spec.headers["authorization"], "the bearer header reached caller-supplied code")
        assertFalse(
            spec.headers.values.any { it.contains(testKey) } || (spec.body ?: "").contains(testKey),
            "the API key reached caller-supplied code by another route",
        )
        assertFalse(spec.toString().contains(testKey), "the spec renders the key")
    }

    @Test
    @Tag("nv-r1-followed")
    fun `ROOT 1 - REFUSES a 2xx the transport reached by FOLLOWING a redirect`() {
        // A detection, not a prevention: by the time this is true the credential is already gone.
        // It exists so the failure is loud and the caller learns the key is burned.
        val err = assertThrows<PaylodSecurityException> {
            transportWith(HttpResponseSpec(200, emptyMap(), "{}", redirected = true))
                .send(collectRequest)
        }
        assertTrue(err.message!!.contains("rotate"), "must tell the caller to rotate: ${err.message}")

        // IT IS NOT A CONNECTION ERROR. That is the whole point of the type: the client's retry loop
        // catches `PaylodConnectionException`, so raising this as one meant the SDK responded to
        // "your bearer token has leaked" by sending the credentialed request again.
        assertFalse(
            err is PaylodConnectionException,
            "a compromise detection must not be a retryable connection error",
        )
        assertTrue(err.indeterminate, "a security refusal never proves the money state")
    }

    @Test
    @Tag("nv-r1-noretry")
    fun `ROOT 1 - a detected credential compromise is TERMINAL and is never retried`() {
        // The end-to-end version of the above, through the real client and its real retry loop, on
        // the CHARGE path. `maxRetries = 3` means a `PaylodConnectionException` here would produce
        // FOUR dispatches — four more chances for the attacker's host to receive the bearer token
        // after the SDK had already concluded it was compromised. Exactly ONE is the fix.
        //
        // The count is taken from the transport that is actually wired in. Asserting on a stub that
        // was built but never passed to the client would make this assertion inert, which is the
        // failure mode the non-vacuity harness exists to catch.
        var dispatches = 0
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(
                maxRetries = 3,
                transport = { _ ->
                    dispatches++
                    HttpResponseSpec(200, emptyMap(), Json.write(ACK), redirected = true)
                },
                allowCustomTransport = true,
            ),
            FakeTimeSource(),
            java.util.Random(1),
        )

        val err = assertThrows<PaylodSecurityException> {
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k-compromise"))
        }
        assertTrue(err.message!!.contains("rotate"))
        // The key still rides along, because the request WAS dispatched and the money state is
        // unknown — the caller must read the payment, not mint a fresh key.
        assertEquals("k-compromise", err.idempotencyKey)
        assertEquals(
            1, dispatches,
            "a credential believed compromised must be sent EXACTLY once, never replayed",
        )
    }

    @Test
    @Tag("nv-r1-noretry-count")
    fun `ROOT 1 - the compromise detection fires exactly once, not maxRetries times`() {
        // Counts the dispatches directly. A retried security refusal shows up here as 4.
        var dispatches = 0
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(
                maxRetries = 3,
                transport = { _ ->
                    dispatches++
                    HttpResponseSpec(200, emptyMap(), "{}", redirected = true)
                },
                allowCustomTransport = true,
            ),
            FakeTimeSource(),
            java.util.Random(1),
        )
        assertThrows<PaylodSecurityException> { paylod.status("pay_123") }
        assertEquals(1, dispatches, "a compromised credential must be sent EXACTLY once, never replayed")
    }

    @Test
    @Tag("nv-r1-origin")
    fun `ROOT 1 - refuses a 2xx whose responding URL is off the pinned origin`() {
        // Catches an implementation that follows a redirect while lying about having done so.
        val err = assertThrows<PaylodSecurityException> {
            transportWith(
                HttpResponseSpec(200, emptyMap(), "{}", url = "https://evil.example/functions/v1/collect"),
            ).send(collectRequest)
        }
        assertTrue(err.message!!.contains("pinned"), "unhelpful message: ${err.message}")

        // The SAME origin is fine, so the check is a pin and not a blanket refusal.
        transportWith(
            HttpResponseSpec(202, emptyMap(), "{}", url = "https://paylod.dev/functions/v1/collect"),
        ).send(collectRequest)
    }

    @Test
    @Tag("nv-r1-3xx")
    fun `ROOT 1 - a 3xx is refused rather than followed`() {
        val err = assertThrows<PaylodApiException> {
            transportWith(
                HttpResponseSpec(302, mapOf("location" to "https://evil.example/"), ""),
            ).send(collectRequest)
        }
        assertEquals(302, err.status)
        assertTrue(err.indeterminate)
    }

    @Test
    @Tag("nv-r1-optsecret")
    fun `ROOT 1 - PaylodOptions publishes no getter for a secret`() {
        // The credential used to sit on a public `@JvmField`, i.e. on an object that gets passed
        // around, held by DI containers and printed by debuggers.
        val methods = PaylodOptions::class.java.methods.map { it.name }
        val fields = PaylodOptions::class.java.fields.map { it.name }
        for (forbidden in listOf("getApiKey", "getWebhookSecret", "getTransport")) {
            assertFalse(forbidden in methods, "PaylodOptions still exposes $forbidden()")
        }
        for (forbidden in listOf("apiKey", "webhookSecret", "transport")) {
            assertFalse(forbidden in fields, "PaylodOptions still exposes the public field $forbidden")
        }
        // And it does not render one either.
        val rendered = PaylodOptions.of(apiKey = testKey, webhookSecret = "whsec_topsecretvalue").toString()
        assertFalse(rendered.contains(testKey), "toString leaked the API key: $rendered")
        assertFalse(rendered.contains("whsec_topsecretvalue"), "toString leaked the webhook secret")
    }

    // ══ ROOT 2 — the semantic model ══════════════════════════════════════════════════════════

    @Test
    @Tag("nv-r2-bind")
    fun `ROOT 2 - L1 ID BINDING - a response for a DIFFERENT payment is never evaluated`() {
        // THE Critical. A settled, paid record for someone else's payment used to be classified on
        // its own merits and returned as YOUR payment's outcome — goods shipped for an order nobody
        // paid for.
        val (paylod, _) = testClient(
            listOf(
                Step(
                    json = mapOf(
                        "id" to "pay_SOMEONE_ELSE",
                        "status" to "success",
                        "mpesaReceipt" to "SFF6XYZ123",
                        "resultCode" to "0",
                    ),
                ),
            ),
        )
        val err = assertThrows<PaylodApiException> { paylod.check("pay_MINE") }
        assertTrue(err.indeterminate, "a wrong-payment answer is INDETERMINATE, never paid or failed")
        assertTrue(
            err.message!!.contains("pay_MINE") && err.message!!.contains("pay_SOMEONE_ELSE"),
            "the message must name both ids: ${err.message}",
        )
    }

    @Test
    @Tag("nv-r2-202")
    fun `ROOT 2 - a collect ack requires HTTP 202`() {
        // A bare 200 is what a cache, a proxy, a captive portal or a rewritten route produces. It
        // is not a dispatched charge, and reading it as one invents a payment that may not exist.
        val (paylod, _) = testClient(listOf(Step(status = 200, json = ACK)))
        val err = assertThrows<PaylodApiException> {
            paylod.collect("0712345678", 100, idempotencyKey = "k-200")
        }
        assertTrue(err.indeterminate)
        assertEquals("k-200", err.idempotencyKey, "the key must survive so a retry cannot mint a new one")
        assertTrue(err.message!!.contains("202"), "unhelpful message: ${err.message}")
    }

    @Test
    @Tag("nv-r2-pending0")
    fun `ROOT 2 - a PENDING row carrying result code ZERO is indeterminate, never paid`() {
        // BEHAVIOUR CHANGE. This came back `paid = true` with a null receipt: a payment the server
        // itself calls unfinished, treated as money in the bank.
        val payment = Payment("pay_1", PaymentStatus.PENDING, null, "0", null)
        val judgement = PaymentSemantics.judge(payment)
        assertEquals(PaymentEvidence.SUCCESS, judgement.evidence)
        assertEquals(PaymentVerdict.INDETERMINATE, judgement.verdict)

        val outcome = Outcomes.of(payment)
        assertFalse(outcome.paid)
        assertFalse(outcome.retryable)
        assertEquals(OutcomeStatus.PENDING, outcome.status)
    }

    @Test
    @Tag("nv-r2-receipt")
    fun `ROOT 2 - L4 - a receipt beside a failure code is indeterminate, never retryable`() {
        // BEHAVIOUR CHANGE, and the worst defect the SDK carried: `failed` + receipt + 1032 was
        // rendered CANCELLED with `retryable = true` — the SDK telling a merchant it was safe to
        // charge again for a payment that carries an M-Pesa confirmation receipt.
        val payment = Payment("pay_1", PaymentStatus.FAILED, "SFF6XYZ123", "1032", "Request cancelled by user")
        val judgement = PaymentSemantics.judge(payment)
        assertEquals(PaymentEvidence.CONFLICT, judgement.evidence)
        assertEquals(PaymentVerdict.INDETERMINATE, judgement.verdict)

        val outcome = Outcomes.of(payment)
        assertFalse(outcome.retryable, "NEVER retryable — this is the double-charge generator")
        assertFalse(outcome.paid)
        assertEquals(OutcomeStatus.PENDING, outcome.status)

        // The law holds for EVERY claim and every non-success code: a receipt forces paid or
        // indeterminate, never failed and never in flight.
        for (status in PaymentStatus.entries) {
            for (code in listOf("1032", "2001", "1", "1037", "4999", "500.001.1001")) {
                val v = PaymentSemantics.judge(Payment("p", status, "SFF6XYZ123", code, null)).verdict
                assertTrue(
                    v == PaymentVerdict.PAID || v == PaymentVerdict.INDETERMINATE,
                    "receipt + $status + $code produced $v",
                )
            }
        }
    }

    @Test
    @Tag("nv-r2-evidence")
    fun `ROOT 2 - L2 - PAID always has success evidence`() {
        // A bare claim is not money. Six characters of JSON from a stubbed endpoint, a truncated
        // row, or a cached proxy envelope must not fulfil an order.
        assertEquals(
            PaymentVerdict.INDETERMINATE,
            PaymentSemantics.judge(Payment("p", PaymentStatus.SUCCESS, null, null, null)).verdict,
        )
        // The converse is NOT required: success WITHOUT a receipt is legitimate, because receipts
        // attach asynchronously. Result code 0 is equally good evidence.
        assertEquals(
            PaymentVerdict.PAID,
            PaymentSemantics.judge(Payment("p", PaymentStatus.SUCCESS, null, "0", null)).verdict,
        )
        assertEquals(
            PaymentVerdict.PAID,
            PaymentSemantics.judge(Payment("p", PaymentStatus.SUCCESS, "SFF6XYZ123", null, null)).verdict,
        )
    }

    @Test
    @Tag("nv-r2-pendingreceipt")
    fun `ROOT 2 - a PENDING row carrying a receipt is indeterminate`() {
        // BEHAVIOUR CHANGE. The record says "not finished" and carries proof it finished.
        val judgement = PaymentSemantics.judge(Payment("p", PaymentStatus.PENDING, "SFF6XYZ123", null, null))
        assertEquals(PaymentEvidence.SUCCESS, judgement.evidence)
        assertEquals(PaymentVerdict.INDETERMINATE, judgement.verdict)
    }

    @Test
    @Tag("nv-r2-inflight")
    fun `ROOT 2 - a FAILED claim beside in-flight evidence is INDETERMINATE, never terminal`() {
        // A `failed` row carrying 4999 is a CONTRADICTION: the claim says terminal, the code says
        // still in flight. This used to resolve to IN_FLIGHT — picking a winner between two signals
        // that disagree, which is the one thing L3 forbids everywhere else in the table. It is now
        // INDETERMINATE like every other contradiction.
        val payment = Payment("p", PaymentStatus.FAILED, null, "4999", null)
        val judgement = PaymentSemantics.judge(payment)
        assertEquals(PaymentVerdict.INDETERMINATE, judgement.verdict)

        // What an integrator SEES is unchanged, and that is the safety argument: still PENDING (so
        // `wait()` keeps polling and the webhook settles it), still not paid, and above all still
        // NOT retryable. Reporting this as a terminal failure is the revenue-losing bug this
        // codebase shipped twice; reporting it as retryable would be the double-charge bug.
        val outcome = Outcomes.of(payment)
        assertEquals(OutcomeStatus.PENDING, outcome.status)
        assertFalse(outcome.retryable)
        assertFalse(outcome.paid)
    }

    @Test
    @Tag("nv-r2-total")
    fun `ROOT 2 - the table is TOTAL and every verdict obeys the four laws`() {
        // Every (claim, evidence) pair has exactly one verdict, and the laws are asserted over the
        // WHOLE cross-product rather than sampled.
        val receipts = listOf(null, "SFF6XYZ123")
        val codes = listOf(null, "0", "1032", "2001", "1", "1037", "4999", "500.001.1001")
        var checked = 0
        for (status in PaymentStatus.entries) {
            for (receipt in receipts) {
                for (code in codes) {
                    val p = Payment("p", status, receipt, code, null)
                    val j = PaymentSemantics.judge(p)
                    checked++

                    // L2: paid implies success evidence.
                    if (j.verdict == PaymentVerdict.PAID) {
                        assertEquals(PaymentEvidence.SUCCESS, j.evidence, "PAID without success evidence: $p")
                    }
                    // L4: a receipt forces paid or indeterminate.
                    if (receipt != null) {
                        assertTrue(
                            j.verdict == PaymentVerdict.PAID || j.verdict == PaymentVerdict.INDETERMINATE,
                            "a receipt produced ${j.verdict}: $p",
                        )
                    }
                    // L3: an indeterminate payment is NEVER retryable and never paid.
                    val o = Outcomes.of(p)
                    if (j.verdict == PaymentVerdict.INDETERMINATE) {
                        assertFalse(o.retryable, "indeterminate but retryable: $p")
                        assertFalse(o.paid, "indeterminate but paid: $p")
                    }
                    // `paid` on the rendered outcome tracks the verdict exactly.
                    assertEquals(j.verdict == PaymentVerdict.PAID, o.paid, "paid disagrees with verdict: $p")
                    // A live prompt is never safe to re-charge.
                    if (j.verdict == PaymentVerdict.IN_FLIGHT) assertFalse(o.retryable, "in-flight retryable: $p")
                    assertNotNull(j.reason)
                }
            }
        }
        assertEquals(PaymentStatus.entries.size * receipts.size * codes.size, checked)
    }

    // ══ The simulator runs the same validators ═══════════════════════════════════════════════

    @Test
    @Tag("nv-sim-key")
    fun `the simulator generates an idempotency key when one is omitted`() {
        // Production `collect()` generates one, so a network retry cannot create two payments. The
        // simulator did not — and a simulator whose double-charge behaviour is WEAKER than
        // production's makes a green "a double-click cannot charge twice" test a lie.
        val (paylod, t) = testClient(
            listOf(Step(status = 202, json = ACK + mapOf("outcomes" to emptyList<Any?>()))),
        )
        paylod.simulate.collect()
        val key = t.calls[0].headers["idempotency-key"]
        assertNotNull(key, "simulate.collect() sent NO Idempotency-Key at all")
        assertTrue(key!!.isNotBlank())
    }

    @Test
    @Tag("nv-sim-bind")
    fun `the simulator binds and validates its outcome response`() {
        // `simulate.outcome()` previously did no validation at all: it read the ack straight into a
        // Payment, so a body for a DIFFERENT payment, or a success with no evidence, came back as
        // this payment's outcome.
        val wrongPayment = testClient(
            listOf(Step(status = 200, json = mapOf("paymentId" to "pay_OTHER", "status" to "success", "resultCode" to 0))),
        )
        val err = assertThrows<PaylodApiException> {
            wrongPayment.first.simulate.outcome("pay_MINE", SimOutcomeId.APPROVE)
        }
        assertTrue(err.indeterminate)

        // And an unevidenced success is judged, not trusted.
        val noEvidence = testClient(
            listOf(Step(status = 200, json = mapOf("paymentId" to "pay_1", "status" to "success"))),
        )
        val outcome = noEvidence.first.simulate.outcome("pay_1", SimOutcomeId.APPROVE)
        assertFalse(outcome.paid, "simulate.outcome() returned paid with no evidence")
    }

    // ══ The boundary fixes ═══════════════════════════════════════════════════════════════════

    @Test
    @Tag("nv-redact-msg")
    fun `an API error message never carries a credential the server echoed back`() {
        val (paylod, _) = testClient(
            listOf(Step(status = 400, json = mapOf("error" to "bad request: Authorization: Bearer mp_test_abc123"))),
            apiKey = "mp_test_abc123",
        )
        val err = assertThrows<PaylodApiException> { paylod.status("pay_1") }
        assertFalse(err.message!!.contains("mp_test_abc123"), "the key leaked into the message: ${err.message}")
    }

    @Test
    @Tag("nv-redact-body")
    fun `an API error body is DEEPLY redacted before it reaches the public field`() {
        // `body` is the field people log wholesale in a catch block.
        val (paylod, _) = testClient(
            listOf(
                Step(
                    status = 400,
                    json = mapOf(
                        "error" to "bad request",
                        "echo" to mapOf(
                            "headers" to mapOf("authorization" to "Bearer mp_test_abc123"),
                            "nested" to listOf(mapOf("k" to "mp_test_abc123")),
                        ),
                    ),
                ),
            ),
            apiKey = "mp_test_abc123",
        )
        val err = assertThrows<PaylodApiException> { paylod.status("pay_1") }
        assertFalse(
            err.body.toString().contains("mp_test_abc123"),
            "the key leaked into the public body field: ${err.body}",
        )
    }

    @Test
    @Tag("nv-keys-any")
    fun `collectAndWait attaches the charge handles to ANY throwable, not only a PaylodException`() {
        // The narrow catch made the guarantee conditional on WHICH LAYER failed. A poll listener or
        // a custom transport throwing an ordinary runtime exception unwound straight past it, and
        // the caller got no key for a charge that is live on a handset right now.
        val (paylod, _) = testClient(listOf(Step(status = 202, json = ACK), Step(json = paymentJson())))
        val err = assertThrows<PaylodConnectionException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-boom"),
                WaitOptions.of(timeoutMs = 30_000, onPoll = { throw IllegalStateException("listener blew up") }),
            )
        }
        assertEquals("k-boom", err.idempotencyKey, "no idempotency key for a LIVE charge")
        assertEquals("pay_123", err.paymentId, "no payment id for a LIVE charge")
    }

    @Test
    @Tag("nv-json-dup")
    fun `the JSON reader REJECTS a duplicate object name`() {
        // Last-value-wins makes a body mean different things to different parsers — and the fields
        // at stake are the id a response is bound to, the status claim, and the evidence.
        for (text in listOf(
            """{"id":"pay_A","id":"pay_B"}""",
            """{"status":"failed","status":"success"}""",
            """{"a":{"mpesaReceipt":null,"mpesaReceipt":"SFF6XYZ123"}}""",
        )) {
            assertThrows<Json.JsonParseException>("accepted $text") { Json.parse(text) }
        }
        // A repeated name in DIFFERENT objects is perfectly legal and still parses.
        assertNotNull(Json.parse("""{"a":{"id":"x"},"b":{"id":"y"}}"""))
    }

    @Test
    @Tag("nv-wh-schema")
    fun `a signed payment_success with NO evidence is REJECTED`() {
        // A signature proves WHO sent the body, not that the body means what a handler assumes.
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L
        val body =
            """{"type":"payment.success","created":1700000000,"data":{"paymentId":"pay_1","status":"success","amount":100}}"""
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, Webhooks.sign(body, secret, now), secret, toleranceSec = Webhooks.DEFAULT_TOLERANCE_SEC, nowSec = now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)

        // The same event WITH evidence is accepted, so this is a rule and not a blanket refusal.
        val good =
            """{"type":"payment.success","created":1700000000,"data":{"paymentId":"pay_1","status":"success","amount":100,"resultCode":0}}"""
        assertEquals(
            "pay_1",
            Webhooks.parseAndVerifyAt(good, Webhooks.sign(good, secret, now), secret, toleranceSec = Webhooks.DEFAULT_TOLERANCE_SEC, nowSec = now).data.paymentId,
        )
    }

    @Test
    @Tag("nv-wh-consistency")
    fun `a signed event whose type contradicts its data status is REJECTED`() {
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L
        val body =
            """{"type":"payment.success","created":1700000000,"data":{"paymentId":"pay_1","status":"failed","amount":100,"resultCode":1032}}"""
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, Webhooks.sign(body, secret, now), secret, toleranceSec = Webhooks.DEFAULT_TOLERANCE_SEC, nowSec = now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
        assertTrue(err.message!!.contains("contradicts"), "unhelpful message: ${err.message}")
    }

    @Test
    @Tag("nv-wh-failedreceipt")
    fun `a signed payment_failed carrying a RECEIPT is REJECTED`() {
        // A failure notice for a payment that carries proof of settlement must never be delivered
        // as a settled failure — acting on it is how a paid customer gets charged again.
        val secret = "whsec_test_secret"
        val now = 1_700_000_000L
        val body =
            """{"type":"payment.failed","created":1700000000,"data":{"paymentId":"pay_1","status":"failed","amount":100,"mpesaReceipt":"SFF6XYZ123","resultCode":1032}}"""
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, Webhooks.sign(body, secret, now), secret, toleranceSec = Webhooks.DEFAULT_TOLERANCE_SEC, nowSec = now)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
    }
}
