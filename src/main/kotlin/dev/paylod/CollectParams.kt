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

/**
 * THE collect request body — built once, for every collect surface there is.
 *
 * ── Why this is not two functions ─────────────────────────────────────────────────────────
 * It was. `Paylod.buildCollectBody` enforced the amount ceiling, the blank/length rules on
 * `accountReference` and `description`, and the validate-the-same-string-you-transmit discipline.
 * `Simulator.collect` enforced `amount > 0` and nothing else, and sent the caller's strings
 * untouched. So `simulate.collect()` accepted 150,001 KES, a whitespace-only reference, and a
 * 200-character description — every one of which production refuses.
 *
 * That divergence is the whole problem with a simulator. Its reason for existing is that an
 * integration test can drive the real settlement path without a handset; a test that passes against
 * a simulator LOOSER than production is a test that certifies a request production will reject. The
 * rules are therefore not "mirrored" here, they are the SAME CODE — there is no second
 * implementation left to drift.
 *
 * The one legitimate difference is the wire name for the reference field: production's `/collect`
 * calls it `accountReference` and `/simulate/collect` calls it `accountRef`. That is a backend
 * naming fact, so it is a parameter, not a second validator.
 */
internal object CollectBody {

    /** The Daraja per-transaction ceiling this SDK will submit, in KES. */
    const val MAX_AMOUNT = 150_000

    const val MAX_ACCOUNT_REFERENCE = 12
    const val MAX_DESCRIPTION = 64

    fun build(
        phone: String,
        amount: Int,
        accountReference: String?,
        description: String?,
        metadata: Map<String, Any?>?,
        accountRefField: String,
        label: String,
    ): MutableMap<String, Any?> {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw PaylodInvalidRequestException(
                "$label: amount must be between 1 and $MAX_AMOUNT KES (got $amount).",
            )
        }

        val out = LinkedHashMap<String, Any?>()
        out["amount"] = amount
        out["phone"] = Phone.normalize(phone)

        // Validate AND transmit the SAME trimmed representation — never validate the trimmed length
        // while transmitting the untrimmed original (that would let " x…(12 spaces) " slip past a
        // 12-char bound). A provided-but-blank value is rejected rather than sent as empty.
        if (accountReference != null) {
            val ref = accountReference.trim()
            if (ref.isEmpty()) {
                throw PaylodInvalidRequestException("$label: accountReference must not be blank.")
            }
            if (ref.length > MAX_ACCOUNT_REFERENCE) {
                throw PaylodInvalidRequestException(
                    "$label: accountReference must be $MAX_ACCOUNT_REFERENCE characters or fewer.",
                )
            }
            out[accountRefField] = ref
        }
        if (description != null) {
            val desc = description.trim()
            if (desc.isEmpty()) {
                throw PaylodInvalidRequestException("$label: description must not be blank.")
            }
            if (desc.length > MAX_DESCRIPTION) {
                throw PaylodInvalidRequestException(
                    "$label: description must be $MAX_DESCRIPTION characters or fewer.",
                )
            }
            out["description"] = desc
        }
        if (metadata != null) out["metadata"] = metadata
        return out
    }
}
