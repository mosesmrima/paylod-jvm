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
internal object Json {

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                require(value.isFinite()) { "JSON cannot encode a non-finite number: $value" }
                // Emit whole doubles without a trailing ".0" so 100.0 serialises as 100.
                if (value == Math.floor(value) && !value.isInfinite()) {
                    sb.append(value.toLong().toString())
                } else {
                    sb.append(value.toString())
                }
            }
            is Float -> writeValue(sb, value.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException(
                "JSON cannot encode a value of type ${value.javaClass.name}",
            )
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
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

    private class Parser(private val src: String) {
        private var pos = 0

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

        private fun parseObjectValue(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a string key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return out
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
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < src.length && src[pos] == '.') {
                isDouble = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            if (!isDouble) {
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

        private fun peek(): Char = if (pos < src.length) src[pos] else ' '

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
