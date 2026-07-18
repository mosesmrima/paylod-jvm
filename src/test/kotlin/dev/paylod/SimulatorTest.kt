package dev.paylod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorTest {

    private val testKey = "mp_test_abc123"
    private val liveKey = "mp_live_abc123"

    private val simAck: Map<String, Any?> = mapOf(
        "paymentId" to "pay_sim_1",
        "checkoutRequestId" to "sim_ws_CO_1",
        "status" to "pending",
        "outcomes" to listOf(
            mapOf("id" to "approve", "label" to "Approve", "status" to "success"),
            mapOf("id" to "wrong_pin", "label" to "Wrong PIN", "status" to "failed"),
        ),
    )

    private fun settled(outcome: SimOutcomeId): Map<String, Any?> = when (outcome) {
        SimOutcomeId.APPROVE -> mapOf(
            "paymentId" to "pay_sim_1", "status" to "success", "resultCode" to 0,
            "resultDesc" to "ok", "mpesaReceipt" to "SFF6XYZ123", "webhookQueued" to true,
        )
        SimOutcomeId.WRONG_PIN -> mapOf(
            "paymentId" to "pay_sim_1", "status" to "failed", "resultCode" to 2001,
            "resultDesc" to "Wrong PIN", "mpesaReceipt" to null, "webhookQueued" to true,
        )
        SimOutcomeId.INSUFFICIENT_FUNDS -> mapOf(
            "paymentId" to "pay_sim_1", "status" to "failed", "resultCode" to 1,
            "resultDesc" to "insufficient", "mpesaReceipt" to null, "webhookQueued" to true,
        )
        SimOutcomeId.USER_CANCELLED -> mapOf(
            "paymentId" to "pay_sim_1", "status" to "failed", "resultCode" to 1032,
            "resultDesc" to "cancelled", "mpesaReceipt" to null, "webhookQueued" to true,
        )
        SimOutcomeId.TIMEOUT -> mapOf(
            "paymentId" to "pay_sim_1", "status" to "failed", "resultCode" to 1037,
            "resultDesc" to "DS timeout", "mpesaReceipt" to null, "webhookQueued" to true,
        )
    }

    // ── the live-key fence ────────────────────────────────────────────────────────────────

    @Test
    fun `refuses a live key on simulate collect, before any request`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = liveKey)
        assertThrows(PaylodSandboxOnlyException::class.java) { paylod.simulate.collect() }
        assertEquals(0, t.count)
    }

    @Test
    fun `refuses a live key on outcome and pay, with no request sent`() {
        val (paylod, t) = testClient(listOf(Step(status = 200, json = settled(SimOutcomeId.APPROVE))), apiKey = liveKey)
        assertThrows(PaylodSandboxOnlyException::class.java) { paylod.simulate.outcome("pay_1", SimOutcomeId.APPROVE) }
        assertThrows(PaylodSandboxOnlyException::class.java) { paylod.simulate.pay(SimOutcomeId.APPROVE) }
        assertEquals(0, t.count)
    }

    @Test
    fun `refuses to construct a simulate-mode client with a live key`() {
        assertThrows(PaylodSandboxOnlyException::class.java) {
            testClient(emptyList(), apiKey = liveKey, simulate = true)
        }
    }

    @Test
    fun `a sandbox key is accepted for simulate mode`() {
        testClient(emptyList(), apiKey = testKey, simulate = true) // does not throw
    }

    // ── simulate.collect ──────────────────────────────────────────────────────────────────

    @Test
    fun `POSTs simulate collect with a normalised phone and returns a real pending payment`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)))
        val sim = paylod.simulate.collect(
            SimulateCollectParams.builder().phone("0712345678").amount(250).accountReference("order-1")
                .idempotencyKey("sim-1").build(),
        )
        assertTrue(t.calls[0].url.contains("/simulate/collect"))
        assertEquals(mapOf("phone" to "254712345678", "amount" to 250L, "accountRef" to "order-1"), t.calls[0].body)
        assertEquals("pay_sim_1", sim.paymentId)
        assertEquals(PaymentStatus.PENDING, sim.status)
        assertTrue(sim.outcomes.map { it.id }.contains("wrong_pin"))
    }

    @Test
    fun `needs nothing but the idempotency key`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)))
        paylod.simulate.collect(SimulateCollectParams(idempotencyKey = "sim-1"))
        assertEquals(mapOf("phone" to "254708374149", "amount" to 1L), t.calls[0].body)
    }

    @Test
    fun `rejects a nonsense amount locally`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)))
        assertThrows(PaylodInvalidRequestException::class.java) {
            paylod.simulate.collect(
                SimulateCollectParams.builder().amount(0).idempotencyKey("sim-1").build(),
            )
        }
        assertEquals(0, t.count)
    }

    // ── simulate.outcome — the same PaymentOutcome the rest of the SDK returns ─────────────

    private fun drive(outcome: SimOutcomeId): Pair<SimulatedOutcome, StubTransport> {
        val (paylod, t) = testClient(listOf(Step(status = 200, json = settled(outcome))))
        return paylod.simulate.outcome("pay_sim_1", outcome) to t
    }

    @Test
    fun `approve to succeeded, paid, a receipt, and NOT retryable`() {
        val (r, t) = drive(SimOutcomeId.APPROVE)
        assertTrue(t.calls[0].url.contains("/simulate/outcome"))
        assertEquals(mapOf("paymentId" to "pay_sim_1", "outcome" to "approve"), t.calls[0].body)
        assertEquals(OutcomeStatus.SUCCEEDED, r.status)
        assertTrue(r.paid)
        assertEquals("SFF6XYZ123", r.receipt)
        assertFalse(r.retryable)
        assertTrue(r.webhookQueued)
    }

    @Test
    fun `wrong_pin to failed, and genuinely safe to charge again`() {
        val (r, _) = drive(SimOutcomeId.WRONG_PIN)
        assertEquals(OutcomeStatus.FAILED, r.status)
        assertEquals("2001", r.code)
        assertTrue(r.retryable)
        assertTrue(r.message.contains("PIN"))
    }

    @Test
    fun `insufficient_funds to failed with a human message`() {
        val (r, _) = drive(SimOutcomeId.INSUFFICIENT_FUNDS)
        assertEquals(OutcomeStatus.FAILED, r.status)
        assertEquals("1", r.code)
        assertTrue(r.message.lowercase().contains("balance is too low"))
    }

    @Test
    fun `user_cancelled to its own cancelled status`() {
        val (r, _) = drive(SimOutcomeId.USER_CANCELLED)
        assertEquals(OutcomeStatus.CANCELLED, r.status)
        assertEquals("1032", r.code)
    }

    @Test
    fun `timeout to a settled failure (1037)`() {
        val (r, _) = drive(SimOutcomeId.TIMEOUT)
        assertEquals(OutcomeStatus.FAILED, r.status)
        assertEquals("1037", r.code)
        assertTrue(r.message.lowercase().contains("prompt expired"))
    }

    @Test
    fun `every outcome is drivable and never leaves the payment pending`() {
        for (outcome in SimOutcomeId.ALL) {
            val (r, _) = drive(outcome)
            assertNotEquals(OutcomeStatus.PENDING, r.status)
            assertEquals("pay_sim_1", r.paymentId)
            assertTrue(r.message.isNotEmpty())
        }
    }

    // ── simulate mode — the integrator's OWN collect() path, unchanged ─────────────────────

    @Test
    fun `collect in simulate mode creates a simulated payment instead of ringing a phone`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey, simulate = true)
        val ack = paylod.collect("0712345678", 250, idempotencyKey = "order-1042")
        assertTrue(t.calls[0].url.contains("/simulate/collect"))
        assertFalse(t.calls[0].url.contains("/functions/v1/collect"))
        assertEquals("pay_sim_1", ack.paymentId)
        assertEquals(PaymentStatus.PENDING, ack.status)
        assertEquals("order-1042", ack.idempotencyKey)
    }

    @Test
    fun `simulate mode sends the Idempotency-Key header`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey, simulate = true)
        paylod.collect("0712345678", 250, idempotencyKey = "order-1042")
        assertEquals("order-1042", t.calls[0].headers["idempotency-key"])
    }

    @Test
    fun `simulate mode forwards the FULL body — description and metadata included`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey, simulate = true)
        paylod.collect(
            CollectParams.builder("0712345678", 250)
                .accountReference("INV-2041")
                .description("Order #2041")
                .metadata(mapOf("attemptId" to "a1"))
                .idempotencyKey("attempt-1")
                .build(),
        )
        val body = t.calls[0].body!!
        assertEquals("Order #2041", body["description"])
        assertEquals(mapOf("attemptId" to "a1"), body["metadata"])
        assertEquals("INV-2041", body["accountRef"])
        assertEquals(250L, body["amount"])
    }

    @Test
    fun `without simulate, collect still goes to the real collect`() {
        val (paylod, t) = testClient(
            listOf(Step(status = 202, json = mapOf("paymentId" to "p", "status" to "pending", "checkoutRequestId" to "c"))),
            apiKey = testKey,
        )
        paylod.collect("0712345678", 1, idempotencyKey = "k")
        assertTrue(t.calls[0].url.endsWith("/functions/v1/collect"))
    }

    @Test
    fun `pay creates then settles in two requests`() {
        val (paylod, t) = testClient(
            listOf(Step(status = 202, json = simAck), Step(status = 200, json = settled(SimOutcomeId.USER_CANCELLED))),
        )
        val r = paylod.simulate.pay(SimOutcomeId.USER_CANCELLED, SimulateCollectParams.builder().amount(99).idempotencyKey("sim-1").build())
        assertEquals("/simulate/collect", t.calls[0].url.substringAfter("/functions/v1"))
        assertEquals("/simulate/outcome", t.calls[1].url.substringAfter("/functions/v1"))
        assertEquals(OutcomeStatus.CANCELLED, r.status)
    }

    @Test
    fun `SIM_OUTCOMES is the full set of five`() {
        assertEquals(
            listOf(
                SimOutcomeId.APPROVE, SimOutcomeId.WRONG_PIN, SimOutcomeId.INSUFFICIENT_FUNDS,
                SimOutcomeId.USER_CANCELLED, SimOutcomeId.TIMEOUT,
            ),
            SimOutcomeId.ALL,
        )
    }
}
