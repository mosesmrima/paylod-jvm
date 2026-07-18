package dev.paylod

/** Terminal + non-terminal payment states. NOTE: it is `SUCCESS`, never `PAID`. */
enum class PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED;

    internal companion object {
        /**
         * Strict: `null` for anything that is not an exact known wire value. Validators use this so an
         * unrecognised status is REJECTED at the boundary rather than quietly coerced.
         */
        fun parseWire(value: String?): PaymentStatus? = when (value) {
            "pending" -> PENDING
            "success" -> SUCCESS
            "failed" -> FAILED
            else -> null
        }

        // THERE IS DELIBERATELY NO LENIENT PARSE HERE.
        //
        // A `fromWire(value) = parseWire(value) ?: PENDING` used to sit alongside the strict one,
        // documented as "only safe AFTER parseWire has vetted the body". It was not used only there.
        // The money path validated a status body with `parseWire` and then built the `Payment` the
        // verdict was computed from with `fromWire`, so the permissive read is the one that reached
        // the semantic model — and the guard's presence made the leniency look deliberate and safe.
        //
        // Deleting it, rather than leaving it unused, is the actual fix. A permissive fallback that
        // exists is a permissive fallback some future caller reaches for, and the comment saying
        // when it is safe is not a mechanism. Typed records now come out of the validator that
        // vetted them ([PaymentValidators.assertPaymentBody]); an unrecognised status has exactly
        // one outcome, which is rejection.
    }
}

/**
 * The `202 Accepted` body from `POST /collect`. The STK prompt is now on the phone.
 */
data class CollectAck(
    val paymentId: String,
    val status: PaymentStatus,
    val checkoutRequestId: String,
    /**
     * The `Idempotency-Key` that was actually sent: the one you passed, or the random one the SDK
     * generated because you did not. Replaying this exact key returns this exact payment instead of
     * charging again — so if you generated nothing, persist this before you retry.
     */
    val idempotencyKey: String,
)

/**
 * The `200` body from `GET /status/:id`.
 *
 * NOTE on [resultCode]: it is a `String?` (e.g. "0", "1037", "500.001.1001"), not an `Int`. Daraja
 * codes are keyed as strings in the catalog and can be dotted business codes like `500.001.1001`,
 * so a numeric type would be lossy. `null` means M-Pesa has not spoken yet.
 */
data class Payment(
    val id: String,
    val status: PaymentStatus,
    /** The M-Pesa confirmation code (e.g. `SFF6XYZ123`). Only present on success. */
    val mpesaReceipt: String?,
    val resultCode: String?,
    val resultDesc: String?,
)

/** The kind of settlement event paylod POSTs to your endpoint. */
enum class WebhookEventType {
    PAYMENT_SUCCESS,
    PAYMENT_FAILED;

    internal companion object {
        fun fromWire(value: String?): WebhookEventType? = when (value) {
            "payment.success" -> PAYMENT_SUCCESS
            "payment.failed" -> PAYMENT_FAILED
            else -> null
        }
    }
}

/** The verified data payload carried by a [WebhookEvent]. */
data class WebhookEventData(
    val paymentId: String,
    val applicationId: String?,
    val env: String?,
    val status: PaymentStatus?,
    val amount: Int?,
    val phone: String?,
    val accountRef: String?,
    val mpesaReceipt: String?,
    val checkoutRequestId: String?,
    val resultCode: String?,
    val resultDesc: String?,
    /** Populated on `payment.failed`, `null` on `payment.success`. */
    val decoded: DecodedError?,
)

/**
 * The signed JSON body paylod POSTs to your endpoint.
 *
 * [rawType] preserves the original `type` string even when it is not one of the known event types,
 * so a forward-compatible consumer can still branch on it.
 */
data class WebhookEvent(
    val type: WebhookEventType?,
    val rawType: String,
    /** Unix seconds. Also the `t=` value inside the signature — signed, so it cannot be forged. */
    val created: Long?,
    val data: WebhookEventData,
)
