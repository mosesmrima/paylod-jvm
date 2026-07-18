package dev.paylod

private const val SANDBOX_PREFIX = "mp_test_"
private const val LIVE_PREFIX = "mp_live_"

/** Safaricom's own sandbox test MSISDN. Phone numbers are irrelevant to the simulator. */
private const val DEFAULT_SIM_PHONE = "254708374149"

/**
 * Refuse a production key locally, before any request leaves the process. The backend 403s a
 * `mp_live_` key too, but a "simulate" call that can even *attempt* to touch production is a footgun.
 */
internal fun assertSandboxKey(apiKey: String, what: String) {
    if (apiKey.startsWith(SANDBOX_PREFIX)) return
    val kind = if (apiKey.startsWith(LIVE_PREFIX)) {
        "a production (mp_live_) key"
    } else {
        "a key that is not a sandbox (mp_test_) key"
    }
    throw PaylodSandboxOnlyException(
        "$what refused: you gave it $kind. The simulator only ever creates SANDBOX payments, so a " +
            "production key is categorically the wrong credential — no amount of retrying or " +
            "key-rotating will make this work. Use your mp_test_ key here. Nothing is ever sent to " +
            "a real phone.",
    )
}

/** The single request hook the simulator borrows from the client. Keeps this class transport-free. */
internal fun interface SimRequester {
    fun request(method: String, path: String, body: Any?, idempotencyKey: String?): Map<String, Any?>
}

/**
 * `paylod.simulate` — drive a payment to any of the five outcomes from a test file, with no phone.
 *
 * It does NOT mock anything but the handset: a real sandbox payment row, the real Daraja result
 * codes, the real settlement path, a real signed webhook. Every method refuses a `mp_live_` key
 * locally.
 */
class Simulator internal constructor(
    private val apiKey: String,
    private val requester: SimRequester,
) {

    /** The five outcomes, typed. */
    val outcomes: List<SimOutcomeId> get() = SimOutcomeId.ALL

    /**
     * Create a real, pending, sandbox payment. No phone rings. The returned `paymentId` is an
     * ordinary payment id — feed it to [Paylod.status], [Paylod.check], [Paylod.wait], or your code.
     */
    @JvmOverloads
    fun collect(params: SimulateCollectParams = SimulateCollectParams.DEFAULT): SimulatedPayment {
        assertSandboxKey(apiKey, "simulate.collect()")

        val amount = params.amount
        if (amount <= 0) {
            throw PaylodInvalidRequestException(
                "simulate.collect(): amount must be a positive whole number of KES (got $amount).",
            )
        }
        // Same double-charge guard as production: reject a blank/whitespace/control-char key here too,
        // so a simulator test cannot pass with a key that would be rejected against the real API.
        params.idempotencyKey?.let { assertValidIdempotencyKey(it) }

        val body = LinkedHashMap<String, Any?>()
        body["phone"] = if (params.phone != null) Phone.normalize(params.phone) else DEFAULT_SIM_PHONE
        body["amount"] = amount
        // The backend calls this field `accountRef`; the SDK calls it `accountReference`.
        if (params.accountReference != null) body["accountRef"] = params.accountReference
        // Send the FULL body — the idempotency layer fingerprints it, so a dropped field is a field
        // it cannot fingerprint.
        if (params.description != null) body["description"] = params.description
        if (params.metadata != null) body["metadata"] = params.metadata

        val ack = requester.request("POST", "/simulate/collect", body, params.idempotencyKey)

        @Suppress("UNCHECKED_CAST")
        val outcomesRaw = ack["outcomes"] as? List<Any?> ?: emptyList()
        val choices = outcomesRaw.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val o = raw as? Map<String, Any?> ?: return@mapNotNull null
            SimOutcomeChoice(
                id = o["id"]?.toString() ?: "",
                label = o["label"]?.toString() ?: "",
                status = o["status"]?.toString() ?: "",
            )
        }

        return SimulatedPayment(
            paymentId = ack["paymentId"]?.toString() ?: "",
            status = PaymentStatus.PENDING,
            checkoutRequestId = ack["checkoutRequestId"]?.toString() ?: "",
            outcomes = choices,
        )
    }

    /**
     * Force how a simulated payment resolves, and get back the ordinary [PaymentOutcome] the rest of
     * the SDK returns — decoded, renderable, with `retryable` already correct. A real signed webhook
     * fires as a side effect.
     */
    fun outcome(paymentId: String, outcome: SimOutcomeId): SimulatedOutcome {
        assertSandboxKey(apiKey, "simulate.outcome()")
        if (paymentId.isEmpty()) {
            throw PaylodInvalidRequestException("simulate.outcome(): paymentId is required.")
        }

        val body = linkedMapOf<String, Any?>("paymentId" to paymentId, "outcome" to outcome.wire)
        val ack = requester.request("POST", "/simulate/outcome", body, null)

        val payment = Payment(
            id = ack["paymentId"]?.toString() ?: paymentId,
            status = PaymentStatus.fromWire(ack["status"]?.toString()),
            mpesaReceipt = ack["mpesaReceipt"]?.toString(),
            resultCode = normalizeResultCode(ack["resultCode"]),
            resultDesc = ack["resultDesc"]?.toString(),
        )
        val webhookQueued = ack["webhookQueued"] != false
        return SimulatedOutcome(Outcomes.of(payment), webhookQueued)
    }

    /** [collect] + [outcome] in one call. */
    @JvmOverloads
    fun pay(
        outcome: SimOutcomeId,
        params: SimulateCollectParams = SimulateCollectParams.DEFAULT,
    ): SimulatedOutcome {
        val created = collect(params)
        return outcome(created.paymentId, outcome)
    }

    private fun normalizeResultCode(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}
