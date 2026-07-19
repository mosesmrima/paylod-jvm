package dev.paylod.internal

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * STRICT UTF-8 decoding for every money-critical byte sequence.
 *
 * ── Why `String(bytes, UTF_8)` is not acceptable here ─────────────────────────────────────────
 * The JDK's `String(ByteArray, Charset)` constructor — and `ByteArray.toString(Charsets.UTF_8)`,
 * and `HttpResponse.BodyHandlers.ofString()` — decode with REPLACE semantics. Malformed input is
 * not rejected; it is silently rewritten to `U+FFFD REPLACEMENT CHARACTER`. Two consequences,
 * both of which reach the money path:
 *
 *   • MALFORMED BYTES BECOME A NONBLANK STRING. A receipt field carrying the raw bytes
 *     `FF FE FF FE FF FE FF FE FF FE` is not text at all, but it decodes to ten replacement
 *     characters — a nonblank string. Under the pre-round-10 evidence rule ("is the receipt
 *     nonblank?") that WAS proof of payment. The receipt grammar closes that particular door, but
 *     the same laundering applies to every other field: payment ids, checkout request ids, result
 *     descriptions, and the idempotency key echoed back in a body.
 *
 *   • DISTINCT INPUTS COLLAPSE INTO ONE STRING. Every invalid sequence maps to the SAME
 *     `U+FFFD`, so `FF FE` and `C0 80` and a truncated three-byte sequence all become the
 *     identical string. Anything that compares, binds or correlates on the decoded value —
 *     the id-binding check, idempotency-key matching, HMAC input built from a decoded string —
 *     then treats genuinely different byte sequences as equal. A response that answers a
 *     DIFFERENT question can be made to satisfy a binding check that way.
 *
 * Neither is a theoretical concern about exotic encodings. Both are ways for a body the SDK cannot
 * actually read to pass a check written on the assumption that it could.
 *
 * So: `REPORT` on malformed AND on unmappable input, and the failure is a typed refusal. A body we
 * cannot decode is a body we do not act on — the same posture as a body we cannot parse.
 *
 * Requirement 2.6.
 */
internal object Utf8 {

    class InvalidUtf8Exception(message: String) : RuntimeException(message)

    /**
     * Decode `bytes` as UTF-8, REFUSING anything that is not valid UTF-8.
     *
     * A fresh `CharsetDecoder` per call: `CharsetDecoder` is stateful and explicitly NOT
     * thread-safe, and this is called from whatever thread the caller's HTTP framework or the
     * SDK's own transport happens to be on. A shared instance would be a data race whose symptom
     * is a mis-decoded payment body — an error that would look like a server bug forever.
     */
    fun decode(bytes: ByteArray, what: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw InvalidUtf8Exception(
                "$what is not valid UTF-8. It is refused rather than decoded with replacement " +
                    "characters: doing so would turn unreadable bytes into an ordinary nonblank " +
                    "string, and would collapse distinct invalid sequences into the same value.",
            )
        }
    }
}
