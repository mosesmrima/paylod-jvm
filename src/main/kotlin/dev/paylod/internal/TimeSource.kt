package dev.paylod.internal

/**
 * The clock + sleeper the client uses for its poll backoff and transient-retry waits. Abstracted so
 * tests can drive time deterministically (a fake that advances a virtual clock on `sleep`) instead
 * of really blocking the suite for two minutes.
 */
internal interface TimeSource {
    fun nowMillis(): Long
    fun sleep(ms: Long)
}

/** The production clock: the real wall clock and a real [Thread.sleep]. */
internal object RealTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }
}
