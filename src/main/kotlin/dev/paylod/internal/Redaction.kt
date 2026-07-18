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
        const val MASK = "[redacted]"
        const val MIN_SECRET_LENGTH = 8
        const val MAX_DEPTH = 8

        /** `mp_live_…` / `mp_test_…` and long bearer tokens, whoever they belong to. */
        val KEY_SHAPED_RE = Regex("mp_(?:live|test)_[A-Za-z0-9_\\-]{4,}|(?i:bearer)\\s+[A-Za-z0-9._\\-]{12,}")
    }
}
