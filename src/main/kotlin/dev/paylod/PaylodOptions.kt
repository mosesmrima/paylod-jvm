package dev.paylod

/**
 * Escape hatches for [Paylod] construction. You should almost never need any of these — the base
 * URL is baked in and identical for every customer.
 *
 * Kotlin callers can use named arguments; Java callers should use [PaylodOptions.builder].
 */
class PaylodOptions private constructor(
    /** Defaults to the `PAYLOD_API_KEY` environment variable. */
    @JvmField val apiKey: String?,
    /** Defaults to `PAYLOD_BASE_URL` or `https://paylod.dev/functions/v1`. */
    @JvmField val baseUrl: String?,
    /** Defaults to `PAYLOD_WEBHOOK_SECRET`. Only needed for webhook verification. */
    @JvmField val webhookSecret: String?,
    /** Per-HTTP-request timeout, ms. Default 30_000. */
    @JvmField val timeoutMs: Long,
    /** Retries for *idempotent/transient* failures (network, 5xx, 429). Default 2. */
    @JvmField val maxRetries: Int,
    /** Inject an HTTP transport (tests, proxies, instrumentation). Defaults to [JdkHttpTransport]. */
    @JvmField val transport: HttpTransport?,
    /**
     * Simulator mode — for tests. `collect()` / `collectAndWait()` create a *simulated* payment
     * instead of ringing a phone. Requires a `mp_test_` key; the constructor throws
     * [PaylodSandboxOnlyException] otherwise, so it can never point at production.
     */
    @JvmField val simulate: Boolean,
) {
    class Builder {
        private var apiKey: String? = null
        private var baseUrl: String? = null
        private var webhookSecret: String? = null
        private var timeoutMs: Long = 30_000
        private var maxRetries: Int = 2
        private var transport: HttpTransport? = null
        private var simulate: Boolean = false

        fun apiKey(value: String?) = apply { this.apiKey = value }
        fun baseUrl(value: String?) = apply { this.baseUrl = value }
        fun webhookSecret(value: String?) = apply { this.webhookSecret = value }
        fun timeoutMs(value: Long) = apply { this.timeoutMs = value }
        fun maxRetries(value: Int) = apply { this.maxRetries = value }
        fun transport(value: HttpTransport?) = apply { this.transport = value }
        fun simulate(value: Boolean) = apply { this.simulate = value }

        fun build(): PaylodOptions =
            PaylodOptions(apiKey, baseUrl, webhookSecret, timeoutMs, maxRetries, transport, simulate)
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * Kotlin-friendly factory with named, defaulted parameters.
         *
         * ```
         * PaylodOptions.of(timeoutMs = 10_000, simulate = true)
         * ```
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            apiKey: String? = null,
            baseUrl: String? = null,
            webhookSecret: String? = null,
            timeoutMs: Long = 30_000,
            maxRetries: Int = 2,
            transport: HttpTransport? = null,
            simulate: Boolean = false,
        ): PaylodOptions =
            PaylodOptions(apiKey, baseUrl, webhookSecret, timeoutMs, maxRetries, transport, simulate)
    }
}
