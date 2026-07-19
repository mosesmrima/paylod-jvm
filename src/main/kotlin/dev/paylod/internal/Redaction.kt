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

    /**
     * Every credential SHAPE this SDK can recognise without holding the secret.
     *
     * `whsec_` and `sk_` were absent, and their absence was not theoretical: the webhook SIGNING
     * SECRET is spelled `whsec_…`, so the one credential most likely to be echoed back by a
     * misconfigured webhook emitter was the one shape this scrubber could not see. `sk_` is the
     * conventional spelling for a secret key across the payment ecosystem, and a body that quotes
     * one is a body we must not print either.
     */
    val KEY_SHAPED_RE = Regex(
        "mp_(?:live|test)_[A-Za-z0-9_\\-]{4,}" +
            "|whsec_[A-Za-z0-9_\\-]{4,}" +
            "|sk_[A-Za-z0-9_\\-]{4,}" +
            "|(?i:bearer)\\s+[A-Za-z0-9._\\-]{12,}",
    )

    /** Does this server-supplied string carry something shaped like a credential? */
    fun looksLikeCredential(value: String?): Boolean =
        value != null && KEY_SHAPED_RE.containsMatchIn(value)

    /**
     * Every marker that means "a sanitizer has already been here".
     *
     * ── Why a sanitizer's OUTPUT must never satisfy an evidence check ─────────────────────
     * This requirement exists because of a defect that shipped: redacting a credential echoed into
     * `mpesaReceipt` rewrote the field to `[redacted]`, and the evidence test at the time was
     * "is the receipt non-blank?". `[redacted]` is non-blank. So the act of HIDING a credential
     * MANUFACTURED proof of payment — the safety machinery became the attack. A `success` claim
     * beside that receipt resolved to PAID.
     *
     * The lesson generalises past our own mask: any placeholder an intermediary substitutes
     * (`[redacted]`, `***`, `<hidden>`, `REDACTED`, `[FILTERED]`) is a statement that the real
     * value was REMOVED, which is the opposite of evidence. So no sanitizer output may satisfy any
     * evidence, identifier or correlation check anywhere in this SDK — receipt, paymentId,
     * checkoutRequestId, payment id, idempotency key.
     *
     * Matched case-insensitively and as a SUBSTRING: a partially-masked value is just as much a
     * value we do not have.
     */
    private val SANITIZED_RE = Regex(
        "\\[redacted\\b|\\bredacted\\b|\\[filtered\\b|\\bfiltered\\b|\\[masked\\b|\\bmasked\\b" +
            "|\\[hidden\\b|<hidden>|\\[removed\\b|\\*{3,}|•{3,}|x{6,}",
        RegexOption.IGNORE_CASE,
    )

    /** Has a sanitizer already replaced (any part of) this value? See [SANITIZED_RE]. */
    fun looksSanitized(value: String?): Boolean =
        value != null && SANITIZED_RE.containsMatchIn(value)

    /** Scrub credential-shaped runs out of a string, preserving `null`. */
    fun scrub(value: String?): String? =
        if (value == null) null else KEY_SHAPED_RE.replace(value, MASK)
}

internal class Redactor(secrets: List<String?>) {

    /**
     * EVERY nonempty configured secret is a needle.
     *
     * This used to drop anything shorter than eight characters, justified as protecting the
     * diagnostic value of a message from a "secret" so short it appeared everywhere. The
     * justification assumed a minimum length that is enforced NOWHERE: neither the API-key
     * configuration nor the webhook-secret parameter has a length floor, so a caller who configures
     * a seven-character signing secret — a test fixture, a truncated environment variable, a
     * placeholder that reached production — got a redactor that silently declined to redact the one
     * value it was constructed to hide. A filter whose precondition is unenforced is not a filter,
     * it is a hole with a comment over it.
     *
     * The diagnostic concern was real but is the wrong trade: a noisy message is an inconvenience,
     * a printed signing secret is a forgeable webhook. Short secrets are now redacted like any
     * other, and the length question is left to whoever chooses the secret.
     */
    private val needles: List<String> = secrets
        .asSequence()
        .filterNotNull()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
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

    internal companion object {
        const val MASK = CredentialShapes.MASK

        /**
         * The redaction traversal bound is PINNED TO THE PARSER'S bound, not chosen independently.
         *
         * It was 8 while [Json.MAX_DEPTH] — the depth to which a hostile response is actually
         * parsed — was 64. Every structure between depth 9 and 64 therefore parsed successfully and
         * then hit a scanner that gave up on it. This one failed CLOSED (the subtree is replaced by
         * a placeholder rather than returned), so it was a diagnostic loss rather than a leak — but
         * it was a loss on exactly the bodies most worth reading, and the next person to make the
         * recursion return the value instead of the placeholder would have turned it into a leak
         * with no test objecting.
         *
         * Deriving it from the parser bound means the two CANNOT drift: there is no second number
         * to forget to update. [NinthRoundTest] additionally asserts the invariant directly, so the
         * relationship survives someone replacing this expression with a literal.
         */
        const val MAX_DEPTH = Json.MAX_DEPTH

        /**
         * The longest server-controlled run any single diagnostic will interpolate.
         *
         * A message is a string that gets logged, and a field the server controls is a field the
         * server can make a megabyte long. Redaction alone does not bound it — a hostile value that
         * contains no credential is passed through verbatim — so the length is bounded too, and the
         * truncation is marked rather than silent.
         */
        const val MAX_FIELD_CHARS = 120

        /** ONE definition, shared with the webhook path. See [CredentialShapes]. */
        val KEY_SHAPED_RE = CredentialShapes.KEY_SHAPED_RE

        /**
         * A redactor holding no secret of its own — shape scrubbing only.
         *
         * For diagnostic sites that are genuinely outside any client or webhook secret's scope. It
         * is a [Redactor] rather than a bare call to [CredentialShapes.scrub] so that every
         * diagnostic in the SDK goes through the SAME choke point and gets the SAME length bound.
         */
        val SHAPES_ONLY = Redactor(emptyList())
    }

    /**
     * THE ONE WAY server-controlled data is allowed to enter a diagnostic string.
     *
     * ── Why a choke point rather than care at each site ───────────────────────────────────
     * Every leak this SDK has had on an error path had the same shape: someone wrote
     * `"field was \"$value\""` with a value that came off the wire. Reviewing each such site is a
     * process that works until the next site is written. Routing them all through one function
     * makes the safe spelling the SHORT one, and makes the unsafe spelling — a bare `$value` — a
     * thing a reviewer and a test can both grep for.
     *
     * Three things happen here, in this order, and all three are load-bearing:
     *   1. REDACT the configured secrets this redactor holds.
     *   2. SCRUB anything merely SHAPED like a credential, including secrets we do not hold.
     *   3. BOUND the length, so a hostile field cannot make a log entry arbitrarily large.
     *
     * The result is quoted, so an empty or whitespace value is visible in the message rather than
     * vanishing into the surrounding prose.
     */
    fun field(value: Any?): String {
        if (value == null) return "absent"
        val redacted = text(value.toString())
        val bounded = if (redacted.length <= MAX_FIELD_CHARS) {
            redacted
        } else {
            redacted.take(MAX_FIELD_CHARS) + "… [${redacted.length} chars, truncated]"
        }
        return "\"$bounded\""
    }

    /**
     * Does this parsed structure carry one of OUR OWN CONFIGURED secrets anywhere inside it?
     *
     * Used to REFUSE rather than to scrub. A correctly-signed body that quotes our own signing
     * secret back at us is not a formatting problem to be masked: it means the sender holds the
     * secret and is echoing it, i.e. the emitter is misconfigured or the server is compromised. In
     * either case the right answer is to stop, not to print a tidier version of the same body.
     *
     * ── Exact needles ONLY, deliberately not credential SHAPES ────────────────────────────
     * A shape match means "this looks like somebody's credential" — possibly another environment's
     * key, possibly a string that merely resembles one. That is a reason to SCRUB the value, and
     * the existing per-field rules already do so (free-form prose is masked, identifier fields are
     * refused individually). Escalating every shape match to a whole-event refusal would reject
     * legitimate events over a substring resemblance.
     *
     * An exact match on a secret THIS CLIENT HOLDS admits no such benign reading. Nobody can echo
     * it back without having it.
     *
     * The traversal FAILS CLOSED. A structure too deep to finish scanning is reported as carrying a
     * credential, because "we could not look" and "there is nothing there" are different answers
     * and only one of them is safe to act on. With [MAX_DEPTH] pinned to the parser's bound this is
     * unreachable through the parser — which is the point: it is the assertion that keeps it
     * unreachable, not an assumption that it already is.
     */
    fun containsCredentialDeep(value: Any?): Boolean = scan(value, 0)

    /** An exact match on a secret this client holds — no shape matching. See [containsCredentialDeep]. */
    private fun holdsConfiguredSecret(value: String?): Boolean =
        value != null && needles.any { value.contains(it) }

    private fun scan(value: Any?, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        return when (value) {
            null -> false
            is String -> holdsConfiguredSecret(value)
            is Boolean, is Number -> false
            is Map<*, *> -> value.any { (k, v) ->
                // KEYS as well as values: an echoed request renders headers as keys at least as
                // often as it renders them as values.
                holdsConfiguredSecret(k?.toString()) || scan(v, depth + 1)
            }
            is Iterable<*> -> value.any { scan(it, depth + 1) }
            is Array<*> -> value.any { scan(it, depth + 1) }
            else -> holdsConfiguredSecret(value.toString())
        }
    }
}
