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
        val safeHeaders = headers.entries.joinToString(", ") { (k, v) ->
            val lower = k.lowercase()
            val redacted = if (lower == "authorization" || lower == "idempotency-key") "[redacted]" else v
            "$k=$redacted"
        }
        return "HttpRequestSpec(method=$method, url=$url, headers={$safeHeaders}, body=$body, timeoutMs=$timeoutMs)"
    }
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
class JdkHttpTransport internal constructor(
    private val client: HttpClient,
) : HttpTransport {

    constructor() : this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER auto-follow a redirect. Redirect.NORMAL would replay the `Authorization: Bearer`
            // header to a cross-origin 3xx target, leaking the API key. The API never legitimately
            // redirects; treat a 3xx as a response and let the client refuse it.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    )

    override fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(request.url))
            .timeout(Duration.ofMillis(request.timeoutMs))

        for ((k, v) in request.headers) builder.header(k, v)

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
