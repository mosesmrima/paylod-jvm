package dev.paylod.internal

/**
 * Scrubs this client's own secrets out of anything that could be logged, thrown, or handed back on
 * a public field.
 *
 * ── Why a whole class for this ────────────────────────────────────────────────────────────
 * `PaylodApiException.message` and `PaylodApiException.body` are the two things every integrator
 * logs wholesale in a catch block. Both were previously built from the RAW response: the message
 * took `body["error"]` verbatim and `body` stored the parsed response as-is. An API that echoes
 * the request back — a validation error quoting the offending headers, a debug envelope, a proxy's
 * error page, a gateway that renders the whole request on a 502 — therefore put the live
 * `Authorization: Bearer mp_live_…` token straight into the exception, and from there into every
 * log sink downstream. The SDK was careful never to PRINT the key itself and then published it on
 * a public field the moment a server echoed it back.
 *
 * A server cannot be trusted not to echo. So nothing derived from a response reaches a caller
 * without passing through here.
 */
/**
 * The credential SHAPES this SDK can recognise without holding the secret itself.
 *
 * Lifted out of [Redactor] because two callers need it and only one of them has a client: a webhook
 * is verified by a static function with no [Redactor] in scope, and a webhook body is exactly as
 * server-controlled as a status body.
 */
internal object CredentialShapes {

    const val MASK = "[redacted]"

    /** `mp_live_…` / `mp_test_…` and long bearer tokens, whoever they belong to. */
    val KEY_SHAPED_RE = Regex("mp_(?:live|test)_[A-Za-z0-9_\\-]{4,}|(?i:bearer)\\s+[A-Za-z0-9._\\-]{12,}")

    /** Does this server-supplied string carry something shaped like a credential? */
    fun looksLikeCredential(value: String?): Boolean =
        value != null && KEY_SHAPED_RE.containsMatchIn(value)

    /** Scrub credential-shaped runs out of a string, preserving `null`. */
    fun scrub(value: String?): String? =
        if (value == null) null else KEY_SHAPED_RE.replace(value, MASK)
}

internal class Redactor(secrets: List<String?>) {

    /**
     * Only substantial secrets are matched. A 3-character "secret" would turn every response into
     * asterisks and destroy the diagnostic value of the message for no security gain.
     */
    private val needles: List<String> = secrets
        .asSequence()
        .filterNotNull()
        .map { it.trim() }
        .filter { it.length >= MIN_SECRET_LENGTH }
        .distinct()
        // Longest first, so a key is not partially masked by a shorter secret that is a substring
        // of it, leaving the remaining characters exposed.
        .sortedByDescending { it.length }
        .toList()

    /** Redact every known secret out of a string. Also scrubs anything SHAPED like a paylod key. */
    fun text(value: String?): String {
        if (value == null) return ""
        var out: String = value
        for (needle in needles) out = out.replace(needle, MASK)
        // A belt-and-braces pass for credentials this client does not itself hold: a key belonging
        // to another environment, or a second key the server quoted back. The message is safe to
        // damage; a leaked key is not.
        return KEY_SHAPED_RE.replace(out, MASK)
    }

    /**
     * Does this server-supplied value carry a credential — ours, or one merely SHAPED like one?
     *
     * ── Why an identifier that echoes a token is REFUSED, not redacted ────────────────────
     * `resultDesc`, `mpesaReceipt`, `paymentId` and `checkoutRequestId` are all server-controlled,
     * and they land on PUBLIC fields of `CollectAck`, `Payment`, `PaymentOutcome` and
     * `WebhookEvent` — which are Kotlin `data class`es, so `toString()` is GENERATED and prints
     * every field. An API that echoes the request back (a validation error quoting the offending
     * headers, a debug envelope, a proxy error page, a gateway rendering the whole request on a
     * 502) therefore put a live `Authorization: Bearer mp_live_…` into an object that every
     * integrator logs wholesale — by default, with no logging mistake required. The error paths
     * were carefully redacted and the SUCCESS paths were not redacted at all.
     *
     * The two field kinds are handled differently ON PURPOSE:
     *
     *   • An IDENTIFIER carrying a credential is not a leak with a valid id inside it; it is not an
     *     identifier at all. A `paymentId` that is a bearer token names no payment, so masking it
     *     would produce a well-formed record pointing at `"[redacted]"` — and the SDK would go on
     *     to bind, poll and reconcile against that. It is refused as INDETERMINATE, which is the
     *     honest reading of a response we cannot make sense of.
     *
     *   • Optional server TEXT (`resultDesc`) has no identity role. It is diagnostic prose, so it
     *     is scrubbed and passed on rather than turning a decodable failure into an unreadable one.
     */
    fun containsCredential(value: String?): Boolean {
        if (value == null) return false
        if (needles.any { value.contains(it) }) return true
        return CredentialShapes.looksLikeCredential(value)
    }

    /** [text], but `null` in gives `null` out — for OPTIONAL fields, where `""` is not the same. */
    fun optionalText(value: String?): String? = if (value == null) null else text(value)

    /**
     * Deep-redact a parsed response body.
     *
     * The recursion is DEPTH-CAPPED rather than trusted. A response body is attacker- or
     * accident-controlled data, so an adversarially nested structure would otherwise be a stack
     * overflow inside an error path — and, worse, a scan that gives up quietly would pass a
     * credential through at depth 9. Beyond the cap the value is replaced, never returned.
     */
    fun body(value: Any?): Any? = redact(value, 0)

    private fun redact(value: Any?, depth: Int): Any? {
        if (depth > MAX_DEPTH) return "[redacted: structure too deeply nested to scan]"
        return when (value) {
            null -> null
            is String -> text(value)
            is Boolean, is Number -> value
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>(value.size)
                // KEYS are redacted too. An echoed request renders headers as keys at least as often
                // as it renders them as values.
                for ((k, v) in value) out[text(k?.toString())] = redact(v, depth + 1)
                out
            }
            is Iterable<*> -> value.map { redact(it, depth + 1) }
            is Array<*> -> value.map { redact(it, depth + 1) }
            // An unknown type reaches a log via its `toString()`, so that is what must be scrubbed.
            else -> text(value.toString())
        }
    }

    private companion object {
        const val MASK = CredentialShapes.MASK
        const val MIN_SECRET_LENGTH = 8
        const val MAX_DEPTH = 8

        /** ONE definition, shared with the webhook path. See [CredentialShapes]. */
        val KEY_SHAPED_RE = CredentialShapes.KEY_SHAPED_RE
    }
}
