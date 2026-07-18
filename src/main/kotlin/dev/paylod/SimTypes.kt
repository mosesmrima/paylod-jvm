package dev.paylod

/**
 * The five things that can happen to an STK prompt, as a typed enum — a typo is a compile error,
 * not a 422 you discover in CI.
 *
 * | outcome              | settles as | Daraja code | PaymentOutcome.status |
 * | -------------------- | ---------- | ----------- | --------------------- |
 * | APPROVE              | success    | 0           | SUCCEEDED             |
 * | WRONG_PIN            | failed     | 2001        | FAILED                |
 * | INSUFFICIENT_FUNDS   | failed     | 1           | FAILED                |
 * | USER_CANCELLED       | failed     | 1032        | CANCELLED             |
 * | TIMEOUT              | failed     | 1037        | FAILED                |
 */
enum class SimOutcomeId(internal val wire: String) {
    APPROVE("approve"),
    WRONG_PIN("wrong_pin"),
    INSUFFICIENT_FUNDS("insufficient_funds"),
    USER_CANCELLED("user_cancelled"),
    TIMEOUT("timeout");

    companion object {
        /** Every outcome, in the order the hosted simulator shows them. */
        @JvmField
        val ALL: List<SimOutcomeId> = listOf(APPROVE, WRONG_PIN, INSUFFICIENT_FUNDS, USER_CANCELLED, TIMEOUT)
    }
}

/** Parameters for [Simulator.collect]. */
class SimulateCollectParams private constructor(
    @JvmField val phone: String? = null,
    @JvmField val amount: Int = 1,
    @JvmField val accountReference: String? = null,
    @JvmField val description: String? = null,
    @JvmField val metadata: Map<String, Any?>? = null,
    @JvmField val idempotencyKey: String? = null,
) {
    @JvmOverloads
    constructor(
        phone: String? = null,
        amount: Int = 1,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
    ) : this(phone, amount, accountReference, description, null, idempotencyKey)

    class Builder {
        private var phone: String? = null
        private var amount: Int = 1
        private var accountReference: String? = null
        private var description: String? = null
        private var metadata: Map<String, Any?>? = null
        private var idempotencyKey: String? = null

        fun phone(value: String?) = apply { this.phone = value }
        fun amount(value: Int) = apply { this.amount = value }
        fun accountReference(value: String?) = apply { this.accountReference = value }
        fun description(value: String?) = apply { this.description = value }
        fun metadata(value: Map<String, Any?>?) = apply { this.metadata = value }
        fun idempotencyKey(value: String?) = apply { this.idempotencyKey = value }

        fun build(): SimulateCollectParams =
            SimulateCollectParams(phone, amount, accountReference, description, metadata, idempotencyKey)
    }

    companion object {
        @JvmField
        val DEFAULT: SimulateCollectParams = SimulateCollectParams()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

/** One outcome the simulator will accept for a given payment, as the backend advertises it. */
data class SimOutcomeChoice(
    val id: String,
    val label: String,
    val status: String,
)

/** The `202` from `POST /simulate/collect` — a real, pending, sandbox payment. */
data class SimulatedPayment(
    val paymentId: String,
    val status: PaymentStatus,
    val checkoutRequestId: String,
    val outcomes: List<SimOutcomeChoice>,
)

/**
 * A settled simulated payment: the same [PaymentOutcome] the rest of the SDK returns, plus whether
 * the real signed webhook was enqueued. Passthrough accessors mirror the Node SDK, where
 * `SimulatedOutcome extends PaymentOutcome`.
 */
class SimulatedOutcome internal constructor(
    /** The underlying renderable outcome. */
    @JvmField val outcome: PaymentOutcome,
    /** `true` when the real signed webhook was enqueued for delivery to your endpoint. */
    @JvmField val webhookQueued: Boolean,
) {
    val status: OutcomeStatus get() = outcome.status
    val message: String get() = outcome.message
    val retryable: Boolean get() = outcome.retryable
    val paid: Boolean get() = outcome.paid
    val paymentId: String get() = outcome.paymentId
    val receipt: String? get() = outcome.receipt
    val code: String? get() = outcome.code
    val detail: DecodedError? get() = outcome.detail
    val payment: Payment get() = outcome.payment
}
