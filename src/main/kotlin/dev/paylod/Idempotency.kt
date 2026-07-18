package dev.paylod

import java.util.UUID

/**
 * THE resolution of a charge's idempotency key. One implementation, shared by every surface that
 * can raise a charge — production [Paylod.collect], [Paylod.collectAndWait] and the sandbox
 * [Simulator.collect] alike.
 *
 * ── Why the key is REQUIRED ───────────────────────────────────────────────────────────────
 * It used to be optional: omit it and the SDK minted a fresh UUID per call and warned ONCE per
 * process. Both halves of that are wrong, and together they are a double-charge generator.
 *
 * A GENERATED KEY IS NOT IDEMPOTENCY. It is a different value on every invocation, so it collapses
 * exactly nothing. A double-clicked Pay button, a refreshed tab, a redelivered queue job, an
 * application-level retry and a process restart mid-request each mint a NEW key, and each raises a
 * SEPARATE charge — a SECOND STK prompt on the customer's handset. Application-level retry is
 * explicitly in this SDK's threat model, and it is precisely what a per-call generated key cannot
 * survive.
 *
 * And the warning was invisible in every posture that matters. A single `AtomicBoolean` for the
 * whole process meant a worker that handled a thousand unprotected charges warned about the FIRST
 * one and stayed silent for the other 999 — and a loop of charges, which is the case where the
 * defect actually bites, warned once.
 *
 * So the key is REQUIRED. The only route to a generated one is to say so IN THE CALL ITSELF, via a
 * primitive `true`, and it still warns on EVERY call because there is no posture in which this is
 * a good idea outside a scratch script. This mirrors the PHP SDK's `unsafeGeneratedIdempotencyKey`
 * and the Python SDK's `unsafe_generated_idempotency_key`, which are the reference behaviour all
 * four SDKs converge on.
 */
internal object Idempotency {

    /**
     * The effective key for one charge, or a throw.
     *
     * @param key the caller's key. `null` means "not supplied".
     * @param unsafeGenerated the explicit opt-out. A KOTLIN `Boolean` — a JVM primitive `boolean` —
     *   so there is no boxed, nullable or truthy value that can reach here. The comparison is
     *   `!= true` rather than `!unsafeGenerated` to state the intent at the one site that matters.
     * @param what the calling surface, for the message.
     */
    fun resolve(key: String?, unsafeGenerated: Boolean, what: String): String {
        if (key != null) {
            // A caller-supplied key is the double-charge guard — reject a blank/whitespace/
            // control-char one loudly rather than silently drop protection.
            assertValidIdempotencyKey(key)
            return key
        }

        if (unsafeGenerated != true) throw PaylodInvalidRequestException(REQUIRED_MESSAGE(what))

        // EVERY call. No process flag, no per-call-site dedup, no once-per-instance latch — each
        // unprotected charge is a separate opportunity to charge a customer twice, so each one is
        // announced. Deleting the old `AtomicBoolean` is the entire point of this line.
        warnUnprotectedCharge(what)
        return UUID.randomUUID().toString()
    }

    /** The message a caller sees when they omit the key without opting out. */
    internal val REQUIRED_MESSAGE: (String) -> String = { what ->
        "$what requires an idempotencyKey. Mint ONE KEY PER PAYMENT ATTEMPT — an id you create " +
            "when the customer presses Pay and PERSIST on that attempt — and pass it here. Without " +
            "it this charge has no double-charge protection at all: a double-clicked button, a " +
            "refreshed tab, a redelivered job or a process restart will fire a SECOND STK prompt " +
            "and can charge your customer twice. A key the SDK generates for you is not " +
            "idempotency — it is different on every call, so it collapses nothing. If you " +
            "genuinely want an unprotected charge (a scratch script, never production), pass " +
            "unsafeGeneratedIdempotencyKey = true and accept that this call can double-charge. " +
            "See https://paylod.dev/docs/sdk#idempotency"
    }

    /** The text of the per-call warning. Public to the tests so they can count real stderr writes. */
    internal val WARNING: (String) -> String = { what ->
        "[paylod] $what was called with unsafeGeneratedIdempotencyKey = true, so this charge is " +
            "NOT protected against being sent twice. The key is freshly generated and therefore " +
            "collapses nothing: a double-clicked Pay button, a refreshed tab, or a redelivered job " +
            "will fire a SECOND STK prompt and can charge your customer twice. Pass ONE KEY PER " +
            "PAYMENT ATTEMPT instead — an id you mint when the customer presses Pay, and persist " +
            "on that attempt. See https://paylod.dev/docs/sdk#idempotency"
    }

    private fun warnUnprotectedCharge(what: String) {
        // `System.err` is resolved on EVERY call rather than cached, so a test that installs its own
        // stream sees the writes — and so that nothing here can accumulate state across calls.
        System.err.println(WARNING(what))
    }
}
