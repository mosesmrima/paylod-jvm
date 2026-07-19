package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.JsonNumber
import dev.paylod.internal.Redactor
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

    /**
     * Every code the catalog defines on the STK result surface.
     *
     * Derived FROM the table, like [pendingResultCodes], so membership and decoding can never
     * disagree — a code that classifies as a terminal failure is by construction a code
     * [decodeError] can produce a real entry for. See the membership check in [classifyStkResult]
     * (conformance requirement 1.5).
     */
    @JvmStatic
    val knownStkResultCodes: Set<String> = Collections.unmodifiableSet(
        allEntries.filter { it.family == DarajaFamily.STK_RESULT }.map { it.code }.toMutableSet(),
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
            // NOT trimmed: the token is returned as written, so a lower layer cannot launder
            // `" 1032"` into `"1032"` behind the checks above it. See requirement 1.1.
            is JsonNumber -> resultCode.lexeme
            // A Double keeps its OWN representation — no collapse, at any value. Collapsing a
            // whole float to its integer form was a lookup convenience that manufactured a
            // canonical code out of a token Daraja never sent; the zero carve-out closed only the
            // success half of it. See [normalizeResultCode] in `Validators.kt`.
            is Double -> resultCode.toString()
            // NOT TRIMMED. This helper used to `.trim()`, which independently turned `" 0"` into
            // `"0"` and `"1032\n"` into `"1032"` — canonical codes, manufactured one layer BELOW
            // every check written to reject them. Its callers happened to guard it, so nothing was
            // reachable through them; but requirement 1.1 says a guard at one layer is not a guard
            // at the layer below it, and "currently unreachable" is a property of today's call
            // graph rather than of this function. A padded code is not a code, here as everywhere.
            else -> resultCode.toString()
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
        // A number read from a document: compared as the TOKEN, not as a value. This is the
        // strongest form of the rule — `-0`, `0.0` and `0e999` are all numerically zero and none of
        // them is spelled `0`, so each is rejected on the one property that actually distinguishes
        // it from the canonical code.
        is JsonNumber -> resultCode.lexeme == "0"
        // Integral JVM numbers built by a CALLER rather than parsed (the simulator, a hand-made
        // fixture, a Java integration). No document, so no token; the value is all there is.
        is Byte, is Short, is Int, is Long -> (resultCode as Number).toLong() == 0L
        // The canonical string, byte for byte. Not trimmed, not parsed, not coerced.
        is String -> resultCode == "0"
        // Double/Float/BigDecimal and everything else: not the integer zero.
        else -> false
    }

    // ─── Canonical code FORM ──────────────────────────────────────────────────────────────────
    //
    // [isCanonicalSuccessCode] closed the SUCCESS direction at the CLASSIFIER. It did not close it
    // at the DECODER, and it never addressed the failure direction at all. `normalizeCode` trims,
    // and `decodeError` looked the entry up by the trimmed string, so BOTH of these held:
    //
    //   • `decodeError(" 0")`    → the SUCCESS entry. code "0", category SUCCESS, "Payment
    //     received — thank you!" — rendered for a row that never paid, while `classifyStkResult`
    //     on the very same value correctly said PENDING.
    //   • `decodeError(" 1032")` → the cancelled-by-the-customer entry, `retryable = true`, "offer
    //     a clear retry button" — a confident instruction to charge again for a payment whose real
    //     state nobody knew.
    //
    // An exact check one layer up is worth nothing while a lower layer launders the impostor into
    // canonical form first. So the FORM is judged on the bytes as they arrived, before any entry
    // can be chosen.
    //
    // NOTE ON THE ANCHOR: these carry no `^`/`$` because [Regex.matches] is a FULL-region match.
    // That is deliberate — an anchored `$` would reintroduce the sibling SDKs' hazard, since Java's
    // `$` (like PCRE's, like Python's) also matches just before a trailing newline, which would
    // accept `"1032\n"` as canonical with no trim involved. Never switch these to `containsMatchIn`.

    /** A bare decimal code: no sign, no leading zeros, no exponent, no radix prefix, no fraction. */
    private val CANONICAL_CODE_RE = Regex("(?:0|[1-9][0-9]*)")

    /**
     * A dotted Daraja business code — `500.001.1001`, `400.002.02`. Each segment is bare digits; the
     * FIRST segment carries no leading zero, later segments may (`002` is how Daraja writes them).
     *
     * ── Why THREE components, not two ─────────────────────────────────────────────────────
     * This accepted `{1,6}` trailing segments, so a two-component value was canonical — and the
     * two-component values are exactly the FLOAT SPELLINGS. `"1032.0"` is what
     * `PaymentValidators.normalizeResultCode` produces for a raw JSON `1032.0`, deliberately, so
     * that the float cannot be laundered into `"1032"`. It then matched here, was pronounced
     * canonical, and reached the classifier's numeric branch as a non-zero finite number: TERMINAL
     * FAILURE, for a float Daraja never sent. The refusal one layer up was undone by the pattern
     * meant to enforce it.
     *
     * Every dotted code Daraja actually issues has exactly three components — see
     * `daraja-error-codes.json`, where all eight are `NNN.NNN.NN…`. Requiring at least two dots is
     * therefore not a narrowing of anything real, and it removes the entire two-component space in
     * which a decimal float and a business code are spelled identically.
     */
    private val CANONICAL_DOTTED_RE = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8}){2,6}")

    /** An alphanumeric result code — `C2B00011`. Always starts with a letter, never with a digit. */
    private val CANONICAL_ALNUM_RE = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")

    /**
     * Is this code written the way Daraja writes result codes?
     *
     * A code failing this is not a code with a formatting quirk — it is a string Daraja never sent.
     * It is neither a success nor a proven failure, so it must not select a catalog entry.
     */
    @JvmStatic
    fun isCanonicalCodeLexeme(code: String?): Boolean {
        if (code == null) return false
        return CANONICAL_CODE_RE.matches(code) ||
            CANONICAL_DOTTED_RE.matches(code) ||
            CANONICAL_ALNUM_RE.matches(code)
    }

    /**
     * The code AS IT ARRIVED, or `null` when it has no lexeme.
     *
     * A `Boolean` is not a result code. A floating-point value has no lexeme: there is no lossless
     * rendering of a `Double` back to the token the sender wrote (`0.0`, `-0.0`, `1032.0` and
     * `1.0e3` all collapse), and the schema specifies an integer — so a float-typed code is refused
     * rather than guessed at. Mirrors the PHP and Python SDKs exactly.
     *
     * PUBLIC because three layers must each ask this question independently (requirement 1.1) —
     * the classifier, the decoder and the webhook schema check. Each CALLS it separately, which is
     * the independence the requirement asks for; what they must not have is three separate
     * DEFINITIONS of what a lexeme is. The webhook path had its own `when` block, and a fourth
     * spelling is a fourth thing to forget to update.
     */
    @JvmStatic
    fun codeLexeme(resultCode: Any?): String? = when (resultCode) {
        null -> null
        is Boolean -> null
        // The token EXACTLY as the sender wrote it, straight from the parser. This is the case that
        // used to be handled by refusing every `Double` for "having no lexeme": correct in outcome,
        // but it judged the JVM TYPE rather than the spelling. `1032.0`, `1.032e3`, `0e999` and
        // `-0` now each arrive with their own distinct lexeme and are each refused by
        // [isCanonicalCodeLexeme] on their own spelling. See [dev.paylod.internal.JsonNumber].
        is JsonNumber -> resultCode.lexeme
        is Byte, is Short, is Int, is Long -> resultCode.toString()
        is String -> resultCode
        // A bare Double/Float/BigDecimal — one built by a caller rather than read from a document,
        // so there is no token to recover and no lossless rendering back to one.
        else -> null
    }

    /**
     * Classify a synchronous STK Query result. THE authoritative call — the decoder defers to this,
     * so a stale or wrong table entry can never resurrect the 4999 bug.
     */
    @JvmStatic
    @JvmOverloads
    fun classifyStkResult(resultCode: Any?, resultDesc: String? = null): StkOutcome {
        // A code with NO LEXEME is a value the sender cannot have written as a Daraja result code:
        // a `Double`/`Float`/`BigDecimal` (no lossless rendering back to the token — `0.0`, `-0.0`,
        // `1032.0` and `1.032e3` all collapse) or a `Boolean`. It is neither proof of success nor
        // proof of failure, so it is AMBIGUOUS. Ambiguity is never force-failed: on a `success`
        // claim it yields INDETERMINATE, on a `failed` claim it yields INDETERMINATE too, and the
        // webhook or a later read settles it. Reaching the numeric branch below with a laundered
        // float is what let `1032.0` classify as a terminal cancellation.
        if (resultCode != null && codeLexeme(resultCode) == null) return StkOutcome.PENDING

        // ── FORM BEFORE MEANING, AT THE CLASSIFIER TOO ────────────────────────────────────────
        //
        // `decodeError` has required a canonical lexeme before a catalog lookup since round 6. The
        // CLASSIFIER did not, and the classifier is the one that produces the money verdict. It
        // reached the numeric branch below through `normalizeCode`, which TRIMS — so `" 1032"`,
        // `"1032\n"`, `"+1032"`, `"01032"` and the string `"1032.0"` (which is what
        // `PaymentValidators.normalizeResultCode` hands over for a raw JSON `1032.0`, precisely so
        // the float cannot be laundered) all parsed as a non-zero finite number and returned
        // FAILED. A token Daraja never sent became TERMINAL FAILURE EVIDENCE, and `PaymentSemantics`
        // turned that into a `FAILED` verdict on a payment whose real state nobody knew.
        //
        // The refusal is the same one the decoder makes, for the same reason: a non-canonical code
        // is neither proof of success nor proof of failure. It is AMBIGUOUS, and ambiguity resolves
        // to PENDING — never to a terminal verdict — so the webhook or a later read settles it.
        //
        // The float type is refused twice over: once above (a `Double` has no lexeme) and once here
        // (the string `"1032.0"` is not a canonical lexeme), because the two layers launder it
        // differently and a guarantee with two ways in has to be closed at both.
        val lexeme = codeLexeme(resultCode)
        if (lexeme != null && lexeme.isNotEmpty() && !isCanonicalCodeLexeme(lexeme)) {
            return StkOutcome.PENDING
        }

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
            // Belt and braces on the FORM: only a BARE DECIMAL lexeme may take the terminal branch.
            // The canonical set also contains dotted and alphanumeric spellings, and a dotted one
            // that `toDoubleOrNull` happens to parse must not be judged as though it were a plain
            // number. Two independent readings of "is this a number" is how the float spelling got
            // in the first time.
            if (!CANONICAL_CODE_RE.matches(raw)) return StkOutcome.PENDING
            // ── AN UNKNOWN CODE IS NOT EVIDENCE (conformance requirement 1.5) ─────────────────
            //
            // This branch used to return FAILED for ANY canonically-shaped non-zero number,
            // catalog member or not. So `999999`, `31337`, `12345` — codes Daraja does not define
            // and this SDK has never seen — became TERMINAL FAILURE evidence, and on a `failed`
            // claim `PaymentSemantics` turned that into a `FAILED` verdict: a confident, customer-
            // facing "the payment didn't go through" for a payment whose real state nobody knew.
            //
            // Form is not meaning. Passing the lexeme check only establishes that the value is
            // SPELLED the way Daraja spells codes; it says nothing about whether the payment
            // failed. A code we cannot place is the definition of ambiguity, and ambiguity resolves
            // to PENDING here so the webhook or a later read settles it — never to a terminal
            // verdict, and so never to a retry invitation that could raise a second charge against
            // a payment that may well have succeeded.
            //
            // The catalog is consulted for MEMBERSHIP only, which is why this cannot weaken the
            // converse: every code the catalog defines still classifies exactly as it did.
            if (!knownStkResultCodes.contains(raw)) return StkOutcome.PENDING
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

    /**
     * The decode of a code this catalog CANNOT PLACE — absent, malformed, non-canonical, or
     * canonically shaped but not a catalog member.
     *
     * ── Why this is no longer called `failedFallback` ─────────────────────────────────────
     * It used to return `title = "Payment failed"` with the customer message *"The payment didn't
     * go through. Please try again."* — for an input the SDK had just finished establishing it
     * could not read. Three separate things were wrong with that, and each costs money on its own:
     *
     *   • IT ASSERTED A FAILURE IT HAD NOT ESTABLISHED (requirement 1.5). An unknown code is not
     *     evidence. `resultCode` absent entirely — the single most common shape of a partially
     *     written row — produced a confident terminal failure.
     *   • IT INVITED A RETRY (requirement 3.7). "Please try again" beside `retryable = false` is
     *     the contradiction that requirement forbids: the structured field said do not charge
     *     again and the sentence a customer actually READS said do. A merchant renders
     *     `outcome.message`, the customer taps Pay a second time, and nothing in this SDK ever
     *     proved the first attempt did not debit them.
     *   • ITS OWN `fix` TEXT CONTRADICTED ITS OWN TITLE — "this code is not in the catalog, so we
     *     cannot prove no money moved" sat directly beneath the word "failed".
     *
     * So the honest decode is INDETERMINATE: no failure claim, no retry invitation, and a customer
     * message that tells the truth — we are still finding out. `retryable` stays `false`, and now
     * every other field agrees with it.
     */
    private fun indeterminateFallback(code: String, rawDesc: String?): DecodedError {
        val desc = (rawDesc ?: "").trim()
        return DecodedError(
            code = code,
            title = "Payment state unknown",
            cause = (if (desc.isNotEmpty()) "$desc " else "") +
                "This result code is not one this SDK can place, so it is neither proof the payment " +
                "succeeded nor proof it failed.",
            fix = "Do NOT charge again — nothing here proves no money moved. Confirm the payment's " +
                "final state with GET /status/:id or let the webhook settle it, and check the raw " +
                "ResultDesc plus your credentials and shortcode/till pairing.",
            category = DarajaCategory.MPESA_SYSTEM,
            retryable = false,
            // Phrased WITHOUT the words "try"/"retry"/"pay again" in any form, including the
            // negated form. The catalog-wide invariant that enforces requirement 3.7 is a substring
            // rule, and a rule that has to distinguish "please try again" from "do not try again"
            // is a rule one careless edit away from letting the first one through. Saying the safe
            // thing in words the check cannot misread costs nothing.
            customerMessage = "We couldn't confirm this payment yet. Please wait while it settles — " +
                "do not start a new payment.",
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
    ): DecodedError = sanitized(decodeUnsanitized(resultCode, rawDesc, family))

    /**
     * THE redaction boundary for the offline decoder (conformance requirement 4.9).
     *
     * ── Why a PUBLIC OFFLINE surface needs its own redaction ──────────────────────────────
     * Everything else that builds a public object from server data does it inside the client, and
     * the client has a [Redactor] holding the configured API key and webhook secret. This function
     * has no client. It is `@JvmStatic`, it never touches the network, and it is documented as the
     * way to decode a code you already have — which means the values reaching it came from a
     * webhook body, a database row, or a log line, all of which are server-controlled.
     *
     * Two of [DecodedError]'s fields are built from those inputs: `code` (from `resultCode`, echoed
     * back deliberately so a caller debugging `" 0"` sees what they actually received) and `cause`
     * (from `rawDesc` on the indeterminate path). Both land on a public data class whose
     * `toString()` is GENERATED, so a server that echoed `Authorization: Bearer mp_live_…` into
     * `resultDesc` had it printed by any caller who logged the decode. The offline surface has no
     * client to redact for it, so it redacts for itself.
     *
     * [Redactor.SHAPES_ONLY] is the right instrument here and its limit is worth stating: it holds
     * no configured secret, so it masks anything SHAPED like a credential (`mp_live_`, `mp_test_`,
     * `whsec_`, `sk_`, `Bearer …`) but cannot match a secret by value. That is all a static function
     * can do. [Paylod.decodeError] runs the CLIENT's redactor over the result as well, which does
     * know the configured values — so a caller who has a client gets both, and a caller who does not
     * still gets shape masking rather than nothing.
     *
     * The length bound comes along with it: `Redactor.field` bounds interpolated server values, so a
     * megabyte-long `resultDesc` cannot become a megabyte-long log line.
     */
    private fun sanitized(d: DecodedError): DecodedError = d.copy(
        code = Redactor.SHAPES_ONLY.text(d.code).take(MAX_DECODED_FIELD_CHARS),
        cause = Redactor.SHAPES_ONLY.text(d.cause).take(MAX_DECODED_FIELD_CHARS),
    )

    /**
     * The longest `code`/`cause` a decode will carry.
     *
     * These two fields are the only ones built from caller- or server-supplied text; the rest come
     * from the catalog and are fixed-length by construction. Bounding them stops a hostile
     * `resultDesc` from making every log line that renders a decode arbitrarily large.
     */
    private const val MAX_DECODED_FIELD_CHARS = 512

    private fun decodeUnsanitized(
        resultCode: Any?,
        rawDesc: String?,
        family: DarajaFamily,
    ): DecodedError {
        val code = normalizeCode(resultCode)

        // An ABSENT code is not evidence of an in-flight payment — it is simply unknown.
        if (code.isEmpty()) return indeterminateFallback("unknown", rawDesc)

        // FORM BEFORE LOOKUP. A code that is not written the way Daraja writes them never reaches
        // the catalog — it is neither evidence of success nor evidence of failure, so it decodes as
        // the indeterminate, NON-RETRYABLE fallback. The ORIGINAL spelling is reported back rather
        // than the tidied one: a caller debugging this needs to see the `" 0"` they actually
        // received. See [isCanonicalCodeLexeme] for what this closed.
        val lexeme = codeLexeme(resultCode)
        if (lexeme == null || !isCanonicalCodeLexeme(lexeme)) {
            return indeterminateFallback(lexeme ?: code, rawDesc)
        }

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
            // No entry. `outcome == PENDING` is now reached by TWO very different routes and they
            // must not share a decode:
            //
            //   • The code, or the description, positively SAYS the payment is still processing —
            //     a genuine in-flight signal. "Check your phone and enter your PIN" is correct.
            //   • The code is simply one we cannot place (conformance 1.5). Nothing here says the
            //     prompt is live. Telling a customer to check their phone for a prompt that may
            //     never have been sent is a false statement, and it is the same class of error as
            //     the old "Payment failed" — asserting a state the SDK never established.
            //
            // Only the first route may claim in-flight, so it is required to be POSITIVE evidence:
            // a catalog-defined pending code, or an explicit still-processing description.
            val positivelyPending = pendingResultCodes.contains(code) ||
                PENDING_DESC_RE.containsMatchIn((rawDesc ?: "").trim())
            return if (outcome == StkOutcome.PENDING && positivelyPending) {
                pendingFallback(code)
            } else {
                indeterminateFallback(code, rawDesc)
            }
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
        return if (entry != null) decodedFrom(code, entry) else indeterminateFallback(code, rawDesc)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadEntries(): List<CatalogEntry> {
        val stream = DarajaCatalog::class.java.getResourceAsStream("/dev/paylod/daraja-error-codes.json")
            ?: throw IllegalStateException("daraja-error-codes.json is missing from the classpath")
        val text = stream.use { dev.paylod.internal.Utf8.decode(it.readBytes(), "the bundled Daraja catalog resource") }
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
