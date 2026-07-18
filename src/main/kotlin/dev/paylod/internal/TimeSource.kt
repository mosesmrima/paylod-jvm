package dev.paylod.internal

import dev.paylod.PaylodInterruptedException

/**
 * The clock + sleeper the client uses for its poll backoff and transient-retry waits. Abstracted so
 * tests can drive time deterministically (a fake that advances a virtual clock on `sleep`) instead
 * of really blocking the suite for two minutes.
 *
 * TWO clocks, deliberately, because they answer different questions:
 *
 *  - [monotonicMillis] — "how much time has passed". Never moves backwards, never jumps. EVERY
 *    deadline, elapsed-time and remaining-budget calculation uses this one.
 *  - [nowMillis] — "what time is it". The wall clock. Used ONLY to subtract from an HTTP-date
 *    `Retry-After`, which is the one place an absolute civil time is genuinely required.
 *
 * Mixing them up is a money bug, not a style nit: NTP steps, DST-adjacent corrections and manual
 * clock changes move the wall clock arbitrarily. A backwards adjustment mid-`wait()` would extend
 * polling past the deadline the caller was promised, holding a request thread open long after the
 * budget it was given had expired.
 */
internal interface TimeSource {
    /** Wall-clock milliseconds since the epoch. ONLY for HTTP-date arithmetic — never for deadlines. */
    fun nowMillis(): Long

    /** A monotonic millisecond counter with an arbitrary origin. For deadlines and elapsed time. */
    fun monotonicMillis(): Long

    fun sleep(ms: Long)
}

/** The production clock: the real wall clock, a real monotonic counter, and a real [Thread.sleep]. */
internal object RealTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    override fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // An interrupt must NEVER escape raw from here. Two reasons, both money-relevant:
            //
            //  1. A raw InterruptedException CLEARS the interrupt flag on its way out, so the caller's
            //     cancellation is silently swallowed by whoever catches it next.
            //  2. These sleeps sit between retry attempts and between polls — i.e. AFTER a request may
            //     already have dispatched a charge. A raw InterruptedException is not a PaylodException,
            //     so it slips past the `catch (e: PaylodException)` blocks that attach the effective
            //     Idempotency-Key, and the caller is left with a possibly-live charge and no key to
            //     retry it under. A fresh key on the next attempt is a second charge.
            //
            // Restoring the flag and rethrowing as a PaylodInterruptedException fixes both.
            Thread.currentThread().interrupt()
            throw PaylodInterruptedException(
                "Interrupted while waiting ${ms}ms before the next paylod attempt. A charge may already " +
                    "be in flight — retry with the idempotencyKey attached to this exception, never a " +
                    "fresh one.",
                e,
            )
        }
    }
}
