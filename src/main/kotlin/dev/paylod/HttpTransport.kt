package dev.paylod

/**
 * A single outbound HTTP call, as a CUSTOM transport sees it.
 *
 * ── What is deliberately NOT here ─────────────────────────────────────────────────────────
 * The API key. There is no `Authorization` header in [headers], no credential field, and no way to
 * derive one from this object.
 *
 * That is the whole point of the redesign. Previously this type carried
 * `authorization = "Bearer mp_live_…"` as an ordinary entry in a public `Map`, handed to arbitrary
 * caller-supplied code on every request. The SDK then tried to police the RESULT — it set
 * `Redirect.NEVER` on its own client and inspected the response for a 3xx. That is not a control.
 * A custom transport is free to receive the header, follow a cross-origin 302 itself, and hand
 * back a perfectly ordinary `200`. By the time the SDK inspects that response the credential has
 * ALREADY been replayed to another host. Checking after following is too late.
 *
 * So the credential no longer crosses the boundary at all. Credentialed dispatch happens inside
 * the SDK-owned transport, which builds its own URL and its own headers from a private field. A
 * custom [HttpTransport] is a TEST SEAM: it sees the method, the URL, the non-secret headers and
 * the body, which is everything a stub needs to return a canned response, and nothing an
 * exfiltrator could use.
 */
data class HttpRequestSpec(
    val method: String,
    val url: String,
    /** Non-secret headers only — `accept`, `content-type`, `idempotency-key`. Never the credential. */
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Long,
) {
    /**
     * Redact the headers whose values are sensitive (an idempotency key correlates a customer's
     * charge) so a stray log line or a wrapped-exception message never renders one. The data-class
     * default `toString()` would print every header value verbatim.
     */
    override fun toString(): String {
        val safeHeaders = headers.entries.joinToString(", ") { (k, v) -> "$k=${redactHeaderValue(k, v)}" }
        return "HttpRequestSpec(method=$method, url=$url, headers={$safeHeaders}, body=$body, timeoutMs=$timeoutMs)"
    }
}

/**
 * Header names whose values must never be rendered.
 *
 * `authorization` stays on this list even though the SDK no longer puts a credential in a
 * [HttpRequestSpec]: the list is a rendering rule, and a future header of that name — or one a
 * caller adds to a map of their own — must still be masked.
 */
private val SECRET_HEADERS = setOf("authorization", "idempotency-key", "proxy-authorization", "cookie")

internal fun redactHeaderValue(name: String, value: String): String =
    if (name.lowercase() in SECRET_HEADERS) "[redacted]" else value

/**
 * A header map whose [toString] redacts sensitive values.
 *
 * [HttpRequestSpec.toString] alone is not enough: `headers` is a public field, so `spec.headers` is
 * a plain `Map` whose own `toString()` prints every value verbatim. That happens for free inside
 * string templates, `Objects.toString`, structured-logging field dumps and most debugger/console
 * output — none of which route through the spec's own `toString`. Wrapping the map moves the
 * redaction to where the rendering actually happens, so lookups still return the REAL value while
 * printing never does.
 */
internal class RedactingHeaders(
    private val delegate: Map<String, String>,
) : Map<String, String> by delegate {
    override fun toString(): String =
        delegate.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${redactHeaderValue(k, v)}" }
}

/** A single HTTP response, as the SDK sees it. */
data class HttpResponseSpec @JvmOverloads constructor(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    /**
     * The URL the response actually came from, when the implementation knows it.
     *
     * The SDK compares this against the origin it is pinned to. A transport that followed a
     * redirect — despite being told not to — reports a final URL on another host here, and the SDK
     * refuses the response and tells the caller to rotate the key. `null` means "not reported",
     * which is the normal case for a stub and is not evidence of anything.
     */
    val url: String? = null,
    /**
     * `true` if the implementation followed one or more redirects to produce this response.
     *
     * This is a DETECTION, not a prevention: by the time it is true, whatever the implementation
     * sent has already reached another host. It exists so the failure is loud rather than silent.
     */
    val redirected: Boolean = false,
)

/**
 * A custom HTTP transport — **a gated, test-only seam**, not a production extension point.
 *
 * Swap it in tests with a stub that returns canned responses and records calls; no live network
 * required. It is NOT a place to add proxying, instrumentation or retries: the SDK refuses to use
 * one unless you opt in explicitly with
 * [PaylodOptions.Builder.allowCustomTransport], and refuses outright with an `mp_live_` key — a
 * production credential must never be within reach of caller-supplied code, even indirectly.
 *
 * An implementation MUST throw [PaylodConnectionException] for transport-level failures (DNS, TLS,
 * socket, timeout) so the client's retry logic can distinguish them from HTTP error responses.
 */
fun interface HttpTransport {
    fun execute(request: HttpRequestSpec): HttpResponseSpec
}
