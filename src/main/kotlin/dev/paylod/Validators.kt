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
            resultDesc = resultDesc as String?,
        )
    }

    /**
     * The wire `resultCode` as a string. JSON numbers arrive as `Long`/`Double`, and a whole `Double`
     * must render as `"1032"` rather than `"1032.0"` — the catalog is keyed on the former, and the
     * latter would classify as an unknown code and silently change the verdict.
     *
     * ZERO IS THE EXCEPTION, and deliberately so. Collapsing a whole `Double` to its integer form is
     * a convenience for CATALOG LOOKUP, where it is harmless: it only decides which entry describes
     * a failure. Applied to zero it stops being a lookup convenience and becomes evidence
     * laundering — it manufactures the canonical success code `"0"` out of a JSON `0.0`, which is
     * not the schema-approved success value (see [DarajaCatalog.isCanonicalSuccessCode]). A float
     * zero therefore keeps its own representation, fails the canonical-zero test downstream, and is
     * classified as ambiguous rather than as proof that money moved.
     */
    private fun normalizeResultCode(v: Any?): String? = when (v) {
        null -> null
        // `isZeroOfAnySign` covers BOTH 0.0 and -0.0. The negative case reaches here now that the
        // JSON reader stops collapsing a raw `-0` into an integral zero, and it must be held back
        // from the integer form for the same reason the positive one is: `(-0.0).toLong()` is `0`,
        // which is the canonical success code spelled exactly.
        is Double ->
            if (v == Math.floor(v) && !isZeroOfAnySign(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** True for 0.0 and for -0.0. Written as a comparison because IEEE equality already merges them. */
    private fun isZeroOfAnySign(v: Double): Boolean = v == 0.0
}
