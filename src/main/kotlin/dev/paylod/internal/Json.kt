package dev.paylod.internal

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * paylod's wire shapes are small and flat, so a full serialization runtime would be more machinery
 * than the job needs. This keeps the SDK at ZERO runtime dependencies — the same posture the Node
 * SDK ships with, which is worth something for a library that sits on a payments path.
 *
 * The reader parses into plain JVM values so arbitrary `metadata` maps round-trip without a schema:
 *   - object  -> LinkedHashMap<String, Any?>  (insertion-ordered)
 *   - array   -> List<Any?>
 *   - string  -> String
 *   - number  -> Long when integral and in range, otherwise Double
 *   - boolean -> Boolean
 *   - null    -> null
 *
 * The writer accepts the same value shapes plus `Int`/`Short`/`Byte`. It is NOT a general
 * pretty-printer: it emits compact JSON, which is all the API needs.
 */
/**
 * Is this the IEEE-754 NEGATIVE zero, as distinct from the ordinary zero?
 *
 * `v == 0.0` cannot answer this: -0.0 and 0.0 compare EQUAL under IEEE, which is precisely why a
 * negative zero slips through arithmetic checks written the obvious way. The sign bit is the only
 * thing that separates them, so it is read directly rather than inferred from a comparison.
 *
 * This matters on the money path because `-0` is one of the values that must never be mistaken for
 * the canonical Daraja success code `"0"`. See [dev.paylod.DarajaCatalog.isCanonicalSuccessCode].
 */
internal fun isNegativeZero(v: Double): Boolean =
    v == 0.0 && java.lang.Double.doubleToRawLongBits(v) != 0L

internal object Json {

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    /**
     * The largest request body this writer will produce, in characters, and the deepest structure
     * it will descend into.
     *
     * ── Why the WRITER needs bounds the reader already has ────────────────────────────────
     * The reader is bounded because a response is attacker-controlled. The writer looked safe by
     * comparison: it serialises the SDK's own request bodies. But one field in those bodies is
     * arbitrary caller data — `metadata`, which is a `Map<String, Any?>` the caller fills with
     * whatever they like, and which is very often built from data the caller's own users supplied.
     * Three shapes then reach a recursive-descent writer with no limits at all:
     *
     *   • DEEP. `writeValue` recurses per level, so a few tens of thousands of nested maps is a
     *     `StackOverflowError` — an `Error`, not a `PaylodException`, so it escapes every catch on
     *     the money path and surfaces as a stack overflow inside the caller's request handler.
     *   • CYCLIC. A map containing itself recurses forever. Same outcome, reached faster, and
     *     trivially produced by ordinary application code — an ORM entity graph, a memoised
     *     structure, a parent/child pair.
     *   • HUGE. A large collection materialises the whole document in one `StringBuilder` before a
     *     single byte is dispatched, so the memory cost is unbounded and is paid before the request
     *     the caller is waiting on has even started.
     *
     * All three are DoS against the SDK's own caller, which is squarely in scope, and all three are
     * refused BEFORE dispatch as an ordinary typed validation error — the one moment at which
     * refusing costs nothing, because no charge exists yet.
     */
    const val MAX_WRITE_DEPTH = 32
    const val MAX_WRITE_CHARS = 256 * 1024

    class JsonWriteException(message: String) : RuntimeException(message)

    fun write(value: Any?): String {
        val sb = StringBuilder()
        // Identity-based, not equality-based: two structurally equal siblings are perfectly legal
        // and must both be written, whereas the SAME object reachable from inside itself is the
        // cycle. `HashMap.equals` on a self-containing map is itself infinite, so an equality-based
        // seen-set would hang in the very case it exists to detect.
        writeValue(sb, value, 0, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
        return sb.toString()
    }

    private fun budgetExceeded() = JsonWriteException(
        "The request body exceeded the ${MAX_WRITE_CHARS}-character limit this SDK will " +
            "serialise. This is almost always an oversized `metadata` value; send an " +
            "identifier your own system can expand instead of the whole object.",
    )

    private fun checkBudget(sb: StringBuilder) {
        if (sb.length > MAX_WRITE_CHARS) throw budgetExceeded()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, depth: Int, seen: MutableSet<Any>) {
        checkBudget(sb)
        if (depth > MAX_WRITE_DEPTH) {
            throw JsonWriteException(
                "The request body nested deeper than $MAX_WRITE_DEPTH levels — refusing to recurse " +
                    "through it, because doing so would overflow the stack rather than return an " +
                    "error. Check `metadata` for a deeply nested or self-referencing value.",
            )
        }
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON cannot encode a non-finite number: $value" }
                // Negative zero keeps its sign. Collapsing -0.0 to the token `0` would re-create,
                // on the way OUT, exactly the laundering the reader now refuses to do on the way
                // in — and it is what made a round-trip through a stub unable to carry the value
                // at all, hiding the reader's defect from any test built on one.
                if (isNegativeZero(value)) {
                    sb.append("-0.0")
                } else if (value == Math.floor(value) && !value.isInfinite()) {
                    sb.append(value.toLong().toString())
                } else {
                    sb.append(value.toString())
                }
            }
            is Float -> writeValue(sb, value.toDouble(), depth, seen)
            is Map<*, *> -> {
                if (!seen.add(value)) throw cycle()
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v, depth + 1, seen)
                    checkBudget(sb)
                }
                sb.append('}')
                // Removed on the way out so a DAG — the same object referenced twice as SIBLINGS —
                // still serialises. Only a value reachable from INSIDE ITSELF is a cycle.
                seen.remove(value)
            }
            is Iterable<*> -> {
                if (!seen.add(value)) throw cycle()
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v, depth + 1, seen)
                    checkBudget(sb)
                }
                sb.append(']')
                seen.remove(value)
            }
            else -> throw IllegalArgumentException(
                "JSON cannot encode a value of type ${value.javaClass.name}",
            )
        }
    }

    private fun cycle() = JsonWriteException(
        "The request body contains a value that refers to ITSELF, so serialising it would never " +
            "terminate. Check `metadata` for a self-referencing structure (an entity graph, a " +
            "parent/child pair, a memoised object).",
    )

    /**
     * Emit a JSON string, BUDGET-AWARE on every append.
     *
     * ── Why the surrounding checks were not enough ────────────────────────────────────────
     * [checkBudget] ran before [writeValue] was entered and after it returned. For a scalar string
     * that is the wrong side of the work: the ENTIRE value — a caller's oversized `metadata` field,
     * or a map KEY, both of which can be arbitrarily long — was copied into the `StringBuilder`
     * character by character, and only then was the budget consulted and the whole thing thrown
     * away. The allocation the bound exists to prevent had already happened, so a large enough
     * value was still an `OutOfMemoryError` rather than the typed refusal it is supposed to be.
     *
     * The pre-check is on the RAW length and is exact enough to be safe: escaping only ever makes a
     * string longer, so a value that does not fit before escaping cannot fit after it. Arithmetic
     * is done in `Long` so a near-`Int.MAX_VALUE` length cannot overflow the comparison into
     * looking small — which would turn the bound into its own bypass.
     */
    private fun writeString(sb: StringBuilder, s: String) {
        val remaining = MAX_WRITE_CHARS.toLong() - sb.length.toLong()
        if (s.length.toLong() + 2L > remaining) throw budgetExceeded()
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
            // Escaping EXPANDS — a control character becomes six characters — so a string that fit
            // before escaping can still cross the budget while being written. Checked as we go, so
            // the refusal happens at the boundary rather than after the overshoot.
            checkBudget(sb)
        }
        sb.append('"')
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    fun parse(text: String): Any? = Parser(text).parseTopLevel()

    /** Parse and require the top-level value to be an object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val v = parse(text)
        require(v is Map<*, *>) { "expected a JSON object" }
        return v as Map<String, Any?>
    }

    class JsonParseException(message: String) : RuntimeException(message)

    /**
     * The deepest document this reader will descend into.
     *
     * This is a recursive-descent parser, so nesting depth IS stack depth: `[[[[[…` a few tens of
     * thousands deep is a `StackOverflowError` thrown from inside `parseValue`. An `Error` is not a
     * [JsonParseException] and not a `PaylodException`, so it escaped every `catch` that classifies
     * a bad body or attaches an idempotency key — a body the SDK could not read became a stack
     * overflow in the caller's request handler instead of a rejected response.
     *
     * The transport refuses an over-deep body before this parser is ever entered, but this limit is
     * enforced here as well and independently, because [Webhooks] parses caller-supplied bytes that
     * never pass through a transport at all. A guard with one implementation is a guard with one
     * point of failure.
     */
    const val MAX_DEPTH = 64

    private class Parser(private val src: String) {
        private var pos = 0
        private var depth = 0

        fun parseTopLevel(): Any? {
            skipWhitespace()
            val v = parseValue()
            skipWhitespace()
            if (pos != src.length) fail("unexpected trailing characters")
            return v
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) fail("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObjectValue()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else ->
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else fail("unexpected character '$c'")
            }
        }

        /**
         * Enter one nesting level, refusing BEFORE the recursive call rather than after it. Checking
         * on the way out would be checking from a stack frame that may not exist.
         */
        private fun enter() {
            depth++
            if (depth > MAX_DEPTH) {
                fail(
                    "JSON nested deeper than $MAX_DEPTH levels — refusing to recurse through it, " +
                        "because doing so would overflow the stack rather than return an error",
                )
            }
        }

        private fun parseObjectValue(): Map<String, Any?> {
            enter()
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                depth--
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                // A REPEATED OBJECT NAME IS FATAL.
                //
                // RFC 8259 calls duplicate names merely "unpredictable", and last-value-wins — what
                // `out[key] = …` did silently — is the most common choice. On a payments path that
                // is a parser-differential attack surface: `{"id":"pay_A","id":"pay_B"}` binds
                // against whichever name the SDK kept, while an upstream proxy, a WAF, a logger, or
                // the server's own parser may have kept the OTHER one. The same body then means two
                // different payments to two different readers, and the fields at stake are exactly
                // the ones money depends on — the id the response is bound to, the status claim, the
                // receipt and the result code that constitute the evidence. A document whose meaning
                // depends on which parser reads it is not a document we can act on.
                if (out.containsKey(key)) {
                    fail("duplicate object name \"$key\" — a repeated key makes the body's meaning depend on which parser reads it")
                }
                out[key] = parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> {
                        depth--
                        return out
                    }
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            enter()
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                depth--
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> {
                        depth--
                        return out
                    }
                    else -> fail("expected ',' or ']' but found '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("unterminated string")
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) fail("unterminated escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > src.length) fail("bad unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                // `toInt(16)` alone accepts "+12f" and " 12f"; RFC 8259 wants exactly
                                // four hex digits.
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    fail("\\u escape must be followed by four hex digits, found \"$hex\"")
                                }
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        // RFC 8259: a raw control character (U+0000..U+001F) inside a string is
                        // ILLEGAL and must be escaped. Accepting it made this parser lenient in a way
                        // that matters on a payments path: a body could carry required fields that
                        // validate cleanly while the rest of it was not JSON at all, so a 2xx passed
                        // validation on the strength of a document no conforming parser would accept.
                        // A response we cannot parse strictly is a response we do not trust.
                        if (c < ' ') {
                            fail("unescaped control character U+%04X in a string".format(c.code))
                        }
                        sb.append(c)
                    }
                }
            }
        }

        /**
         * RFC 8259 grammar, strictly:
         *
         *     number = [ "-" ] int [ frac ] [ exp ]
         *     int    = "0" / ( digit1-9 *DIGIT )
         *     frac   = "." 1*DIGIT
         *     exp    = ("e" / "E") [ "+" / "-" ] 1*DIGIT
         *
         * The previous implementation allowed every digit run to be EMPTY, so `-`, `1.`, `.5`, `1e`
         * and `1e+` were all consumed as tokens; leading zeros (`007`) were accepted too. Most then
         * failed the `toDoubleOrNull` at the end — but `-` did not (it consumed nothing and produced a
         * confusing failure elsewhere), and `007` parsed happily. Enforce the grammar as written.
         */
        private fun parseNumber(): Any {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++

            // int: a bare "0", or a non-zero digit followed by any digits. No leading zeros.
            if (pos >= src.length || src[pos] !in '0'..'9') fail("a number must have at least one digit")
            if (src[pos] == '0') {
                pos++
                if (pos < src.length && src[pos] in '0'..'9') fail("a number must not have a leading zero")
            } else {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }

            var isDouble = false
            if (pos < src.length && src[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= src.length || src[pos] !in '0'..'9') fail("a fraction must have at least one digit")
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || src[pos] !in '0'..'9') fail("an exponent must have at least one digit")
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }

            val token = src.substring(start, pos)
            if (!isDouble) {
                // NEGATIVE ZERO IS NOT THE INTEGER ZERO, AND MUST NOT BECOME IT HERE.
                //
                // `"-0".toLong()` is `0L`, so a raw JSON `-0` used to arrive downstream as an
                // integral zero — indistinguishable from a genuine `0`. That laundering happened
                // BELOW every check that was written to stop it: `DarajaCatalog.isCanonicalSuccessCode`
                // explicitly lists `-0` as an impostor and rejects it, but only ever saw a `Long`
                // 0 that had already lost the sign, so a signed `{"resultCode":-0}` produced
                // SUCCESS evidence on both the status path and the webhook path. The exact-zero
                // check was correct; it was reading a value this parser had already normalized.
                //
                // The sign is therefore preserved by handing back the `Double` -0.0, which is the
                // one JVM value that represents negative zero distinctly. It is deliberately NOT
                // rejected here: `-0` is legal JSON and may appear in an arbitrary `metadata` map
                // that must round-trip. Rejection belongs at classification, where the canonical
                // success code is decided — and a `Double` is never the canonical success code,
                // so it now fails that test exactly like the quoted `"-0"` always did.
                if (token == "-0") return -0.0
                token.toLongOrNull()?.let { return it }
            }
            return token.toDoubleOrNull() ?: fail("invalid number '$token'")
        }

        private fun parseBoolean(): Boolean {
            if (src.startsWith("true", pos)) {
                pos += 4
                return true
            }
            if (src.startsWith("false", pos)) {
                pos += 5
                return false
            }
            fail("invalid literal")
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4
                return null
            }
            fail("invalid literal")
        }

        private fun skipWhitespace() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        /**
         * The end-of-input sentinel.
         *
         * Spelled as the ESCAPE `\u0000`, never as a literal NUL byte in the source. A raw NUL
         * makes this file `data` rather than text to `file(1)`, and grep then skips it silently
         * -- so a reviewer grepping the tree for, say, the parser's depth bound gets no match and
         * concludes there is none. A parser is the last file in this SDK that should be invisible
         * to review tooling.
         */
        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        private fun next(): Char {
            if (pos >= src.length) fail("unexpected end of input")
            return src[pos++]
        }

        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) fail("expected '$c'")
            pos++
        }

        private fun fail(message: String): Nothing =
            throw JsonParseException("$message at position $pos")
    }
}
