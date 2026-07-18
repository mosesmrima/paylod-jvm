package dev.paylod

import dev.paylod.internal.Json
import java.util.Collections

/** WHO/what is at fault — lets a merchant tell their config apart from the customer or M-Pesa. */
enum class DarajaCategory {
    CUSTOMER,
    BALANCE,
    LIMIT,
    CREDENTIALS,
    NETWORK,
    MPESA_SYSTEM,
    PENDING,
    SUCCESS;

    internal companion object {
        fun fromWire(value: String): DarajaCategory = when (value) {
            "customer" -> CUSTOMER
            "balance" -> BALANCE
            "limit" -> LIMIT
            "credentials" -> CREDENTIALS
            "network" -> NETWORK
            "mpesa_system" -> MPESA_SYSTEM
            "pending" -> PENDING
            "success" -> SUCCESS
            else -> throw IllegalArgumentException("unknown Daraja category: $value")
        }
    }
}

/**
 * Which Daraja surface a code came from. The SAME numeric code means different things on different
 * surfaces (e.g. 2001 = wrong PIN on STK, but invalid initiator on B2C), so the family disambiguates.
 */
enum class DarajaFamily {
    STK_RESULT,
    API_ERROR,
    B2C_C2B_RESULT;

    internal companion object {
        fun fromWire(value: String): DarajaFamily = when (value) {
            "stk_result" -> STK_RESULT
            "api_error" -> API_ERROR
            "b2c_c2b_result" -> B2C_C2B_RESULT
            else -> throw IllegalArgumentException("unknown Daraja family: $value")
        }
    }
}

/** The terminal/non-terminal classification of a synchronous STK Query result. */
enum class StkOutcome { PENDING, SUCCESS, FAILED }

/**
 * A decoded, human-readable error. Shape matches the `decoded` field on webhook events.
 *
 * `retryable` means **SAFE TO CHARGE AGAIN** — not merely "the user could try again". An in-flight
 * / pending payment is therefore NEVER retryable.
 */
data class DecodedError(
    /** The original ResultCode, normalized to a string. */
    val code: String,
    val title: String,
    /** Plain-English "what happened". */
    val cause: String,
    /** Actionable "what to do" for the merchant/developer. */
    val fix: String,
    val category: DarajaCategory,
    val retryable: Boolean,
    /** Short, friendly line a merchant could show THEIR end-customer. */
    val customerMessage: String,
)

/** One catalog entry, as stored in `daraja-error-codes.json`. */
data class CatalogEntry(
    val code: String,
    val family: DarajaFamily,
    val title: String,
    val cause: String,
    val fix: String,
    val category: DarajaCategory,
    val retryable: Boolean,
    val customerMessage: String,
    val sources: List<String> = emptyList(),
)

/**
 * THE single source of truth for Daraja result-code meanings — classification AND decoding.
 *
 * This is a faithful port of the Node SDK's `daraja-catalog.ts`, reading the SAME
 * `daraja-error-codes.json` table (copied verbatim into resources).
 *
 * ── The `retryable` contract ──
 * `retryable` means SAFE TO CHARGE AGAIN — we know no money moved and no charge is still in flight.
 * A pending/in-flight payment is NEVER retryable; an indeterminate outcome (unknown code) is NEVER
 * retryable.
 */
object DarajaCatalog {

    /**
     * Every catalog entry, in file order. Exposed as an UNMODIFIABLE list — a Java caller sees the
     * `List` interface and could otherwise cast to a mutable backing type and corrupt the table.
     */
    @JvmStatic
    val allEntries: List<CatalogEntry> = Collections.unmodifiableList(loadEntries())

    /**
     * Codes that mean "still processing, poll again", derived FROM the table so the classifier and
     * the decoder can never disagree. Compared as normalized strings. UNMODIFIABLE (see [allEntries]).
     */
    @JvmStatic
    val pendingResultCodes: Set<String> = Collections.unmodifiableSet(
        allEntries.filter { it.category == DarajaCategory.PENDING }.map { it.code }.toMutableSet(),
    )

    // `ResultDesc` phrasings that mean "still processing", used as a safety net for unrecognised codes.
    private val PENDING_DESC_RE = Regex(
        "\\b(?:still\\s+under\\s+processing|is\\s+being\\s+processed|still\\s+processing|being\\s+processed)\\b",
        RegexOption.IGNORE_CASE,
    )

    // `500.001.1001` is overloaded: under the SAME code Daraja also returns hard, terminal config
    // errors. A 500.* whose message matches one of these is NOT treated as pending.
    private val TERMINAL_500_MESSAGE_RE = Regex(
        "\\b(?:wrong\\s+credentials|merchant\\s+does\\s+not\\s+exist|invalid\\s+access\\s+token|unable\\s+to\\s+lock\\s+subscriber|insufficient\\s+funds?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun normalizeCode(resultCode: Any?): String =
        when (resultCode) {
            null -> ""
            is Double -> if (resultCode == Math.floor(resultCode)) resultCode.toLong().toString() else resultCode.toString()
            else -> resultCode.toString().trim()
        }

    /**
     * Is this result code the SUCCESS code — exactly, not merely numerically?
     *
     * ── Why this is not `toDouble() == 0.0` ───────────────────────────────────────────────
     * It used to be. `ResultCode = 0` is the single value that means "money moved", and it was
     * recognised by parsing the code as a `Double` and comparing to zero. That accepts a whole
     * family of values that are numerically zero but are NOT the schema-approved success code, and
     * every one of them is a way to manufacture proof of payment out of a record that has none:
     *
     *   • `"0e999"`   — `Double.parseDouble` reads this as 0.0.
     *   • `"+0"`      — a leading sign; `Integer.parseInt`/`parseDouble` both accept it.
     *   • `"00"`      — leading zeros.
     *   • `"0.0"`     — a float where an integer code is specified.
     *   • `"-0"`      — negative zero, and `-0.0 == 0.0` is true.
     *   • `"0x0"`     — `Integer.decode` reads this as 0.
     *   • `" 0"`      — surrounded by whitespace.
     *
     * None of these is a code Daraja emits. Each is what a mangled, re-encoded, or crafted record
     * looks like, and under the old rule each produced `SUCCESS` evidence — which, on a `success`
     * claim, is the difference between `INDETERMINATE` and `PAID`. That is goods shipped for a
     * payment that never settled.
     *
     * So success is recognised for exactly two representations: the INTEGER `0` (any integral JVM
     * number type, which is what a JSON `0` parses to) and the canonical STRING `"0"`. A floating
     * point value is deliberately excluded even when it equals zero — a JSON `0.0` is not the
     * integer zero, and treating it as one is the same laundering this rule exists to stop.
     *
     * Everything else numerically-zero is not evidence of anything, so it classifies as PENDING
     * (ambiguous) rather than SUCCESS or FAILED. On a `success` claim that yields `INDETERMINATE`,
     * which is the honest answer: we cannot read the code, so we cannot confirm the payment.
     */
    @JvmStatic
    fun isCanonicalSuccessCode(resultCode: Any?): Boolean = when (resultCode) {
        // Integral JVM numbers. A JSON `0` parses to `Long` here, which is the ordinary wire case.
        is Byte, is Short, is Int, is Long -> (resultCode as Number).toLong() == 0L
        // The canonical string, byte for byte. Not trimmed, not parsed, not coerced.
        is String -> resultCode == "0"
        // Double/Float/BigDecimal and everything else: not the integer zero.
        else -> false
    }

    /**
     * Classify a synchronous STK Query result. THE authoritative call — the decoder defers to this,
     * so a stale or wrong table entry can never resurrect the 4999 bug.
     */
    @JvmStatic
    @JvmOverloads
    fun classifyStkResult(resultCode: Any?, resultDesc: String? = null): StkOutcome {
        val raw = normalizeCode(resultCode)
        val desc = (resultDesc ?: "").trim()

        // A terminal 500.* config error must not be mistaken for "still processing".
        if (raw.startsWith("500.") && TERMINAL_500_MESSAGE_RE.containsMatchIn(desc)) return StkOutcome.FAILED

        if (pendingResultCodes.contains(raw)) return StkOutcome.PENDING

        // SUCCESS is recognised from the ORIGINAL value, exactly. See [isCanonicalSuccessCode].
        if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS

        val n = raw.toDoubleOrNull()
        if (raw.isNotEmpty() && n != null && n.isFinite()) {
            if (n == 0.0) {
                // Numerically zero but NOT the canonical success code — an impostor like "0e999",
                // "+0", "00", "-0" or " 0". It is not proof of payment, and it is not a known
                // failure code either, so it is ambiguous. Ambiguity is never force-failed and is
                // certainly never reported as success: on a `success` claim this becomes
                // INDETERMINATE, and the webhook or a later read settles it.
                return StkOutcome.PENDING
            }
            // A known-numeric, non-zero code is terminal — UNLESS the description says otherwise.
            return if (PENDING_DESC_RE.containsMatchIn(desc)) StkOutcome.PENDING else StkOutcome.FAILED
        }

        // Blank / non-numeric / unknown -> never force-fail on ambiguity.
        return StkOutcome.PENDING
    }

    /**
     * True when a thrown `stkQuery` error is really a "still processing" signal rather than a
     * genuine transport/auth failure.
     */
    @JvmStatic
    fun isPendingError(message: String?): Boolean {
        val s = message ?: ""
        if (TERMINAL_500_MESSAGE_RE.containsMatchIn(s)) return false
        if (PENDING_DESC_RE.containsMatchIn(s)) return true
        for (code in pendingResultCodes) {
            // Bare "4999" is too generic to substring-match; only dotted business codes are safe.
            if (code.contains(".") && s.contains(code)) return true
        }
        return false
    }

    private fun pickEntry(code: String, family: DarajaFamily, outcome: StkOutcome): CatalogEntry? {
        val matches = allEntries.filter { it.code == code }
        if (matches.isEmpty()) return null

        val consistent = matches.filter {
            if (outcome == StkOutcome.PENDING) it.category == DarajaCategory.PENDING
            else it.category != DarajaCategory.PENDING
        }
        if (consistent.isEmpty()) return null

        return consistent.firstOrNull { it.family == family } ?: consistent.first()
    }

    private fun pendingFallback(code: String) = DecodedError(
        code = code,
        title = "Payment still in progress",
        cause = "M-Pesa is still processing this payment — the customer has most likely not entered their " +
            "M-Pesa PIN yet. This is NOT a failure: the payment is still live and can still succeed.",
        fix = "Keep polling GET /status/:id (or wait for the webhook). Do NOT retry the charge — a retry " +
            "sends a second prompt and can double-charge the customer.",
        category = DarajaCategory.PENDING,
        retryable = false,
        customerMessage = "Check your phone and enter your M-Pesa PIN to complete this payment.",
    )

    private fun failedFallback(code: String, rawDesc: String?): DecodedError {
        val desc = (rawDesc ?: "").trim()
        return DecodedError(
            code = code,
            title = "Payment failed",
            cause = if (desc.isNotEmpty()) desc else "M-Pesa returned a non-zero ResultCode with no further detail.",
            fix = "Check the raw ResultDesc, verify your credentials + shortcode/till pairing, and confirm " +
                "the payment's final state with GET /status/:id before charging again — this code is not " +
                "in the catalog, so we cannot prove no money moved.",
            category = DarajaCategory.MPESA_SYSTEM,
            retryable = false,
            customerMessage = "The payment didn't go through. Please try again.",
        )
    }

    private fun decodedFrom(code: String, entry: CatalogEntry): DecodedError = DecodedError(
        code = code,
        title = entry.title,
        cause = entry.cause,
        fix = entry.fix,
        category = entry.category,
        retryable = entry.retryable,
        customerMessage = entry.customerMessage,
    )

    /**
     * Decode a Daraja ResultCode into a normalized, human-readable error.
     *
     * ── Family-awareness ──
     * The STK "still processing -> pending" semantics (and the blank/unknown-numeric -> pending
     * fallback) apply ONLY to the STK result surface. A dotted `api_error` code (e.g. 400.002.02,
     * 500.001.1001) or an alphanumeric `b2c_c2b_result` code (e.g. C2B00011) is a TERMINAL error;
     * routing it through [classifyStkResult] used to misclassify it as `pending` and decode it as
     * "payment still in progress", which is wrong. So we select by family:
     *
     *   • STK family: defer to [classifyStkResult], so 4999 / 500.001.1001 can never decode as a
     *     failure and can never be advertised as retryable.
     *   • Non-STK families: decode straight from the catalog by family — no pending semantics. This
     *     also disambiguates the OVERLOADED 500.001.1001, whose `api_error` entry is the terminal
     *     "merchant does not exist / insufficient funds" server error.
     *
     * If the caller asks for the (default) STK family but the code exists ONLY in non-STK families,
     * we decode it by its real family rather than letting the STK unknown->pending rule mislabel it.
     *
     * @param resultCode the Daraja ResultCode (number or string). `null` is treated as unknown.
     * @param rawDesc the raw ResultDesc — corroborating signal, and the `cause` when the code is unknown.
     * @param family which Daraja surface the code came from. Defaults to the STK payment path.
     */
    @JvmStatic
    @JvmOverloads
    fun decodeError(
        resultCode: Any?,
        rawDesc: String? = null,
        family: DarajaFamily = DarajaFamily.STK_RESULT,
    ): DecodedError {
        val code = normalizeCode(resultCode)

        // An ABSENT code is not evidence of an in-flight payment — it is simply unknown.
        if (code.isEmpty()) return failedFallback("unknown", rawDesc)

        val matches = allEntries.filter { it.code == code }
        val hasStk = matches.any { it.family == DarajaFamily.STK_RESULT }

        // If STK was requested but the code is not an STK code, decode it by the family it DOES have.
        val effectiveFamily: DarajaFamily =
            if (family == DarajaFamily.STK_RESULT && !hasStk && matches.isNotEmpty()) matches.first().family
            else family

        if (effectiveFamily == DarajaFamily.STK_RESULT) {
            val outcome = classifyStkResult(code, rawDesc)
            val entry = pickEntry(code, effectiveFamily, outcome)
            if (entry != null) return decodedFrom(code, entry)
            return if (outcome == StkOutcome.PENDING) pendingFallback(code) else failedFallback(code, rawDesc)
        }

        // Terminal (api_error / b2c_c2b_result): no STK pending semantics, EVER.
        //
        // We select the entry for the requested family, or — failing that — another NON-STK entry.
        // We deliberately do NOT fall back to `matches.firstOrNull()`: that last-resort fallback could
        // hand back an STK entry, and the only STK entries a non-STK lookup can collide with are the
        // *pending* ones (4999, 500.001.1001). Returning those would tell a caller "the payment is
        // still in flight, keep polling" about a code that arrived on a terminal surface — the exact
        // 4999 bug this family-awareness exists to kill. When no non-STK entry exists the honest
        // answer is the terminal, NON-RETRYABLE fallback.
        val entry = matches.firstOrNull { it.family == effectiveFamily }
            ?: matches.firstOrNull { it.family != DarajaFamily.STK_RESULT }
        return if (entry != null) decodedFrom(code, entry) else failedFallback(code, rawDesc)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadEntries(): List<CatalogEntry> {
        val stream = DarajaCatalog::class.java.getResourceAsStream("/dev/paylod/daraja-error-codes.json")
            ?: throw IllegalStateException("daraja-error-codes.json is missing from the classpath")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parseObject(text)
        val codes = root["codes"] as? List<Any?>
            ?: throw IllegalStateException("daraja-error-codes.json has no `codes` array")
        return codes.map { raw ->
            val e = raw as Map<String, Any?>
            CatalogEntry(
                code = e["code"] as String,
                family = DarajaFamily.fromWire(e["family"] as String),
                title = e["title"] as String,
                cause = e["cause"] as String,
                fix = e["fix"] as String,
                category = DarajaCategory.fromWire(e["category"] as String),
                retryable = e["retryable"] as Boolean,
                customerMessage = e["customerMessage"] as String,
                sources = Collections.unmodifiableList(
                    (e["sources"] as? List<Any?>)?.map { it as String } ?: emptyList(),
                ),
            )
        }
    }
}
