package dev.paylod

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
     */
    fun assertPaymentBody(
        parsed: Any?,
        httpStatus: Int,
        expectedId: String,
        what: String,
        redact: Redactor,
    ) {
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
        PaymentStatus.parseWire(wireStatus)
            ?: bad("status \"$wireStatus\" is not one of pending/success/failed")

        // `mpesaReceipt` is THE proof of settlement. A non-string, or a blank string pretending to
        // be one, is not a receipt.
        val receipt = p["mpesaReceipt"]
        if (receipt != null && (receipt !is String || receipt.isBlank())) {
            bad("mpesaReceipt is present but is not a non-empty string")
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
    }
}
