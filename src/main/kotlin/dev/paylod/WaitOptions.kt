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
    class Builder {
        private var timeoutMs: Long = 120_000
        private var onPoll: PollListener? = null

        fun timeoutMs(value: Long) = apply { this.timeoutMs = value }
        fun onPoll(value: PollListener?) = apply { this.onPoll = value }

        fun build(): WaitOptions = WaitOptions(timeoutMs, onPoll)
    }

    companion object {
        @JvmField
        val DEFAULT: WaitOptions = WaitOptions(120_000, null)

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        @JvmOverloads
        fun of(timeoutMs: Long = 120_000, onPoll: PollListener? = null): WaitOptions =
            WaitOptions(timeoutMs, onPoll)
    }
}
