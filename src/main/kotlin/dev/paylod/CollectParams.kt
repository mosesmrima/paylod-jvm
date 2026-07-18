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
     *
     * REQUIRED. Omitting it without [unsafeGeneratedIdempotencyKey] is a
     * [PaylodInvalidRequestException] before any network call.
     */
    @JvmField val idempotencyKey: String? = null,
    /**
     * Opt out of the double-charge guard and let the SDK generate a throwaway key.
     *
     * A generated key is NOT idempotency — it is different on every call, so it collapses nothing
     * and an application-level or job-queue retry fires a SECOND STK prompt. This exists for scratch
     * scripts and nothing else. It is a primitive `boolean`, so only a literal `true` opts out, and
     * every call that uses it warns on `System.err`. See [Idempotency].
     */
    @JvmField val unsafeGeneratedIdempotencyKey: Boolean = false,
) {
    /** Kotlin-friendly constructor with named, defaulted parameters. */
    @JvmOverloads
    constructor(
        phone: String,
        amount: Int,
        accountReference: String? = null,
        description: String? = null,
        idempotencyKey: String? = null,
        unsafeGeneratedIdempotencyKey: Boolean = false,
    ) : this(
        phone, amount, accountReference, description, null, idempotencyKey,
        unsafeGeneratedIdempotencyKey,
    )

    class Builder(private var phone: String, private var amount: Int) {
        private var accountReference: String? = null
        private var description: String? = null
        private var metadata: Map<String, Any?>? = null
        private var idempotencyKey: String? = null
        private var unsafeGeneratedIdempotencyKey: Boolean = false

        fun phone(value: String) = apply { this.phone = value }
        fun amount(value: Int) = apply { this.amount = value }
        fun accountReference(value: String?) = apply { this.accountReference = value }
        fun description(value: String?) = apply { this.description = value }
        fun metadata(value: Map<String, Any?>?) = apply { this.metadata = value }
        fun idempotencyKey(value: String?) = apply { this.idempotencyKey = value }

        /** Primitive `boolean` — no boxed or nullable value can opt out. See [Idempotency]. */
        fun unsafeGeneratedIdempotencyKey(value: Boolean) =
            apply { this.unsafeGeneratedIdempotencyKey = value }

        fun build(): CollectParams = CollectParams(
            phone, amount, accountReference, description, metadata, idempotencyKey,
            unsafeGeneratedIdempotencyKey,
        )
    }

    companion object {
        @JvmStatic
        fun builder(phone: String, amount: Int): Builder = Builder(phone, amount)
    }
}
