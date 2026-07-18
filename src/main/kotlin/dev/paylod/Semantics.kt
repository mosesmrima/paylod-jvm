package dev.paylod

/**
 * THE semantic model for a payment record.
 *
 * ── Why this file exists ──────────────────────────────────────────────────────────────────
 * Before it, "is this payment paid?" was answered by a scatter of per-field checks spread across
 * [Outcomes.of] and the status validator in [Paylod]. Each check was locally reasonable and the
 * set of them was collectively wrong, because nothing stated the RULES the fields have to satisfy
 * TOGETHER. Every hole that survived four rounds of per-finding patching was of one kind: a body
 * whose fields CONTRADICT each other, resolved in favour of whichever field the code happened to
 * read first.
 *
 *   • `{status: "pending", resultCode: "0"}` was reported PAID, with a null receipt — a payment
 *     the server itself calls unfinished, treated as money in the bank.
 *   • `{status: "failed", mpesaReceipt: "SFF6XYZ123", resultCode: "1032"}` was reported
 *     `CANCELLED` **and `retryable = true`** — the SDK telling a merchant it was safe to charge
 *     again for a payment that carries an M-Pesa confirmation receipt. That is a double-charge
 *     generator, and it is the single worst defect a payments SDK can have.
 *
 * Shape validation cannot catch either one: both bodies are perfectly well-typed. What was
 * missing is a model of what the fields MEAN together. That is what this file is.
 *
 * ── The model ─────────────────────────────────────────────────────────────────────────────
 * A payment record makes ONE CLAIM ([Payment.status]) and carries EVIDENCE
 * ([Payment.mpesaReceipt], [Payment.resultCode]). These are separate things and are never allowed
 * to substitute for one another. Evaluation is two stages plus a set of laws:
 *
 *   1. EVIDENCE — what the record PROVES, independent of what it claims ([evidenceFor]).
 *   2. VERDICT  — the claim and the evidence resolved together, via ONE TOTAL TABLE ([judge]).
 *   3. LAWS     — invariants the table is required to satisfy (asserted in the tests).
 *
 * ── The four laws ─────────────────────────────────────────────────────────────────────────
 * These are the contract. The sibling Node / PHP / Python SDKs mirror THESE, not the code.
 *
 *   L1  BINDING      A record whose `id` is not the id that was requested is never evaluated at
 *                    all. It is a hard, INDETERMINATE error. (Enforced at the transport boundary
 *                    in [PaymentValidators.assertPaymentBody] — a wrong-payment body must not
 *                    even reach this file.)
 *   L2  EVIDENCE     `PAID` requires SUCCESS evidence. A bare `status: "success"` with no receipt
 *                    and no result code proves nothing and is never paid. The converse is NOT
 *                    required: success WITHOUT a receipt is legitimate, because receipts attach
 *                    asynchronously — result code 0 is equally good evidence.
 *   L3  CONSISTENCY  A claim that contradicts its evidence is INDETERMINATE — never a failure,
 *                    and in particular never a RETRYABLE failure. We cannot prove money did not
 *                    move, so we must not invite a second charge.
 *   L4  RECEIPT      A receipt is proof money moved. Its presence forces the verdict to `PAID` or
 *                    `INDETERMINATE` — never `FAILED`, never `IN_FLIGHT`.
 *
 * The asymmetry in L3 is deliberate and is the whole safety argument: an indeterminate payment is
 * rendered as `PENDING` so [Paylod.wait] keeps polling and lets the webhook settle it, rather than
 * reporting a false success (merchant ships goods that were never paid for) or a false retryable
 * failure (merchant charges the customer twice).
 */

/**
 * What the record PROVES, derived ONLY from [Payment.mpesaReceipt] and [Payment.resultCode].
 *
 * [Payment.status] is deliberately not an input here — a claim is not evidence for itself.
 */
enum class PaymentEvidence {
    /** Nothing to go on: no receipt, no result code. Proves neither direction. */
    NONE,

    /** A receipt, or result code 0. Money moved. */
    SUCCESS,

    /** A terminal failure code (1032 cancelled, 2001 wrong PIN, 1 low balance, …). */
    FAILURE,

    /** A pending code (4999, 500.001.1001), or a code we cannot place. Still on the handset. */
    IN_FLIGHT,

    /** The evidence disagrees with ITSELF — e.g. a receipt alongside a cancellation code. */
    CONFLICT,
}

/**
 * The resolved state of a payment. This is what every caller branches on.
 *
 * [INDETERMINATE] is not a failure mode of the SDK — it is a real, expected state of a real
 * payment, and treating it as anything else loses money in both directions.
 */
enum class PaymentVerdict {
    /** Money moved, and we can prove it. Fulfil the order. */
    PAID,

    /** Terminal, no money moved. Safe to charge again IF the catalog says the code is retryable. */
    FAILED,

    /** Still on the handset. Keep polling. NEVER retry — the prompt is live. */
    IN_FLIGHT,

    /** We cannot prove what happened. Never paid, never retryable. Let the webhook settle it. */
    INDETERMINATE,
}

/** A verdict, plus everything needed to explain it in a log line. */
data class PaymentJudgement(
    @JvmField val verdict: PaymentVerdict,
    @JvmField val evidence: PaymentEvidence,
    /** The record's own `status` field — what it CLAIMED, for diagnostics. */
    @JvmField val claimed: PaymentStatus,
    /** Why this verdict, in one human sentence. For logs and error messages, never for a customer. */
    @JvmField val reason: String,
)

/** The judge. One evidence function, one total table, no defaults. */
object PaymentSemantics {

    /** A receipt counts only if it is a non-blank string. `""` and `"   "` prove nothing. */
    @JvmStatic
    fun hasReceipt(payment: Payment): Boolean = !payment.mpesaReceipt.isNullOrBlank()

    /** A result code is "present" if it is neither null nor blank. `"0"` is present and meaningful. */
    @JvmStatic
    fun hasResultCode(payment: Payment): Boolean = !payment.resultCode.isNullOrBlank()

    /**
     * Stage 1 — what the record proves, ignoring what it claims.
     *
     * The receipt and the result code are two independent witnesses. When they agree, or when only
     * one of them speaks, the answer is theirs. When they DISAGREE the answer is [PaymentEvidence.CONFLICT],
     * which L3 sends straight to [PaymentVerdict.INDETERMINATE]: a receipt beside a cancellation
     * code is not a cancellation with a stray field, it is a record we have no business acting on.
     */
    @JvmStatic
    fun evidenceFor(payment: Payment): PaymentEvidence {
        // `classifyStkResult` is the canonical classifier the payment engine itself uses, so the SDK
        // cannot drift from the backend about what 4999 means. It maps blank/unknown codes to
        // PENDING on purpose — we refuse to force-fail on ambiguity.
        val codeEvidence = if (hasResultCode(payment)) {
            when (DarajaCatalog.classifyStkResult(payment.resultCode, payment.resultDesc)) {
                StkOutcome.SUCCESS -> PaymentEvidence.SUCCESS
                StkOutcome.FAILED -> PaymentEvidence.FAILURE
                StkOutcome.PENDING -> PaymentEvidence.IN_FLIGHT
            }
        } else {
            PaymentEvidence.NONE
        }

        if (!hasReceipt(payment)) return codeEvidence

        // A receipt is present. It agrees with success evidence and with silence; it CONTRADICTS a
        // terminal failure code and an in-flight code alike — a receipt means M-Pesa has settled,
        // which is incompatible with "still on the handset".
        return when (codeEvidence) {
            PaymentEvidence.SUCCESS, PaymentEvidence.NONE -> PaymentEvidence.SUCCESS
            PaymentEvidence.FAILURE, PaymentEvidence.IN_FLIGHT, PaymentEvidence.CONFLICT ->
                PaymentEvidence.CONFLICT
        }
    }

    /**
     * Stage 2 — the claim and the evidence resolved together.
     *
     * ── The table ─────────────────────────────────────────────────────────────────────────
     * ```
     * claim \ evidence  NONE           SUCCESS         FAILURE         IN_FLIGHT      CONFLICT
     * SUCCESS           INDETERMINATE  PAID            INDETERMINATE   INDETERMINATE  INDETERMINATE
     * PENDING           IN_FLIGHT      INDETERMINATE   INDETERMINATE   IN_FLIGHT      INDETERMINATE
     * FAILED            FAILED         INDETERMINATE   FAILED          IN_FLIGHT      INDETERMINATE
     * ```
     *
     * This table is TOTAL: every (claim, evidence) pair has exactly one verdict, every pair is
     * ENUMERATED rather than derived, and there is **no `else` branch anywhere**. Adding a
     * [PaymentStatus] or a [PaymentEvidence] member is therefore a COMPILE ERROR rather than a
     * silent fallthrough to some permissive default — and defaults are exactly where the old logic
     * went wrong. Kotlin's exhaustive `when` over enums, used as an expression, is what enforces it.
     *
     * Reading the table, the rules are:
     *   • Success evidence beside a non-success claim is never paid and never failed — the two
     *     signals contradict, so it is indeterminate (L3 + L4).
     *   • A success claim needs evidence to be believed (L2).
     *   • A failure claim is believed on failure evidence or on silence: proving a payment did NOT
     *     happen is not something we require evidence for, because the safe action (do not ship,
     *     do not capture) is the same either way.
     *   • In-flight evidence outranks a terminal `FAILED` claim: a `failed` row carrying 4999 means
     *     the prompt is STILL LIVE and the customer is mid-PIN. Reporting that as a failure is the
     *     revenue-losing bug this codebase already shipped twice.
     */
    @JvmStatic
    fun judge(payment: Payment): PaymentJudgement {
        val evidence = evidenceFor(payment)
        val claimed = payment.status

        val (verdict, reason) = when (claimed) {
            PaymentStatus.SUCCESS -> when (evidence) {
                PaymentEvidence.SUCCESS ->
                    PaymentVerdict.PAID to
                        "status is success and it is backed by a receipt or result code 0"
                // L2. The "a stubbed endpoint / truncated row / cached proxy envelope can write six
                // characters of JSON" case. A claim with nothing behind it is not money.
                PaymentEvidence.NONE ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims success but the record carries neither a receipt nor a " +
                        "result code, so there is no evidence the payment actually settled"
                PaymentEvidence.FAILURE ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims success but the result code is a terminal failure"
                PaymentEvidence.IN_FLIGHT ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims success but the result code says the payment is still in flight"
                PaymentEvidence.CONFLICT ->
                    PaymentVerdict.INDETERMINATE to CONFLICT_REASON
            }

            PaymentStatus.PENDING -> when (evidence) {
                // THE named hole. `{status: "pending", resultCode: "0"}` used to come back PAID,
                // with a null receipt. A record that simultaneously says "not finished" and
                // "succeeded" is not a success we may act on — it is a record mid-write, or one we
                // are misreading.
                PaymentEvidence.SUCCESS ->
                    PaymentVerdict.INDETERMINATE to
                        "status says pending while the evidence says the payment succeeded — a " +
                        "pending record must never be reported as paid"
                PaymentEvidence.FAILURE ->
                    PaymentVerdict.INDETERMINATE to
                        "status says pending while the result code is a terminal failure"
                PaymentEvidence.NONE, PaymentEvidence.IN_FLIGHT ->
                    PaymentVerdict.IN_FLIGHT to "the payment is still on the handset"
                PaymentEvidence.CONFLICT ->
                    PaymentVerdict.INDETERMINATE to CONFLICT_REASON
            }

            PaymentStatus.FAILED -> when (evidence) {
                // L4. Includes the receipt-on-a-failed-row case that used to be rendered as
                // CANCELLED with `retryable = true` — an explicit invitation to charge twice.
                PaymentEvidence.SUCCESS ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims failed but the evidence proves the payment succeeded — " +
                        "refusing to report a payment that carries proof of settlement as a failure"
                PaymentEvidence.IN_FLIGHT ->
                    PaymentVerdict.IN_FLIGHT to
                        "status says failed but the result code means the prompt is still live and " +
                        "the customer has not entered their PIN yet"
                PaymentEvidence.NONE, PaymentEvidence.FAILURE ->
                    PaymentVerdict.FAILED to "the payment failed terminally"
                PaymentEvidence.CONFLICT ->
                    PaymentVerdict.INDETERMINATE to CONFLICT_REASON
            }
        }

        return PaymentJudgement(verdict, evidence, claimed, reason)
    }

    private const val CONFLICT_REASON =
        "the record carries an M-Pesa receipt alongside a result code that is not a success — " +
            "the receipt proves money moved and the code denies it, so neither can be trusted"
}
