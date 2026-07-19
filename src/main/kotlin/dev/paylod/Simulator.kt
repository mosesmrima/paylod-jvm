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

/**
 * The single request hook the simulator borrows from the client. Keeps this class transport-free —
 * and, critically, routes every simulator call through the SAME credentialed transport, the SAME
 * retry rules and the SAME validators production uses.
 */
internal fun interface SimRequester {
    fun request(
        method: String,
        path: String,
        body: Any?,
        idempotencyKey: String?,
        validate: ((Map<String, Any?>, Int) -> Unit)?,
    ): Map<String, Any?>
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
    private val redact: dev.paylod.internal.Redactor,
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

        // EXACTLY the production rule, through the one shared implementation: the key is REQUIRED,
        // a blank/whitespace/control-char one is refused, and the only route to a generated key is
        // the explicit primitive-`true` opt-out, which warns on every single call.
        //
        // A simulator whose double-charge behaviour is WEAKER than production's is precisely the
        // divergence that makes a green "a double-click cannot charge twice" test a lie — and
        // "the simulator quietly generates one for you" was exactly that weakness.
        val idempotencyKey = Idempotency.resolve(
            params.idempotencyKey,
            params.unsafeGeneratedIdempotencyKey,
            "simulate.collect()",
        )

        // THE SAME BODY BUILDER AND THE SAME VALIDATORS PRODUCTION RUNS.
        //
        // This used to be a hand-rolled body with `amount > 0` as its only rule, so the simulator
        // accepted 150,001 KES, a whitespace-only `accountReference`, and a 200-character
        // `description` — all of which `collect()` refuses. A simulator that accepts what production
        // rejects certifies a request that cannot be made, which is the exact opposite of what a
        // test double is for. There is now ONE implementation; see [CollectBody].
        //
        // The only difference left is the wire name — `/simulate/collect` calls the reference field
        // `accountRef` — which is a parameter rather than a second validator.
        val body = CollectBody.build(
            phone = params.phone ?: DEFAULT_SIM_PHONE,
            amount = params.amount,
            accountReference = params.accountReference,
            description = params.description,
            metadata = params.metadata,
            accountRefField = "accountRef",
            label = "simulate.collect()",
        )

        // THE SAME validator production runs, with the REAL HTTP status — the 202 requirement
        // included. A simulator that tolerates an acknowledgement production would reject teaches
        // the wrong thing about the shape of a real response, and silently hands back an empty
        // `paymentId` for the rest of the test to trip over.
        // The HTTP status is captured so the simulator's own field checks below can report the
        // response they are rejecting, exactly as the shared validator does.
        var ackStatus = 0
        // ── THE EFFECTIVE KEY SURVIVES EVERY FAILURE PATH, HERE TOO ──────────────────────────
        //
        // `collect()` has wrapped its dispatch in exactly this since round 5: whatever goes wrong
        // after the request leaves — network, timeout, 5xx, a malformed 2xx, a simulator field the
        // checks below refuse — the caller must be able to recover the key that was actually sent
        // and retry with THAT one, because a fresh key is a second charge. `simulate.collect()` had
        // no such wrapper, so every one of those failures arrived with `idempotencyKey = null`.
        //
        // Sandbox money is not real money, but the RULE is what an integration test is exercising.
        // A simulator that loses the handle teaches the caller's retry code a habit that double-
        // charges the moment the same code runs against production.
        //
        // The wrapper spans the FIELD CHECKS as well, not just the dispatch: a refusal from
        // `simBad` happens after the request was sent, so it is exactly a case where a charge may
        // exist and the key is the only way to ask about it.
        try {
            val ack = requester.request("POST", "/simulate/collect", body, idempotencyKey) { map, status ->
                ackStatus = status
                PaymentValidators.assertCollectAck(
                    map, status, idempotencyKey, "simulate.collect()", redact,
                )
            }
            return buildSimulatedPayment(ack, ackStatus, idempotencyKey)
        } catch (e: PaylodException) {
            if (e.idempotencyKey == null) e.idempotencyKey = idempotencyKey
            throw e
        }
    }

    private fun buildSimulatedPayment(
        ack: Map<String, Any?>,
        ackStatus: Int,
        idempotencyKey: String,
    ): SimulatedPayment {
        // THE SIMULATOR'S OWN FIELDS ARE VALIDATED EXACTLY, LIKE EVERY OTHER RESPONSE.
        //
        // The shared payment validator has approved the parts of this ack it owns. The fields
        // BELOW are the simulator's own, and they used to be read permissively: `mapNotNull { …
        // ?: return@mapNotNull null }` silently DROPPED any outcome entry that was not an object,
        // and `?: ""` filled a missing id/label/status with an empty string.
        //
        // That is precisely backwards for this surface. The simulator exists so integration tests
        // can assert against a real settlement path; a broken simulator response is the thing such
        // a test is there to catch. Dropping malformed outcomes made a broken response produce a
        // shorter, quieter list and a GREEN test — a test that passed because it had been handed
        // less to check, which is the worst possible failure mode for a test double.
        @Suppress("UNCHECKED_CAST")
        val outcomesRaw = ack["outcomes"] as? List<Any?>
            ?: simBad("outcomes is missing or is not an array", ackStatus)
        val choices = outcomesRaw.mapIndexed { i, raw ->
            @Suppress("UNCHECKED_CAST")
            val o = raw as? Map<String, Any?> ?: simBad("outcomes[$i] is not an object", ackStatus)
            SimOutcomeChoice(
                id = simString(o["id"], "outcomes[$i].id", ackStatus),
                label = simString(o["label"], "outcomes[$i].label", ackStatus),
                status = simString(o["status"], "outcomes[$i].status", ackStatus),
            )
        }

        return SimulatedPayment(
            paymentId = simString(ack["paymentId"], "paymentId", ackStatus),
            status = PaymentStatus.PENDING,
            checkoutRequestId = simString(ack["checkoutRequestId"], "checkoutRequestId", ackStatus),
            outcomes = choices,
            // The key that was ACTUALLY SENT, handed back exactly as `CollectAck.idempotencyKey`
            // does. Without it a caller who used the `unsafeGeneratedIdempotencyKey` opt-out had no
            // way to learn the key the SDK minted, so a retry could only ever mint a second one.
            idempotencyKey = idempotencyKey,
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
        // Settling is a MUTATING call and it carried no idempotency key at all, so a network retry
        // could re-dispatch it. The key is derived DETERMINISTICALLY from the operation, which is
        // exactly the right shape: retrying "settle THIS payment as THIS outcome" is the same
        // operation and must replay, while settling it as a different outcome is a different one.
        val idempotencyKey = "sim-outcome-$paymentId-${outcome.wire}"

        // The settle response describes a PAYMENT, so it runs the PAYMENT validator — the same one
        // `status()` runs, law L1 ID BINDING included. This surface previously did NO validation at
        // all: it read `paymentId` / `status` straight into a `Payment` and handed it to the
        // renderer, so a body describing a DIFFERENT payment, or claiming success with no evidence,
        // was returned as this payment's outcome. Every dispatch surface runs the same validators,
        // or the guarantee is not a guarantee.
        // The validator BUILDS the record. This used to validate the map and then construct a
        // `Payment` from the map a second time, with the lenient `PaymentStatus.fromWire` — so the
        // strict check and the record the outcome was actually rendered from were two different
        // readings of the same bytes, and money used the lenient one. There is now one reading.
        var validated: Payment? = null
        var settleStatus = 0
        val ack = requester.request("POST", "/simulate/outcome", body, idempotencyKey) { map, status ->
            settleStatus = status
            validated = PaymentValidators.assertPaymentBody(
                normalizeSettleAck(map), status, paymentId, "simulate.outcome()", redact,
            )
        }

        val payment = validated ?: throw PaylodIndeterminateException(
            "simulate.outcome() returned a response that was never validated — refusing to render " +
                "an outcome from it.",
            idempotencyKey,
        )
        // `ack["webhookQueued"] != false` reported TRUE for a MISSING field, for a null, and for a
        // string — so "a webhook was queued" was asserted on the strength of the field's absence.
        // A test written to prove the webhook path fires then passed against a simulator that had
        // queued nothing at all, and passed for the same reason whether the field was absent
        // because nothing was queued or because the response was malformed. It is required, and it
        // is required to be a boolean.
        val webhookQueued = ack["webhookQueued"] as? Boolean
            ?: simBad("webhookQueued is missing or is not a boolean", settleStatus)
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

    /**
     * Refuse a simulator response field, in the same INDETERMINATE posture the shared payment
     * validator uses. The simulator is a sandbox surface, but the rule it teaches about response
     * strictness has to be the same one production enforces — a test double that is more forgiving
     * than the real client is a test double that certifies code the real client will reject.
     */
    private fun simBad(detail: String, httpStatus: Int): Nothing = throw PaylodApiException(
        redact.text(
            "The paylod simulator returned a response this SDK cannot trust ($detail). It is " +
                "refused rather than read past: the simulator exists so your tests can rely on a " +
                "real settlement path, and quietly tolerating a malformed simulator response is " +
                "how an integration test goes green against a backend that is broken.",
        ),
        httpStatus,
        null,
        null,
        indeterminate = true,
    )

    /** A required, non-blank string field. Not coerced with `toString()`, not defaulted to "". */
    private fun simString(v: Any?, field: String, httpStatus: Int): String {
        if (v !is String) simBad("$field is missing or is not a string", httpStatus)
        if (v.isBlank()) simBad("$field is empty", httpStatus)
        // SERVER-CONTROLLED, LANDING ON A DATA CLASS. `SimulatedPayment` and `SimOutcomeChoice` are
        // Kotlin data classes, so `toString()` is generated and prints every field — and printing
        // the simulated payment is the first thing a test does with it. `paymentId`,
        // `checkoutRequestId` and every outcome `id`/`label`/`status` came straight off the wire
        // with no credential check, while the production ack and status read have refused exactly
        // this since round 7. A guarantee the simulator does not share is not a guarantee.
        if (redact.containsCredential(v)) {
            simBad(
                "$field contains something shaped like an API credential, so it is not a $field",
                httpStatus,
            )
        }
        return v
    }

    /**
     * `POST /simulate/outcome` names the payment `paymentId`; a status read names it `id`. The
     * validator is shared, so the ack is renamed into the payment shape rather than the validator
     * being taught a second field name — a validator with per-caller special cases is how two
     * surfaces drift apart. A MISSING `paymentId` deliberately maps to a missing `id`, so the
     * binding check rejects it instead of a default silently making it agree.
     */
    private fun normalizeSettleAck(ack: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(ack)
        out.remove("paymentId")
        if (ack.containsKey("paymentId")) out["id"] = ack["paymentId"]
        return out
    }

    // `normalizeResultCode` used to live here too. It is gone along with the second reading of the
    // ack that needed it — the validator normalizes the code as part of building the record it
    // approved, so there is exactly one implementation of that rule on the money path.
}
