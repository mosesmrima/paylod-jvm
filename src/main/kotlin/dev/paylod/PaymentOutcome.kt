package dev.paylod

/**
 * The whole vocabulary. Four words, deliberately.
 *
 * - `SUCCEEDED` — money moved. Fulfil the order.
 * - `PENDING`   — still in flight. Keep polling. NEVER retry.
 * - `CANCELLED` — the customer pressed Cancel. No money moved; a fresh charge is safe.
 * - `FAILED`    — everything else terminal (wrong PIN, low balance, M-Pesa error).
 */
enum class OutcomeStatus { SUCCEEDED, PENDING, CANCELLED, FAILED }

/**
 * The renderable payment outcome — the type you build your UI on.
 *
 * ```
 * label.text = outcome.message
 * retryButton.isVisible = outcome.retryable
 * ```
 *
 * No `if` over result codes. No catalog in the UI. No M-Pesa knowledge in the app at all.
 */
data class PaymentOutcome(
    val status: OutcomeStatus,
    /**
     * A customer-facing sentence, already decoded from the M-Pesa result code. Safe to render
     * verbatim.
     */
    val message: String,
    /**
     * SAFE TO CHARGE AGAIN — i.e. we know no money moved and no charge is still in flight. Gate your
     * retry button on exactly this. It is `false` for `PENDING` and for `SUCCEEDED`.
     */
    val retryable: Boolean,
    /** The one branch a *backend* legitimately needs: `if (outcome.paid) fulfil(order)`. */
    val paid: Boolean,
    val paymentId: String,
    /** The M-Pesa confirmation code (e.g. `SFF6XYZ123`). Non-null exactly when `paid`. */
    val receipt: String?,
    /** The raw M-Pesa `ResultCode`, normalized to a string. `null` when M-Pesa hasn't spoken. */
    val code: String?,
    /** Title / cause / fix / category, for logs, support tickets and dashboards. */
    val detail: DecodedError?,
    /** The raw payment record, for anything the above doesn't cover. */
    val payment: Payment,
)

/** Builds renderable [PaymentOutcome]s from raw [Payment] records. */
object Outcomes {

    /** Shown while the prompt is live but M-Pesa has not given us a code to decode yet. */
    private const val WAITING = "Check your phone and enter your M-Pesa PIN to complete this payment."

    /**
     * Shown when the record contradicts itself — the raw `status` field says one terminal thing and
     * the decoded result code says another. We CANNOT prove money did or did not move, so this is an
     * indeterminate payment: never `paid`, never `retryable`. It is surfaced as `PENDING` so [wait]
     * lets a webhook settle it (and ultimately throws [PaylodTimeoutException]) rather than reporting
     * a false success or a false failure.
     */
    private const val INDETERMINATE =
        "We couldn't confirm this payment yet. Please wait — do not retry — while it settles."
    private const val CANCELLED_CODE = "1032"

    /**
     * The renderable form of a freshly-sent STK prompt — a pending payment with a "check your phone"
     * message and `retryable = false` (a live prompt is never safe to re-charge).
     */
    @JvmStatic
    fun pending(paymentId: String): PaymentOutcome = PaymentOutcome(
        status = OutcomeStatus.PENDING,
        message = WAITING,
        retryable = false,
        paid = false,
        paymentId = paymentId,
        receipt = null,
        code = null,
        detail = null,
        payment = Payment(
            id = paymentId,
            status = PaymentStatus.PENDING,
            mpesaReceipt = null,
            resultCode = null,
            resultDesc = null,
        ),
    )

    /**
     * Build a renderable outcome from a payment record.
     *
     * The classification is delegated to [DarajaCatalog.classifyStkResult], the canonical classifier
     * the payment engine itself uses. That means the SDK cannot disagree with the backend about
     * whether 4999 is a failure: a `failed` row carrying a pending code is reported as `PENDING`
     * here rather than rendered as a failure to a customer who is, at that moment, typing their PIN.
     */
    @JvmStatic
    fun of(payment: Payment): PaymentOutcome {
        val hasCode = payment.resultCode != null
        val detail = if (hasCode) DarajaCatalog.decodeError(payment.resultCode, payment.resultDesc) else null
        val code = detail?.code

        // When M-Pesa has given us a code, the CLASSIFIER is authoritative and the raw `status` field
        // must NOT override it. A row marked status:"success" carrying a pending code (4999) or a
        // failure code (1032) must never be reported as paid; a row marked status:"failed" carrying a
        // pending code stays pending. Before there is a code, the API's own status is all we have.
        val classified: StkOutcome? =
            if (hasCode) DarajaCatalog.classifyStkResult(payment.resultCode, payment.resultDesc) else null

        // A genuine contradiction between two TERMINAL signals — the raw status says success while
        // the code classifies as a failure, or vice versa. Neither can be trusted, so the payment is
        // INDETERMINATE: not paid, not safe to charge again. (A `pending` classification is NOT a
        // contradiction — it just means "still in flight, keep polling".)
        val contradictory = classified != null &&
            ((classified == StkOutcome.SUCCESS && payment.status == PaymentStatus.FAILED) ||
                (classified == StkOutcome.FAILED && payment.status == PaymentStatus.SUCCESS))

        if (contradictory) {
            return PaymentOutcome(
                status = OutcomeStatus.PENDING,
                message = INDETERMINATE,
                retryable = false,
                paid = false,
                paymentId = payment.id,
                receipt = null,
                code = code,
                detail = detail,
                payment = payment,
            )
        }

        val outcome = classified ?: when (payment.status) {
            PaymentStatus.SUCCESS -> StkOutcome.SUCCESS
            PaymentStatus.FAILED -> StkOutcome.FAILED
            PaymentStatus.PENDING -> StkOutcome.PENDING
        }

        if (outcome == StkOutcome.SUCCESS) {
            return PaymentOutcome(
                status = OutcomeStatus.SUCCEEDED,
                message = detail?.customerMessage ?: "Payment received — thank you!",
                retryable = false,
                paid = true,
                paymentId = payment.id,
                receipt = payment.mpesaReceipt,
                code = code,
                detail = detail,
                payment = payment,
            )
        }

        if (outcome == StkOutcome.PENDING) {
            return PaymentOutcome(
                status = OutcomeStatus.PENDING,
                message = if (detail != null && detail.category == DarajaCategory.PENDING) detail.customerMessage else WAITING,
                retryable = false, // THE double-charge guard. A live prompt is never safe to re-charge.
                paid = false,
                paymentId = payment.id,
                receipt = null,
                code = code,
                detail = detail,
                payment = payment,
            )
        }

        // Terminal failure. Cancellation gets its own word.
        return PaymentOutcome(
            status = if (code == CANCELLED_CODE) OutcomeStatus.CANCELLED else OutcomeStatus.FAILED,
            message = detail?.customerMessage ?: "The payment didn't go through. Please try again.",
            retryable = detail?.retryable ?: false,
            paid = false,
            paymentId = payment.id,
            receipt = null,
            code = code,
            detail = detail,
            payment = payment,
        )
    }
}
