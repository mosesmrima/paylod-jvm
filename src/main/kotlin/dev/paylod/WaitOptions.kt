package dev.paylod

/** Called with each `pending` snapshot while [Paylod.wait] polls — handy for a spinner. */
fun interface PollListener {
    fun onPoll(payment: Payment)
}

/**
 * Options for [Paylod.wait] / [Paylod.collectAndWait].
 *
 * Kotlin callers can use named arguments; Java callers should use [WaitOptions.builder].
 */
class WaitOptions private constructor(
    /** Give up after this long. Default 120_000 ms (STK prompts expire around 60s). */
    @JvmField val timeoutMs: Long,
    /** Called with each `pending` snapshot. */
    @JvmField val onPoll: PollListener?,
) {
    init {
        // VALIDATED HERE, WHERE THE CALLER WROTE THE VALUE.
        //
        // `wait()` used to do `options.timeoutMs.takeIf { it > 0 } ?: 120_000` — a non-positive
        // timeout was silently replaced by the default. So `WaitOptions.of(timeoutMs = 0)` (an unset
        // config field, a units mix-up, a "0 means no limit" assumption) produced a wait that ran for
        // two minutes while the caller believed they had asked for something else entirely, and there
        // was no error, no warning, and nothing in the object to inspect afterwards. Substituting a
        // different value for the one supplied is worse than refusing it.
        //
        // Bounded above as well, and for the same reason `timeoutMs` is on the client: a wait is a
        // held request thread, and an unbounded one holds it until the process dies.
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw PaylodInvalidRequestException(
                "WaitOptions.timeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms (got " +
                    "$timeoutMs). It is a DURATION to wait for a live STK prompt: 0 or a negative " +
                    "value is not \"wait forever\" and is no longer silently replaced by the default, " +
                    "and a wait longer than $MAX_TIMEOUT_MS ms outlives any prompt it could be " +
                    "waiting on. Leave it unset for the ${DEFAULT_TIMEOUT_MS} ms default.",
            )
        }
    }

    class Builder {
        private var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        private var onPoll: PollListener? = null

        fun timeoutMs(value: Long) = apply { this.timeoutMs = value }
        fun onPoll(value: PollListener?) = apply { this.onPoll = value }

        fun build(): WaitOptions = WaitOptions(timeoutMs, onPoll)
    }

    companion object {
        /** STK prompts expire around 60s; 120s leaves room for a late settlement. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Below this there is no time for even one status read. */
        const val MIN_TIMEOUT_MS = 1L

        /** One hour. Far past the life of any STK prompt this could be waiting on. */
        const val MAX_TIMEOUT_MS = 3_600_000L

        @JvmField
        val DEFAULT: WaitOptions = WaitOptions(DEFAULT_TIMEOUT_MS, null)

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        @JvmOverloads
        fun of(timeoutMs: Long = DEFAULT_TIMEOUT_MS, onPoll: PollListener? = null): WaitOptions =
            WaitOptions(timeoutMs, onPoll)
    }
}
