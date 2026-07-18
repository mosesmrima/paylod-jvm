package dev.paylod

/**
 * Escape hatches for [Paylod] construction. You should almost never need any of these — the base
 * URL is baked in and identical for every customer.
 *
 * Kotlin callers can use named arguments; Java callers should use [PaylodOptions.builder].
 *
 * ── Secrets are write-only ────────────────────────────────────────────────────────────────
 * `apiKey`, `webhookSecret` and `transport` are settable but NOT readable. They were previously
 * `@JvmField` public fields, which meant the live credential sat on an object that gets passed
 * around, held in DI containers, printed by `toString()` in a debugger, and serialised by
 * well-meaning config dumpers. An option object is a place to PUT a secret, not a place to read one
 * back from: the only consumer that ever needs the value is [Paylod]'s constructor, and it reads
 * it through a module-internal accessor. There is no public getter to leak.
 */
class PaylodOptions private constructor(
    private val apiKeyValue: String?,
    /** Defaults to `PAYLOD_BASE_URL` or `https://paylod.dev/functions/v1`. */
    @JvmField val baseUrl: String?,
    private val webhookSecretValue: String?,
    /** Per-HTTP-request timeout, ms. Default 30_000. */
    @JvmField val timeoutMs: Long,
    /** Retries for *idempotent/transient* failures (network, 5xx, 429). Default 2. */
    @JvmField val maxRetries: Int,
    private val transportValue: HttpTransport?,
    /**
     * Simulator mode — for tests. `collect()` / `collectAndWait()` create a *simulated* payment
     * instead of ringing a phone. Requires a `mp_test_` key; the constructor throws
     * [PaylodSandboxOnlyException] otherwise, so it can never point at production.
     */
    @JvmField val simulate: Boolean,
    /**
     * TEST-ONLY escape hatch: permit a plaintext `http://` [baseUrl] when — and only when — it is a
     * loopback host (`localhost` / `127.0.0.1` / `::1`). HTTPS is otherwise enforced so the API key
     * is never sent in the clear. A live (`mp_live_`) key is NEVER allowed over plaintext.
     */
    @JvmField val allowInsecureBaseUrl: Boolean,
    /**
     * TEST-ONLY opt-in required before a custom [HttpTransport] is used at all.
     *
     * A custom transport is a test seam, not a production extension point, and it must be asked for
     * deliberately rather than acquired by wiring an object into a builder. Setting a transport
     * without this flag is a hard [PaylodConfigException] rather than a silent downgrade, and the
     * combination is refused outright for `mp_live_` keys — enforced both in [Paylod]'s constructor
     * and again inside the transport itself, so the guarantee does not depend on one call site.
     */
    @JvmField val allowCustomTransport: Boolean,
) {
    /** Module-internal reads. There is deliberately no public getter for any of these. */
    internal fun apiKeyOrNull(): String? = apiKeyValue
    internal fun webhookSecretOrNull(): String? = webhookSecretValue
    internal fun transportOrNull(): HttpTransport? = transportValue

    /** Never renders a secret, whatever a debugger or a config dumper decides to call. */
    override fun toString(): String =
        "PaylodOptions(apiKey=${if (apiKeyValue == null) "null" else "[redacted]"}, baseUrl=$baseUrl, " +
            "webhookSecret=${if (webhookSecretValue == null) "null" else "[redacted]"}, " +
            "timeoutMs=$timeoutMs, maxRetries=$maxRetries, " +
            "transport=${if (transportValue == null) "null" else "[custom]"}, simulate=$simulate, " +
            "allowInsecureBaseUrl=$allowInsecureBaseUrl, allowCustomTransport=$allowCustomTransport)"

    class Builder {
        private var apiKey: String? = null
        private var baseUrl: String? = null
        private var webhookSecret: String? = null
        private var timeoutMs: Long = 30_000
        private var maxRetries: Int = 2
        private var transport: HttpTransport? = null
        private var simulate: Boolean = false
        private var allowInsecureBaseUrl: Boolean = false
        private var allowCustomTransport: Boolean = false

        fun apiKey(value: String?) = apply { this.apiKey = value }
        fun baseUrl(value: String?) = apply { this.baseUrl = value }
        fun webhookSecret(value: String?) = apply { this.webhookSecret = value }
        fun timeoutMs(value: Long) = apply { this.timeoutMs = value }
        fun maxRetries(value: Int) = apply { this.maxRetries = value }

        /** TEST ONLY. Also requires [allowCustomTransport], and never works with an `mp_live_` key. */
        fun transport(value: HttpTransport?) = apply { this.transport = value }
        fun simulate(value: Boolean) = apply { this.simulate = value }
        fun allowInsecureBaseUrl(value: Boolean) = apply { this.allowInsecureBaseUrl = value }
        fun allowCustomTransport(value: Boolean) = apply { this.allowCustomTransport = value }

        fun build(): PaylodOptions = PaylodOptions(
            apiKey, baseUrl, webhookSecret, timeoutMs, maxRetries, transport, simulate,
            allowInsecureBaseUrl, allowCustomTransport,
        )
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
            allowInsecureBaseUrl: Boolean = false,
            allowCustomTransport: Boolean = false,
        ): PaylodOptions = PaylodOptions(
            apiKey, baseUrl, webhookSecret, timeoutMs, maxRetries, transport, simulate,
            allowInsecureBaseUrl, allowCustomTransport,
        )
    }
}
