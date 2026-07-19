package dev.paylod

import dev.paylod.internal.CredentialShapes

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
 *   L2  EVIDENCE     A TERMINAL verdict requires evidence, in BOTH directions. A bare
 *                    `status: "success"` with no receipt and no result code proves nothing and is
 *                    never `PAID`; a bare `status: "failed"` with no result code proves nothing and
 *                    is never `FAILED` (and so is never RETRYABLE). A receipt is not required for
 *                    success — receipts attach asynchronously, and result code 0 is equally good
 *                    evidence — but SOMETHING must be there.
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

    /**
     * THE receipt grammar: exactly ten uppercase alphanumerics.
     *
     * ── Why a POSITIVE grammar replaced the non-emptiness test ────────────────────────────
     * This used to be `!mpesaReceipt.isNullOrBlank()`. Non-emptiness is not a property of a
     * receipt; it is a property of a string. Every one of these passed it and none of them is an
     * M-Pesa confirmation code:
     *
     *   • `"[redacted]"` — the SDK'S OWN redaction placeholder. When a server echoed a credential
     *     into `mpesaReceipt`, scrubbing it wrote this value, and the scrubbed result then SATISFIED
     *     the evidence test. Hiding a secret manufactured proof of payment. This is the single
     *     defect this grammar exists for.
     *   • `"null"`, `"undefined"`, `"N/A"`, `"-"`, `"pending"` — what a serialiser writes for an
     *     absent value, and what a half-populated row carries.
     *   • `"0"` — a truncated field.
     *
     * The shape is derived from every real receipt in the paylod fixtures (`SFF6XYZ123`,
     * `RGH4TYU789`, …): ten characters, uppercase letters and digits only. Sibling SDKs (PHP first,
     * then Node, Python and this one) enforce the IDENTICAL grammar, so a body that is evidence in
     * one is evidence in all of them.
     *
     * ── The anchor ────────────────────────────────────────────────────────────────────────
     * Written WITHOUT `^`/`$` and matched with [Regex.matches], which is a FULL-REGION match — the
     * Kotlin/Java equivalent of `\z`-anchoring. An `$`-anchored pattern would be a live defect here:
     * in Java (as in PCRE and Python `re`) `$` also matches immediately BEFORE a trailing newline,
     * so `"SFF6XYZ123\n"` would validate — a receipt with a smuggled newline is exactly the kind of
     * re-encoded value this check exists to reject. See conformance requirement 7.1.
     */
    @JvmField
    val RECEIPT_RE = Regex("[A-Z0-9]{10}")

    /**
     * Is this string an M-Pesa receipt — by SHAPE, not merely by presence?
     *
     * Exposed publicly because callers reconciling their own records need the same answer the SDK
     * uses, and because a second, subtly different copy in application code is how the two drift.
     */
    @JvmStatic
    fun isValidReceipt(receipt: String?): Boolean {
        if (receipt == null) return false
        // Sanitizer output is checked FIRST and independently of the grammar. The grammar happens to
        // exclude `[redacted]` on its own, but that is a coincidence of which characters the current
        // mask uses — a future mask spelled `XXXXXXXXXX` would satisfy `[A-Z0-9]{10}` exactly. A
        // safety property must not rest on the spelling of an unrelated constant.
        if (CredentialShapes.looksSanitized(receipt)) return false
        return RECEIPT_RE.matches(receipt)
    }

    /**
     * A receipt counts as evidence only if it satisfies [isValidReceipt].
     *
     * Note what this does NOT do: it does not make a malformed receipt into a failure. A record
     * whose receipt is unreadable simply carries no receipt evidence, and the rest of the table
     * decides from the result code. Turning "I cannot read this receipt" into "this payment failed"
     * would be the same claim-without-evidence move the whole file exists to forbid.
     */
    @JvmStatic
    fun hasReceipt(payment: Payment): Boolean = isValidReceipt(payment.mpesaReceipt)

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
     * FAILED            INDETERMINATE  INDETERMINATE   FAILED          INDETERMINATE  INDETERMINATE
     * ```
     *
     * Note the shape of the IN_FLIGHT column: a `pending` claim beside in-flight evidence is the one
     * cell where the two AGREE, and it is the only one that yields IN_FLIGHT. A `failed` claim beside
     * in-flight evidence is a CONTRADICTION and goes to INDETERMINATE like every other contradiction
     * in the table — there are no exceptions to L3 left.
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
     *   • A `FAILED` claim beside in-flight evidence is NOT a terminal failure. Reporting it as one
     *     is the revenue-losing bug this codebase already shipped twice — but neither is it a
     *     confident "the prompt is live". It is a contradiction, so it is INDETERMINATE, which
     *     renders as PENDING and never as retryable. The safe behaviour is identical; the claim the
     *     SDK makes about what it knows is now honest.
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
                // L3, applied to the cell that used to be the one exception to it.
                //
                // This resolved to IN_FLIGHT: "in-flight evidence outranks a terminal claim". That
                // reasoning picks a WINNER between two signals that contradict each other, which is
                // precisely the move L3 exists to forbid — everywhere else in this table, a claim
                // that disagrees with its evidence is INDETERMINATE, and this cell was carved out on
                // the strength of one plausible story about how it arises (a `failed` row carrying
                // 4999 while the customer is mid-PIN). Other stories fit the same bytes just as well:
                // a genuinely failed payment whose result code was written from a stale read, or a
                // record mid-write. We cannot tell them apart, and "cannot tell" is INDETERMINATE by
                // definition, not IN_FLIGHT.
                //
                // Nothing an integrator SEES changes: `Outcomes.of` renders INDETERMINATE exactly as
                // it renders IN_FLIGHT — `OutcomeStatus.PENDING`, `paid = false` and, critically,
                // `retryable = false` — so `wait()` still keeps polling and lets the webhook settle
                // it, and no caller is ever invited to charge again. What changes is that the SDK no
                // longer CLAIMS to know the prompt is live when it does not.
                PaymentEvidence.IN_FLIGHT ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims the payment failed terminally while the result code says it " +
                        "is still in flight — the two contradict, so the record proves nothing; it " +
                        "is neither settled nor safe to charge again"
                // L2, applied to the FAILURE direction — the law was only ever enforced on the
                // success one.
                //
                // A record claiming `failed` with NO receipt and NO result code carries no
                // evidence at all. It was accepted as terminal purely on its own say-so, which is
                // exactly the claim-substituting-for-evidence move this file exists to forbid: the
                // reasoning for it ("proving a payment did NOT happen is not something we require
                // evidence for, because the safe action is the same either way") is false in the
                // one direction that costs money. A terminal FAILED verdict is what makes
                // `Outcomes.of` consult the catalog's `retryable` at all, so a bare `{status:
                // "failed"}` — the shape a truncated row, a stubbed endpoint or a cached error
                // envelope produces — was one catalog lookup away from telling a merchant to
                // charge again for a payment whose real state nobody knew.
                //
                // So it is INDETERMINATE: rendered as PENDING, never paid, never retryable, and
                // `wait()` keeps polling until the webhook settles it or the deadline expires.
                //
                // The CONVERSE is untouched, deliberately: `failed` beside genuine FAILURE evidence
                // is still a terminal failure, and still retryable exactly where the catalog says
                // so. This narrows the door; it does not close it.
                PaymentEvidence.NONE ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims the payment failed but the record carries neither a receipt " +
                        "nor a result code, so there is no evidence it actually failed — a claim " +
                        "is not evidence for itself"
                PaymentEvidence.FAILURE ->
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
