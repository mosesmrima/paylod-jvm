package dev.paylod

import dev.paylod.internal.CredentialShapes
import dev.paylod.internal.Redactor

/**
 * The validators that guard money.
 *
 * They live in their own file for one structural reason: EVERY surface that can create or read a
 * payment must run the SAME checks. When the simulator carried its own hand-rolled copies, it
 * accepted responses production rejects — so a test proving "a double-click cannot charge twice"
 * could go green against a simulator quietly weaker than the thing it stands in for. A validator
 * that only some callers use is not a validator.
 */
internal object PaymentValidators {

    private fun isNonBlankString(v: Any?): Boolean = v is String && v.isNotBlank()

    /**
     * Validate the COMPLETE `POST /collect` acknowledgement — including its HTTP STATUS.
     *
     * Every field here is load-bearing, so a partial check is a false sense of safety. When we do
     * not understand the answer to a CHARGE request, the money state is INDETERMINATE — the charge
     * may well have been raised — so it is surfaced as a stop-and-read signal carrying the key,
     * never handed back as a half-populated ack a caller would treat as a healthy new payment.
     */
    fun assertCollectAck(
        parsed: Any?,
        httpStatus: Int,
        idempotencyKey: String,
        what: String,
        redact: Redactor,
    ) {
        fun bad(detail: String): Nothing = throw PaylodApiException(
            redact.text(
                "$what returned a response that is not a valid collect acknowledgement ($detail) — " +
                    "the charge state is INDETERMINATE. Read the payment with this idempotencyKey " +
                    "before starting any new attempt; do NOT mint a fresh key (that risks a second " +
                    "charge).",
            ),
            httpStatus,
            // The body goes through the SAME deep redaction a non-2xx body does. Storing the RAW
            // parsed body here bypassed it, and `body` is a public field people log wholesale.
            redact.body(parsed),
            idempotencyKey,
            indeterminate = true,
        )

        // THE STATUS IS PART OF THE CONTRACT. `POST /collect` answers 202 Accepted and nothing else:
        // the STK push has been handed to Daraja and the payment is pending. Accepting ANY 2xx meant
        // a bare `200` — the shape a cache, a proxy, a captive portal, a stubbed endpoint or a
        // rewritten route produces — was read as a successfully dispatched charge. A 200 here is not
        // a successful collect; it is a response from something that is not the collect endpoint,
        // and treating it as an ack invents a payment that may not exist (or hides one that does).
        if (httpStatus != 202) {
            bad(
                "HTTP $httpStatus, expected 202 Accepted — a collect that was genuinely dispatched " +
                    "always answers 202",
            )
        }

        val ack = parsed as? Map<*, *> ?: bad("the body is not an object")
        if (!isNonBlankString(ack["paymentId"])) bad("no usable paymentId")
        if (!isNonBlankString(ack["checkoutRequestId"])) bad("no usable checkoutRequestId")

        // CREDENTIAL-BEARING IDENTIFIERS ARE REFUSED. `CollectAck` is a public data class, so its
        // GENERATED `toString()` prints both of these, and a caller who logs the ack — which is the
        // normal thing to do with an ack — publishes whatever the server put in them. A server that
        // echoes the bearer token into `paymentId` has not named a payment; it has handed back our
        // own credential. See [Redactor.containsCredential].
        if (redact.containsCredential(ack["paymentId"] as? String)) {
            bad("paymentId contains something shaped like an API credential, so it is not a payment id")
        }
        if (redact.containsCredential(ack["checkoutRequestId"] as? String)) {
            bad(
                "checkoutRequestId contains something shaped like an API credential, so it is not a " +
                    "checkout request id",
            )
        }

        // SANITIZER OUTPUT IS NOT AN IDENTIFIER. A `paymentId` of `[redacted]` is not a payment we
        // can poll, bind a status read to, or reconcile against — it is a statement that the real id
        // was removed. Accepting it would produce a well-formed `CollectAck` naming a payment that
        // does not exist, and every subsequent `wait()` and `getPayment()` would bind against the
        // placeholder. Refused for the same reason a credential-bearing id is refused: it is not an
        // identifier at all. See conformance requirement 3.4.
        if (CredentialShapes.looksSanitized(ack["paymentId"] as? String)) {
            bad("paymentId is a redaction/sanitizer placeholder, so it does not name a payment")
        }
        if (CredentialShapes.looksSanitized(ack["checkoutRequestId"] as? String)) {
            bad("checkoutRequestId is a redaction/sanitizer placeholder, so it does not name a checkout request")
        }

        // `status` is a HARDCODED LITERAL "pending" on the backend, present on every 202 — including
        // an idempotent REPLAY, which returns the STORED original ack rather than the current settled
        // state. So there is no legitimate ack carrying a settled status, and no legitimate ack
        // missing the field either: both are malformed, and requiring the literal cannot break replay.
        //
        // Note the asymmetry with a STATUS read, which legitimately carries terminal states. There,
        // `success` is not trusted from the string — it must be backed by evidence (law L2).
        val wireStatus = ack["status"]
        if (wireStatus !is String) bad("status is missing or is not a string")
        if (wireStatus != "pending") {
            bad("status is \"$wireStatus\", but a collect acknowledgement is always the literal \"pending\"")
        }
    }

    /**
     * Validate a payment body, and BIND it to the payment that was asked for.
     *
     * ── The binding check (law L1) ────────────────────────────────────────────────────────
     * This is the highest-value single check in the SDK. Nothing previously compared the `id` in
     * the response to the id in the request, so ANY mechanism that returned a DIFFERENT payment's
     * record — a cache keyed on the wrong thing, a proxy collapsing concurrent requests, an
     * off-by-one in a routing or authorization layer, a server-side bug, a crafted response —
     * produced a body the SDK validated happily and then classified on its own merits. If that
     * other payment happened to be settled and paid, the caller was told THEIR payment was paid,
     * and shipped goods for an order nobody had paid for.
     *
     * A response that answers a different question is not a MALFORMED response, it is a WRONG one,
     * and no amount of field-level shape checking can find it — every field is perfectly valid. The
     * request knows which payment it asked about; the answer has to say the same thing, or it is
     * not an answer at all.
     *
     * A mismatch is INDETERMINATE rather than a plain error because that is the honest reading: we
     * now know nothing about the payment we asked about. "I do not know" must never collapse to
     * "failed" (reported as retryable, so the customer is charged twice) or to "paid".
     *
     * ── Why this RETURNS a [Payment] rather than just asserting ───────────────────────────
     * It used to be `Unit`: it vetted the map, and then the CALLER re-read the same map and built a
     * [Payment] from it independently — with `PaymentStatus.fromWire`, a lenient parse that mapped
     * anything unrecognised to `PENDING`. Two readings of one body, and the money path used the
     * permissive one. The strict check and the value that money decisions were actually made from
     * were different objects, so the guard could be satisfied while the constructed record said
     * something the guard never approved, and any future edit that moved, weakened or bypassed the
     * assert left the lenient construction standing on its own.
     *
     * Returning the typed record collapses the two into one. The ONLY way to obtain a [Payment] from
     * a wire body is to go through the validation that produced it, `parseWire` (strict, nullable) is
     * the only status parse left in the SDK, and there is no lenient fallback anywhere for a future
     * caller to reach for — it was deleted rather than merely left unused.
     */
    fun assertPaymentBody(
        parsed: Any?,
        httpStatus: Int,
        expectedId: String,
        what: String,
        redact: Redactor,
    ): Payment {
        fun bad(detail: String): Nothing = throw PaylodApiException(
            redact.text(
                "$what returned a status body this SDK cannot trust ($detail). The payment state is " +
                    "INDETERMINATE — this is NOT a confirmed payment and must NOT be fulfilled. Read " +
                    "the payment again, or let the webhook settle it.",
            ),
            httpStatus,
            redact.body(parsed),
            null,
            indeterminate = true,
        )

        val p = parsed as? Map<*, *> ?: bad("the body is not an object")

        val id = p["id"]
        if (!isNonBlankString(id)) bad("no payment id")
        if (redact.containsCredential(id as? String)) {
            bad("the payment id contains something shaped like an API credential, so it is not an id")
        }
        // See the identical check in [assertCollectAck]: a placeholder is not an identifier, and the
        // binding check below would otherwise compare one placeholder against another.
        if (CredentialShapes.looksSanitized(id as? String)) {
            bad("the payment id is a redaction/sanitizer placeholder, so it does not name a payment")
        }

        // ID BINDING, checked before anything else about the record's CONTENTS: if this fails then
        // every remaining field describes some other payment, and reasoning about them is not merely
        // useless but actively misleading.
        if (id != expectedId) {
            bad(
                "the body describes payment \"$id\" but \"$expectedId\" was requested — this " +
                    "response answers a different question, so it tells you NOTHING about the " +
                    "payment you asked about",
            )
        }

        val wireStatus = p["status"]
        if (wireStatus !is String) bad("status is missing or is not a string")
        val status = PaymentStatus.parseWire(wireStatus)
            ?: bad("status \"$wireStatus\" is not one of pending/success/failed")

        // `mpesaReceipt` is THE proof of settlement. A non-string, or a blank string pretending to
        // be one, is not a receipt.
        val receipt = p["mpesaReceipt"]
        if (receipt != null && (receipt !is String || receipt.isBlank())) {
            bad("mpesaReceipt is present but is not a non-empty string")
        }
        // An M-Pesa receipt is THE proof of settlement and it lands on a public field of a data
        // class. A credential echoed into it is neither a receipt nor safe to expose.
        if (redact.containsCredential(receipt as? String)) {
            bad("mpesaReceipt contains something shaped like an API credential, so it is not a receipt")
        }

        // `resultCode` is an input to the evidence function. A shape it cannot read (a boolean, an
        // object, an empty string) would be normalized into a junk string and classified as an
        // unknown code — silently changing the verdict.
        val resultCode = p["resultCode"]
        if (resultCode != null && resultCode !is String && resultCode !is Number) {
            bad("resultCode is neither a string nor a number")
        }
        if (resultCode is String && resultCode.isBlank()) bad("resultCode is a blank string")

        val resultDesc = p["resultDesc"]
        if (resultDesc != null && resultDesc !is String) bad("resultDesc is present but is not a string")

        // Built HERE, from the values that were just vetted, and handed back. The caller does not get
        // a second look at the map, so it cannot reach a different conclusion from it.
        return Payment(
            id = id as String,
            status = status,
            mpesaReceipt = receipt as String?,
            resultCode = normalizeResultCode(resultCode),
            // SCRUBBED, not refused. `resultDesc` is free-form server prose with no identity role,
            // so a credential echoed into it is masked and the (still useful) rest of the sentence
            // survives. It reaches `Payment.resultDesc`, `PaymentOutcome.payment.resultDesc` and the
            // generated `toString()` of both, which is why it cannot be passed through raw.
            resultDesc = redact.optionalText(resultDesc as String?),
        )
    }

    /**
     * The wire `resultCode` as a string, WITHOUT changing its type.
     *
     * ── Why a whole `Double` no longer collapses to its integer form ──────────────────────
     * It used to: `1032.0` became `"1032"` so the catalog lookup would find the entry. That is the
     * round-6 root defect — NORMALIZATION BEFORE VALIDATION — surviving in the float path, and it
     * is the single most dangerous shape it can take. A raw JSON `1032.0` or `1.032e3` was
     * laundered into the canonical string `"1032"`, which selected the "customer cancelled" entry:
     * `retryable = true`, "offer a clear retry button". A float that Daraja never sent was turned
     * into a confident instruction to charge the customer again.
     *
     * [DarajaCatalog.codeLexeme] already refuses a float-typed code — there is no lossless
     * rendering of a `Double` back to the token the sender wrote, since `0.0`, `-0.0`, `1032.0` and
     * `1.032e3` all collapse, and the schema specifies an integer. That refusal was worth nothing
     * while THIS function handed the catalog a laundered integer string one layer up.
     *
     * So a `Double` now keeps its own representation, unconditionally. `1032.0` stays `"1032.0"`,
     * which is not a code any catalog entry is keyed on, so it decodes as the indeterminate,
     * NON-RETRYABLE fallback — and a float zero still fails
     * [DarajaCatalog.isCanonicalSuccessCode], as it has since round 6. Only an INTEGRAL JSON token
     * (`Long`/`Int`/…) or an exact canonical string can select an entry.
     */
    private fun normalizeResultCode(v: Any?): String? = when (v) {
        null -> null
        // No collapse, at any value. Not just at zero: the non-zero float path selected a RETRYABLE
        // cancellation entry, which is strictly worse than manufacturing a success code.
        is Double -> v.toString()
        else -> v.toString()
    }
}
