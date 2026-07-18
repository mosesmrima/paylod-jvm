package dev.paylod

import dev.paylod.internal.Json

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

    /** Every catalog entry, in file order. */
    @JvmStatic
    val allEntries: List<CatalogEntry> = loadEntries()

    /**
     * Codes that mean "still processing, poll again", derived FROM the table so the classifier and
     * the decoder can never disagree. Compared as normalized strings.
     */
    @JvmStatic
    val pendingResultCodes: Set<String> =
        allEntries.filter { it.category == DarajaCategory.PENDING }.map { it.code }.toSet()

    // `ResultDesc` phrasings that mean "still processing", used as a safety net for unrecognised codes.
    private val PENDING_DESC_RE = Regex(
        "\\b(?:still\\s+under\\s+processing|is\\s+being\\s+processed|still\\s+processing|being\\s+processed)\\b",
        RegexOption.IGNORE_CASE,
    )

    // `500.001.1001` is overloaded: under the SAME code Daraja also returns hard, terminal config
    // errors. A 500.* whose message matches one of these is NOT treated as pending.
    private val TERMINAL_500_MESSAGE_RE = Regex(
        "\\b(?:wrong\\s+credentials|merchant\\s+does\\s+not\\s+exist|invalid\\s+access\\s+token|unable\\s+to\\s+lock\\s+subscriber)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun normalizeCode(resultCode: Any?): String =
        when (resultCode) {
            null -> ""
            is Double -> if (resultCode == Math.floor(resultCode)) resultCode.toLong().toString() else resultCode.toString()
            else -> resultCode.toString().trim()
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

        val n = raw.toDoubleOrNull()
        if (raw.isNotEmpty() && n != null && n.isFinite()) {
            if (n == 0.0) return StkOutcome.SUCCESS
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

    /**
     * Decode a Daraja ResultCode into a normalized, human-readable error. Defers to
     * [classifyStkResult] FIRST, so pending/in-flight codes (4999, 500.001.1001) can never decode as
     * a failure and can never be advertised as retryable.
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

        val outcome = classifyStkResult(code, rawDesc)
        val entry = pickEntry(code, family, outcome)

        if (entry != null) {
            return DecodedError(
                code = code,
                title = entry.title,
                cause = entry.cause,
                fix = entry.fix,
                category = entry.category,
                retryable = entry.retryable,
                customerMessage = entry.customerMessage,
            )
        }

        return if (outcome == StkOutcome.PENDING) pendingFallback(code)
        else failedFallback(code.ifEmpty { "unknown" }, rawDesc)
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
                sources = (e["sources"] as? List<Any?>)?.map { it as String } ?: emptyList(),
            )
        }
    }
}
