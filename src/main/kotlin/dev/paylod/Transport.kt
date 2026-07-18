package dev.paylod

import dev.paylod.internal.Redactor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * THE credentialed transport.
 *
 * ── Why this class exists ─────────────────────────────────────────────────────────────────
 * The API key is a BEARER credential: whoever receives it can move money. The previous design
 * handed that credential to an arbitrary, caller-supplied [HttpTransport] as an ordinary entry in
 * a public header map, and then tried to police the RESULT — the SDK built its own client with
 * `Redirect.NEVER` and checked responses for a 3xx. Neither control survives contact with an
 * injected transport: it receives the header, can follow a cross-origin 302 itself, and can hand
 * back a perfectly ordinary `200` from anywhere. By then the credential has already been replayed.
 * Checking after following is too late.
 *
 * So the credential no longer crosses a replaceable boundary at all:
 *
 *   1. THE KEY LIVES HERE, in a private field. Callers pass a method, a path and a body. They
 *      never see the key, never construct headers, never supply a URL or a redirect mode, and
 *      therefore have no way to address it anywhere.
 *   2. THE CREDENTIALED DISPATCH IS SDK-OWNED. It is the JDK's own [HttpClient], built here with
 *      [HttpClient.Redirect.NEVER]. It can be replaced ONLY through the explicit, test-only
 *      [PaylodOptions.Builder.allowCustomTransport] opt-in — which is refused for `mp_live_` keys,
 *      the same posture `allowInsecureBaseUrl` already had — and a custom transport is handed a
 *      [HttpRequestSpec] with NO credential in it at all.
 *   3. THE ORIGIN IS PINNED, PER DISPATCH. Not once at construction: the origin of the URL actually
 *      being requested is recomputed and compared on every single call.
 *   4. REDIRECTS ARE REFUSED, not followed and then judged — and the refusal is LAYERED, so an
 *      implementation that lies about its redirect handling is still caught. See
 *      [assertNotRedirected].
 *
 * Points 3 and 4 run INSIDE this class, on every dispatch, with no way for a caller to opt out.
 * That is the difference between a protection and a suggestion.
 */
internal class Transport(
    private val apiKey: String,
    /** Already normalised (no trailing slash) and already passed the base-URL assertion. */
    private val baseUrl: String,
    /** The gated test seam, or `null` for the SDK-owned JDK dispatch. */
    private val custom: HttpTransport?,
    private val redact: Redactor,
) {

    /** The one origin this client may ever address, derived once and then immutable. */
    val origin: String = originOf(baseUrl)
        ?: throw PaylodConfigException("baseUrl is not a valid absolute URL: \"$baseUrl\".")

    init {
        // Belt and braces: `Paylod`'s constructor refuses this combination before a Transport is
        // ever built. The check is repeated here so the class is safe on its own terms and cannot
        // be misused by a future caller inside this module. A transport that would hand a
        // production key's traffic to caller-supplied code under ANY circumstance is not a
        // transport worth having.
        if (custom != null && apiKey.startsWith(LIVE_PREFIX)) {
            throw PaylodConfigException(
                "A custom HttpTransport may never be used with an mp_live_ key. The API key is a " +
                    "bearer credential and this is a test-only seam; a live charge path must run " +
                    "through the SDK's own dispatch, which pins the origin and never follows a " +
                    "redirect.",
            )
        }
    }

    /**
     * The SDK's own HTTP client. Built lazily so a stubbed test never opens one, and built ONCE so
     * the `Redirect.NEVER` guarantee is a property of this object rather than of a call site.
     */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER auto-follow. `Redirect.NORMAL` would replay the `Authorization: Bearer` header
            // to a cross-origin 3xx target, leaking the API key. The paylod API never legitimately
            // redirects; a 3xx is a misconfiguration or an attack, and it is terminal.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /**
     * Dispatch one request.
     *
     * The caller supplies a method, a path and a body. It does NOT supply headers, a URL, a
     * redirect mode, or the credential — all four are produced here, which is precisely what makes
     * the guarantees unconditional.
     */
    fun send(request: TransportRequest): TransportResponse {
        val url = "$baseUrl${request.path}"
        assertOnOrigin(url, "the request URL", request.idempotencyKey)

        // The headers a custom transport is allowed to see. No credential, ever.
        val publicHeaders = LinkedHashMap<String, String>()
        publicHeaders["accept"] = "application/json"
        if (request.body != null) publicHeaders["content-type"] = "application/json"
        if (request.idempotencyKey != null) publicHeaders["idempotency-key"] = request.idempotencyKey

        val response = if (custom != null) {
            custom.execute(
                HttpRequestSpec(
                    method = request.method,
                    url = url,
                    headers = RedactingHeaders(publicHeaders),
                    body = request.body,
                    timeoutMs = request.timeoutMs,
                ),
            )
        } else {
            dispatch(request, url, publicHeaders)
        }

        assertNotRedirected(response, url, request.idempotencyKey)
        // The body is bounded BEFORE anyone parses it. `dispatch` already refuses to accumulate more
        // than MAX_RESPONSE_BYTES, but a custom transport hands us a `String` it built itself, so the
        // size check is repeated here for that path — and the DEPTH check has to live here either
        // way, because it is a property of the document rather than of the byte count.
        assertBoundedBody(response.body, request.idempotencyKey)
        return TransportResponse(response.status, response.headers, response.body)
    }

    /**
     * Refuse a response we are not willing to parse, BEFORE anything recurses through it.
     *
     * Two independent bounds, for two independent ways the old code died AFTER the request had been
     * dispatched:
     *
     *   • SIZE. `BodyHandlers.ofString()` accumulates whatever arrives, without limit. A hostile or
     *     broken endpoint answering a `POST /collect` with a multi-gigabyte body produced an
     *     `OutOfMemoryError` — not a [PaylodException], so it escaped the block that attaches the
     *     effective `Idempotency-Key`, and the caller was holding a possibly-live charge with no key
     *     to settle it under. paylod's real responses are a few hundred bytes.
     *
     *   • DEPTH. The JSON reader is a recursive-descent parser, so nesting depth is stack depth.
     *     `[[[[[…` a hundred thousand deep is a `StackOverflowError` from inside the parser, with
     *     exactly the same consequence. The depth is measured HERE, by a flat scan that cannot
     *     itself overflow, so the parser is never entered with a document that would blow the stack.
     *
     * Both become a [PaylodIndeterminateException] carrying the key: the request reached the server,
     * so the money state is genuinely unknown, and re-dispatching is the one thing that must not
     * happen.
     */
    private fun assertBoundedBody(body: String, idempotencyKey: String?) {
        // Measured in UTF-16 units rather than encoded bytes on purpose: it is an upper bound on the
        // memory already committed, it needs no second encoding pass, and it can only ever refuse
        // EARLIER than a byte count would.
        if (body.length > MAX_RESPONSE_CHARS) {
            throw PaylodIndeterminateException(
                redact.text(
                    "paylod's response exceeded the ${MAX_RESPONSE_CHARS}-character limit this SDK " +
                        "will parse (got ${body.length}). The request WAS dispatched, so the money " +
                        "state is INDETERMINATE: read the payment with this idempotencyKey rather " +
                        "than retrying, and never mint a fresh key.",
                ),
                idempotencyKey,
            )
        }

        val depth = maxJsonDepth(body)
        if (depth > MAX_JSON_DEPTH) {
            throw PaylodIndeterminateException(
                redact.text(
                    "paylod's response nests JSON $depth levels deep, past the $MAX_JSON_DEPTH-level " +
                        "limit this SDK will recurse through — parsing it would overflow the stack. " +
                        "The request WAS dispatched, so the money state is INDETERMINATE: read the " +
                        "payment with this idempotencyKey rather than retrying.",
                ),
                idempotencyKey,
            )
        }
    }

    /** The SDK-owned, credentialed dispatch. The only place the API key is ever written down. */
    private fun dispatch(
        request: TransportRequest,
        url: String,
        publicHeaders: Map<String, String>,
    ): HttpResponseSpec {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(request.timeoutMs))

        // Header assembly runs INSIDE a guard. `HttpRequest.Builder.header` performs the JDK's own
        // field-value validation, and the IllegalArgumentException it raises embeds the whole
        // offending value — for `authorization`, that is the complete `Bearer mp_live_…` token,
        // handed straight to whatever logs the exception. Re-throw with the header NAME only.
        val all = LinkedHashMap<String, String>(publicHeaders)
        // THE credential. Constructed here, from a private field, on every request.
        all["authorization"] = "Bearer $apiKey"
        for ((k, v) in all) {
            try {
                builder.header(k, v)
            } catch (e: IllegalArgumentException) {
                throw PaylodConfigException(
                    "The \"$k\" header could not be built: it contains a character that is not legal " +
                        "in an HTTP field value. (The value is deliberately omitted — this message " +
                        "ends up in logs, and for a secret-bearing header it would be the secret.)",
                )
            }
        }

        val publisher = if (request.body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(request.body)
        }
        builder.method(request.method, publisher)

        // `BodyHandlers.ofInputStream()` rather than `ofString()`: `ofString` accumulates the ENTIRE
        // body into memory with no ceiling, so the size limit could only ever be checked after the
        // allocation that is the actual hazard. Streaming lets the limit be enforced DURING the read,
        // so an oversized body is abandoned at the boundary instead of being materialised first.
        val response: HttpResponse<java.io.InputStream> = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            // A thread interrupt is a deliberate cancellation, NOT a transient network blip. Restore
            // the interrupt flag and abort without retrying — retrying would ignore the cancellation.
            Thread.currentThread().interrupt()
            throw PaylodInterruptedException(redact.text("Request to $url was interrupted."), e)
        } catch (e: Exception) {
            // The lower-level exception is NOT attached as a cause: a JDK/TLS/proxy exception can
            // embed the request line and headers, which is exactly what must not reach a log sink.
            throw PaylodConnectionException(redact.text("Could not reach paylod at $url: ${e.message}"))
        }

        val headers = HashMap<String, String>()
        response.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) headers[k.lowercase()] = v[0] }

        // A declared `Content-Length` past the limit is refused without reading a single byte of the
        // body. This is only a fast path — the streaming read below is the real enforcement, because
        // a chunked response declares no length and a hostile one can simply lie.
        val declared = response.headers().firstValueAsLong("content-length")
        if (declared.isPresent && declared.asLong > MAX_RESPONSE_BYTES) {
            throw PaylodIndeterminateException(
                redact.text(
                    "paylod declared a ${declared.asLong}-byte response, past the " +
                        "${MAX_RESPONSE_BYTES}-byte limit this SDK will read. The request WAS " +
                        "dispatched, so the money state is INDETERMINATE: read the payment with this " +
                        "idempotencyKey rather than retrying.",
                ),
                request.idempotencyKey,
            )
        }

        val body = readBounded(response.body(), request.idempotencyKey, url)
        return HttpResponseSpec(
            status = response.statusCode(),
            headers = headers,
            body = body,
            url = response.uri()?.toString(),
            // Present exactly when the client followed at least one redirect to get here. With
            // `Redirect.NEVER` it never is — which is the point: if it ever becomes true, something
            // has replaced the guarantee and the caller needs to hear about it.
            redirected = response.previousResponse().isPresent,
        )
    }

    /**
     * Read at most [MAX_RESPONSE_BYTES] from the response stream, refusing the moment it is exceeded.
     *
     * The buffer grows from a small initial size rather than being pre-sized to the limit, so a
     * normal few-hundred-byte response costs a few hundred bytes — the ceiling bounds the WORST
     * case, it does not become the allocation for the ordinary one.
     */
    private fun readBounded(stream: java.io.InputStream, idempotencyKey: String?, url: String): String {
        val out = java.io.ByteArrayOutputStream(INITIAL_BODY_BUFFER)
        val chunk = ByteArray(8 * 1024)
        try {
            stream.use { s ->
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    if (out.size().toLong() + n > MAX_RESPONSE_BYTES) {
                        throw PaylodIndeterminateException(
                            redact.text(
                                "paylod's response exceeded the ${MAX_RESPONSE_BYTES}-byte limit " +
                                    "this SDK will read, so it was abandoned rather than " +
                                    "accumulated. The request WAS dispatched, so the money state is " +
                                    "INDETERMINATE: read the payment with this idempotencyKey " +
                                    "rather than retrying, and never mint a fresh key.",
                            ),
                            idempotencyKey,
                        )
                    }
                    out.write(chunk, 0, n)
                }
            }
        } catch (e: PaylodException) {
            throw e
        } catch (e: Exception) {
            // A genuine mid-body network failure. This used to happen INSIDE `client.send`, which
            // surfaced it as a retryable connection error, and that classification is preserved:
            // an interrupted read really is the transient case the retry loop exists for.
            throw PaylodConnectionException(
                redact.text("Could not read paylod's response from $url: ${e.message}"),
            )
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8)
    }

    /**
     * Refuse anything that IS, or CAME FROM, a redirect — in three independent ways, because the
     * dispatch implementation is part of what we are defending against and cannot be trusted to
     * report honestly:
     *
     *   1. a 3xx status — a redirect that was surfaced rather than followed.
     *   2. [HttpResponseSpec.redirected] — the implementation FOLLOWED one anyway. The credential
     *      has already been replayed, so this is a detection, not a prevention. It is here so the
     *      failure is loud and so the caller learns their key is burned. Prevention is that a live
     *      key can never reach a custom transport in the first place.
     *   3. [HttpResponseSpec.url] off-origin — the final response came from somewhere we did not
     *      address, which catches an implementation that follows a redirect while lying about
     *      having done so.
     *
     * None of these is retryable: a redirect is a configuration error or an attack, never a blip.
     */
    private fun assertNotRedirected(response: HttpResponseSpec, requested: String, idempotencyKey: String?) {
        if (response.status in 300..399) {
            throw PaylodApiException(
                redact.text(
                    "paylod responded ${response.status} (a redirect) to $requested. The paylod API " +
                        "never redirects, and a redirect is never followed: doing so would replay " +
                        "your API key to the redirect target's host. Check baseUrl and any proxy in " +
                        "front of it.",
                ),
                response.status,
                null,
                null,
                indeterminate = true,
            )
        }

        // A DETECTED CREDENTIAL COMPROMISE IS TERMINAL, NEVER RETRIED.
        //
        // This is a `PaylodSecurityException` rather than a `PaylodConnectionException` for one
        // reason, and it is the whole reason the type exists: the client's retry loop catches
        // `PaylodConnectionException` and RE-SENDS. So this detection — "your bearer token has
        // already been replayed to another host, rotate it now" — used to be followed immediately
        // by the SDK replaying the very same credentialed request, up to `maxRetries` more times.
        // The loop has no branch that catches this type, so it propagates on the first occurrence.
        if (response.redirected) {
            throw PaylodSecurityException(
                redact.text(
                    "The HTTP transport FOLLOWED a redirect even though this SDK dispatches with " +
                        "Redirect.NEVER. Your Authorization header may ALREADY have been replayed to " +
                        "another host — treat this API key as COMPROMISED and rotate it now. This " +
                        "request will NOT be retried: retrying a request whose credential we believe " +
                        "has leaked would hand it over again. This is only reachable through the " +
                        "test-only custom-transport seam, which is why that seam is refused for " +
                        "mp_live_ keys.",
                ),
                idempotencyKey,
            )
        }

        // A null URL is "not reported" (the normal case for a stub) and is not evidence of anything.
        val finalUrl = response.url
        if (finalUrl != null) assertOnOrigin(finalUrl, "the responding URL", idempotencyKey)
    }

    /**
     * Recomputed and compared on EVERY dispatch, so no path can walk a request off-origin.
     *
     * Also terminal, for the same reason as the redirect detection: an off-origin RESPONSE means a
     * redirect was followed and the credential is already gone, and an off-origin REQUEST is a
     * configuration error or an attack. Neither is a transient blip, and neither gets a second
     * dispatch.
     */
    private fun assertOnOrigin(candidate: String, what: String, idempotencyKey: String?) {
        val candidateOrigin = originOf(candidate)
            ?: throw PaylodSecurityException(
                redact.text("$what is not a valid absolute URL ($candidate)."),
                idempotencyKey,
            )
        if (candidateOrigin != origin) {
            throw PaylodSecurityException(
                redact.text(
                    "Refusing a request that is not addressed to the pinned paylod origin: $what " +
                        "resolves to \"$candidateOrigin\", but this client is pinned to \"$origin\". " +
                        "Your API key is a bearer credential and is sent on every request, so it may " +
                        "only ever be addressed to the origin it was configured for. If this happened " +
                        "on a RESPONSE, a redirect was followed and the key must be rotated. This " +
                        "request is NOT retried.",
                ),
                idempotencyKey,
            )
        }
    }

    internal companion object {
        const val LIVE_PREFIX = "mp_live_"

        /**
         * The most a paylod response may be. Real ones are a few hundred bytes — an ack, a payment
         * record, or an error envelope. 1 MiB is roughly three orders of magnitude of headroom and
         * still small enough that the worst case is harmless.
         */
        const val MAX_RESPONSE_BYTES = 1L shl 20

        /** The same ceiling expressed in chars, for the already-materialised custom-transport path. */
        const val MAX_RESPONSE_CHARS = 1 shl 20

        /** Start small; the ceiling bounds the worst case, it is not the ordinary allocation. */
        const val INITIAL_BODY_BUFFER = 8 * 1024

        /**
         * The deepest JSON this SDK will recurse through. paylod's shapes are flat — the deepest
         * legitimate nesting is a caller's own `metadata` object echoed back, and 64 levels is far
         * past anything a payment carries.
         */
        const val MAX_JSON_DEPTH = 64

        /**
         * The maximum `{`/`[` nesting depth in [text], measured by a FLAT scan.
         *
         * Deliberately iterative: the entire point is to learn the depth of a document WITHOUT
         * recursing to the bottom of it, since recursing is the thing that overflows. Brackets
         * inside string literals do not nest, so the scan tracks string and escape state; it is a
         * depth counter, not a parser, and it never needs to be one — over-counting is impossible
         * and under-counting only for input the real parser will reject anyway.
         */
        fun maxJsonDepth(text: String): Int {
            var depth = 0
            var max = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > max) max = depth
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
            return max
        }

        /** `scheme://host[:port]`, lowercased, or `null` when the URL is not usable. */
        fun originOf(url: String): String? {
            val parsed = try {
                URI(url)
            } catch (e: Exception) {
                return null
            }
            val scheme = parsed.scheme?.lowercase() ?: return null
            val host = parsed.host?.lowercase() ?: return null
            if (host.isEmpty()) return null
            val port = parsed.port
            return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
        }
    }
}

/** What the client asks the transport to do. No headers, no URL, no credential. */
internal data class TransportRequest(
    val method: String,
    /** Appended to the pinned base URL. Never an absolute URL. */
    val path: String,
    val body: String?,
    val idempotencyKey: String?,
    val timeoutMs: Long,
)

/** What a dispatch produced. Deliberately inert data. */
internal data class TransportResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)
