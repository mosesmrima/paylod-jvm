package dev.paylod

/**
 * Request parameters for [Paylod.collect] / [Paylod.collectAndWait].
 *
 * Kotlin callers can use named arguments on the constructor; Java callers should use
 * [CollectParams.builder]. The client also exposes `collect(phone, amount, ...)` overloads for the
 * common case, so a Java caller is never forced to build an object for a two-argument charge.
 */
class CollectParams private constructor(
    /** Any Kenyan format: `0712345678`, `+254712345678`, `254712345678`, `712345678`. */
    @JvmField val phone: String,
    /** Whole KES. Must be a positive integer <= 150000 — M-Pesa rejects decimals. */
    @JvmField val amount: Int,
    /**
     * Your correlation id (invoice/order number), returned as `accountRef`. 1–12 chars. A LABEL, not
     * a lock — it does not deduplicate anything. Never pass the same value here and to [idempotencyKey].
     */
    @JvmField val accountReference: String? = null,
    /** Shown on the STK prompt. 1–64 chars. Defaults server-side to `Payment`. */
    @JvmField val description: String? = null,
    /** Opaque to paylod. Stored alongside the payment; NOT returned on `/status` or the webhook. */
    @JvmField val metadata: Map<String, Any?>? = null,
    /**
     * The thing that stops you charging a customer twice. It names ONE PAYMENT ATTEMPT — one press
     * of Pay. Mint it when the attempt begins, persist it, and never reuse it for a different charge.
     * Omit it and the SDK generates a fresh key per call (and warns once).
     */
    @JvmField val idempotencyKey: String? = null,
) {
    /** Kotlin-friendly constructor with named, defaulted parameters. */
    @JvmOverloads
    constructor(
        phone: String,
        amount: Int,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
    ) : this(phone, amount, accountReference, description, null, idempotencyKey)

    class Builder(private var phone: String, private var amount: Int) {
        private var accountReference: String? = null
        private var description: String? = null
        private var metadata: Map<String, Any?>? = null
        private var idempotencyKey: String? = null

        fun phone(value: String) = apply { this.phone = value }
        fun amount(value: Int) = apply { this.amount = value }
        fun accountReference(value: String?) = apply { this.accountReference = value }
        fun description(value: String?) = apply { this.description = value }
        fun metadata(value: Map<String, Any?>?) = apply { this.metadata = value }
        fun idempotencyKey(value: String?) = apply { this.idempotencyKey = value }

        fun build(): CollectParams =
            CollectParams(phone, amount, accountReference, description, metadata, idempotencyKey)
    }

    companion object {
        @JvmStatic
        fun builder(phone: String, amount: Int): Builder = Builder(phone, amount)
    }
}
