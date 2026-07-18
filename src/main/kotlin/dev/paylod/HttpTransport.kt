package dev.paylod

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A single outbound HTTP call, as the SDK sees it. */
data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Long,
) {
    /**
     * Redact secret-bearing headers (the `Authorization: Bearer …` token, idempotency keys) so a
     * stray log line or a wrapped-exception message can never leak the API key. The data-class
     * default `toString()` would print every header value verbatim.
     */
    override fun toString(): String {
        val safeHeaders = headers.entries.joinToString(", ") { (k, v) -> "$k=${redactHeaderValue(k, v)}" }
        return "HttpRequestSpec(method=$method, url=$url, headers={$safeHeaders}, body=$body, timeoutMs=$timeoutMs)"
    }
}

/** The header names whose values are secrets and must never be rendered. */
private val SECRET_HEADERS = setOf("authorization", "idempotency-key", "proxy-authorization", "cookie")

internal fun redactHeaderValue(name: String, value: String): String =
    if (name.lowercase() in SECRET_HEADERS) "[redacted]" else value

/**
 * A header map whose [toString] redacts secret-bearing values.
 *
 * [HttpRequestSpec.toString] alone is not enough: `headers` is a public field, so `spec.headers` is a
 * plain `Map` whose own `toString()` prints `authorization=Bearer mp_live_…` verbatim. That happens
 * for free inside string templates, `Objects.toString`, structured-logging field dumps and most
 * debugger/console output — none of which route through the spec's own `toString`. Wrapping the map
 * moves the redaction to where the rendering actually happens, so lookups still return the REAL value
 * (the transport must be able to send it) while printing never does.
 */
internal class RedactingHeaders(
    private val delegate: Map<String, String>,
) : Map<String, String> by delegate {
    override fun toString(): String =
        delegate.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${redactHeaderValue(k, v)}" }
}

/** A single HTTP response, as the SDK sees it. */
data class HttpResponseSpec(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * The one seam the client talks to the network through. Swap it in tests (the analogue of the Node
 * SDK's injectable `fetch`) with a stub that returns canned responses and records calls — no live
 * network required.
 *
 * An implementation MUST throw [PaylodConnectionException] for transport-level failures (DNS, TLS,
 * socket, timeout) so the client's retry logic can distinguish them from HTTP error responses.
 */
fun interface HttpTransport {
    fun execute(request: HttpRequestSpec): HttpResponseSpec
}

/**
 * The default transport, built on the JDK's [java.net.http.HttpClient] (JDK 11+). Zero extra HTTP
 * dependency.
 */
class JdkHttpTransport private constructor(
    private val client: HttpClient,
) : HttpTransport {

    init {
        // The no-redirect guarantee belongs to EVERY instance, not just the one the default
        // constructor builds. This class is public JVM bytecode: a caller who reached the
        // client-taking constructor could otherwise hand in a `Redirect.NORMAL` client and have the
        // `Authorization: Bearer mp_live_…` header replayed to whatever host a 3xx `Location` names —
        // a full credential handover with no code change anywhere near this file. The primary
        // constructor is `private` so the only ways in are the vetted factories below, and this check
        // makes the guarantee hold even for them.
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw PaylodConfigException(
                "JdkHttpTransport requires an HttpClient built with followRedirects(Redirect.NEVER) " +
                    "(got ${client.followRedirects()}). Following a redirect replays the " +
                    "Authorization: Bearer header — your API key — to the redirect target's host. The " +
                    "paylod API never legitimately redirects.",
            )
        }
    }

    constructor() : this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER auto-follow a redirect. Redirect.NORMAL would replay the `Authorization: Bearer`
            // header to a cross-origin 3xx target, leaking the API key. The API never legitimately
            // redirects; treat a 3xx as a response and let the client refuse it.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    )

    internal companion object {
        /** Test seam. Goes through the same `Redirect.NEVER` gate as every other instance. */
        internal fun withClient(client: HttpClient): JdkHttpTransport = JdkHttpTransport(client)
    }

    override fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(Duration.ofMillis(request.timeoutMs))

        // Header assembly runs INSIDE a guard. `HttpRequest.Builder.header` performs the JDK's own
        // field-value validation, and the IllegalArgumentException it raises embeds the whole
        // offending value — for `authorization`, that is the complete `Bearer mp_live_…` token,
        // handed straight to whatever logs the exception. Re-throw with the header NAME only.
        for ((k, v) in request.headers) {
            try {
                builder.header(k, v)
            } catch (e: IllegalArgumentException) {
                throw PaylodConfigException(
                    "The \"$k\" header could not be built: it contains a character that is not legal in " +
                        "an HTTP field value. (The value is deliberately omitted — this message ends up " +
                        "in logs, and for a secret-bearing header it would be the secret itself.)",
                )
            }
        }

        val publisher = if (request.body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(request.body)
        }
        builder.method(request.method, publisher)

        val response: HttpResponse<String> = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            // A thread interrupt is a deliberate cancellation, NOT a transient network blip. Restore
            // the interrupt flag and abort without retrying — retrying would ignore the cancellation.
            Thread.currentThread().interrupt()
            throw PaylodInterruptedException("Request to ${request.url} was interrupted.", e)
        } catch (e: Exception) {
            throw PaylodConnectionException(
                "Could not reach paylod at ${request.url}: ${e.message}",
                e,
            )
        }

        val headers = HashMap<String, String>()
        response.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) headers[k.lowercase()] = v[0] }
        return HttpResponseSpec(response.statusCode(), headers, response.body() ?: "")
    }
}
