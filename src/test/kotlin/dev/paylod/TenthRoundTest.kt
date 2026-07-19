package dev.paylod

import dev.paylod.internal.CredentialShapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ROUND 10 — conformance with `docs/SDK-CONFORMANCE.md`.
 *
 * ── Why this file is organised by REQUIREMENT and not by finding ──────────────────────────────
 * Nine rounds of review produced 95 findings across four SDKs, and the dominant failure was never
 * that a defect was hard to fix. It was that a fix landing in one SDK never reached the other
 * three: PHP closed the redaction-placeholder-as-receipt defect in round 9, and round 10 found the
 * IDENTICAL defect still open in Node, Python and this SDK — because each SDK was working from its
 * own findings list rather than from a shared contract.
 *
 * So the unit of work is the specification, and each test below names the requirement it proves.
 * A requirement with no test here is a requirement this SDK cannot claim.
 */
class TenthRoundTest {

    private fun paidPayment(receipt: String?, code: Any? = "0") = Payment(
        id = "pay_123",
        status = PaymentStatus.SUCCESS,
        mpesaReceipt = receipt,
        resultCode = code?.toString(),
        resultDesc = null,
    )

    // ── §3.3 — a receipt is validated against a POSITIVE grammar ────────────────────────────

    /**
     * §3.3. The grammar is `^[A-Z0-9]{10}\z`, derived from every real receipt in the paylod
     * fixtures, and it is the same grammar the PHP, Node and Python SDKs enforce.
     */
    @Test
    @Tag("nv-receipt-grammar")
    fun `a receipt must be ten uppercase alphanumerics, and nothing else counts`() {
        // Real fixtures — these MUST still be evidence, or the grammar is over-corrected.
        for (real in listOf("SFF6XYZ123", "RGH4TYU789", "QK21ABCD99", "0123456789", "ABCDEFGHIJ")) {
            assertTrue(PaymentSemantics.isValidReceipt(real), "real receipt rejected: $real")
        }

        // Everything a non-emptiness test used to accept.
        val impostors = listOf(
            "[redacted]",           // THE defect: the SDK's own mask satisfied the old check
            "***",
            "REDACTED",
            "null", "undefined", "N/A", "-", "pending", "0", "",
            "   ",
            "SFF6XYZ12",            // nine — too short
            "SFF6XYZ1234",          // eleven — too long
            "sff6xyz123",           // lowercase
            "SFF6XYZ-23",           // punctuation
            "SFF6 YZ123",           // embedded space
            "SFF6XYZ12é",      // non-ASCII
        )
        for (bad in impostors) {
            assertFalse(PaymentSemantics.isValidReceipt(bad), "impostor accepted as a receipt: $bad")
        }
        assertFalse(PaymentSemantics.isValidReceipt(null))
    }

    /**
     * §3.3 + §7.1. THE anchoring trap, stated as its own test because it is invisible in the
     * pattern.
     *
     * In Java (as in PCRE and Python `re`) `$` also matches immediately before a trailing newline,
     * so a `^[A-Z0-9]{10}$` pattern would ACCEPT `"SFF6XYZ123\n"`. A receipt carrying a smuggled
     * newline is precisely the re-encoded value the grammar exists to reject, and a reviewer reading
     * the pattern cannot see the difference. `Regex.matches` is a full-region match, which is the
     * Kotlin equivalent of `\z`-anchoring.
     */
    @Test
    @Tag("nv-receipt-grammar-anchor")
    fun `a receipt with a trailing newline is refused (the Java dollar-anchor trap)`() {
        assertFalse(PaymentSemantics.isValidReceipt("SFF6XYZ123\n"))
        assertFalse(PaymentSemantics.isValidReceipt("SFF6XYZ123\r\n"))
        assertFalse(PaymentSemantics.isValidReceipt("\nSFF6XYZ123"))
        // The demonstration that this is not a theoretical concern: the `$`-anchored spelling a
        // reviewer would wave through DOES accept it, in this language, today.
        assertTrue(Regex("^[A-Z0-9]{10}$").containsMatchIn("SFF6XYZ123\n"))
    }

    // ── §3.4 — sanitizer output satisfies NOTHING ───────────────────────────────────────────

    /**
     * §3.4. The defect this requirement exists for, end to end: a server echoes a credential into
     * `mpesaReceipt`, the SDK's redaction rewrites it to `[redacted]`, and the resulting non-blank
     * string used to satisfy the evidence check — so HIDING a secret manufactured proof of payment
     * and a `success` claim resolved to PAID.
     */
    @Test
    @Tag("nv-receipt-placeholder")
    fun `a redaction placeholder in the receipt is never payment evidence`() {
        for (mask in listOf("[redacted]", CredentialShapes.MASK, "***", "[FILTERED]", "XXXXXXXXXX")) {
            val p = paidPayment(mask, code = null)
            assertFalse(PaymentSemantics.hasReceipt(p), "$mask counted as a receipt")
            assertEquals(PaymentEvidence.NONE, PaymentSemantics.evidenceFor(p))
            // A `success` claim with a placeholder receipt and NO result code proves nothing.
            assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(p).verdict)
            val outcome = Outcomes.of(p)
            assertFalse(outcome.paid, "$mask produced a PAID outcome")
            assertFalse(outcome.retryable)
            assertNull(outcome.receipt)
        }
    }

    /**
     * §3.4. `XXXXXXXXXX` is the case that proves the placeholder check is INDEPENDENT of the
     * grammar rather than an accident of which characters the current mask happens to use: it
     * satisfies `[A-Z0-9]{10}` exactly, and is still refused.
     */
    @Test
    @Tag("nv-receipt-placeholder")
    fun `a mask that satisfies the receipt grammar is still refused`() {
        assertTrue(Regex("[A-Z0-9]{10}").matches("XXXXXXXXXX"))
        assertFalse(PaymentSemantics.isValidReceipt("XXXXXXXXXX"))
    }

    /** §3.4 — the identifier and correlation surfaces, not just the receipt. */
    @Test
    @Tag("nv-ident-placeholder")
    fun `a sanitizer placeholder is refused as a paymentId, checkoutRequestId and idempotency key`() {
        // Collect ack: a placeholder paymentId names no payment we could ever poll.
        val (client, _) = testClient(
            listOf(Step(status = 202, json = ACK + mapOf("paymentId" to "[redacted]"))),
        )
        val e = assertThrows<PaylodApiException> {
            client.collect("254712345678", 10, idempotencyKey = "key-1")
        }
        assertTrue(e.indeterminate)
        assertTrue(e.message!!.contains("placeholder"), e.message)

        val (client2, _) = testClient(
            listOf(Step(status = 202, json = ACK + mapOf("checkoutRequestId" to "***"))),
        )
        assertThrows<PaylodApiException> {
            client2.collect("254712345678", 10, idempotencyKey = "key-1")
        }

        // The idempotency key itself: two attempts masked the same way would collide.
        val bad = assertThrows<PaylodInvalidRequestException> {
            val (c, _) = testClient(listOf(Step(status = 202, json = ACK)))
            c.collect("254712345678", 10, idempotencyKey = "[redacted]")
        }
        assertTrue(bad.message!!.contains("placeholder"), bad.message)
    }

    // ── §1.1 / §6.4 / §6.5 — form and bounds at EVERY layer, not just the top one ───────────

    /**
     * §1.1. The lowest normalization helper must not launder a non-canonical spelling into a
     * canonical one, even when today's callers happen to guard it.
     *
     * `normalizeCode` used to `.trim()`. Its callers checked the lexeme first, so nothing was
     * reachable through them — but "currently unreachable" is a property of today's call graph,
     * and the requirement is explicit that a guard at one layer is not a guard at the layer below.
     */
    @Test
    @Tag("nv-lower-layer-form")
    fun `a padded result code is never laundered into a canonical one, at any layer`() {
        // The classifier: never SUCCESS, never a terminal FAILED.
        for (padded in listOf(" 0", "0 ", "0\n", "0\t", " 1032", "1032\n", "\t1032")) {
            assertEquals(
                StkOutcome.PENDING, DarajaCatalog.classifyStkResult(padded),
                "$padded classified as something other than ambiguous",
            )
            assertFalse(DarajaCatalog.isCanonicalSuccessCode(padded), "$padded read as success")
            assertFalse(DarajaCatalog.isCanonicalCodeLexeme(padded), "$padded read as canonical")
        }
        // The decoder: never selects a catalog entry, so never a retryable cancellation.
        val cancelled = DarajaCatalog.decodeError("1032")
        assertTrue(cancelled.retryable, "control: a bare 1032 IS the retryable cancellation")
        for (padded in listOf(" 1032", "1032\n", "1032 ")) {
            val d = DarajaCatalog.decodeError(padded)
            assertFalse(d.retryable, "$padded selected the retryable cancellation entry")
            assertEquals("Payment state unknown", d.title)
        }
        // §1.4: a dotted code needs at least two dots. `500.0` is a decimal, not a business code.
        assertFalse(DarajaCatalog.isCanonicalCodeLexeme("500.0"))
        assertTrue(DarajaCatalog.isCanonicalCodeLexeme("500.001.1001"))

        // ── THE LOWEST LAYER, ASSERTED DIRECTLY ─────────────────────────────────────────────
        //
        // Everything above goes through callers that check the lexeme first, so it passes whether
        // or not the bottom helper launders anything — the non-vacuity sweep proved exactly that
        // by restoring the old `.trim()` and watching every assertion above still succeed. A
        // fixture that cannot distinguish the two states proves nothing (§8.5), so the invariant
        // is asserted on the helper itself: `normalizeCode` must be LOSSLESS.
        for (padded in listOf(" 0", "0 ", "0\n", " 1032", "1032\n", "\t1032", "  ")) {
            assertEquals(
                padded, DarajaCatalog.normalizeCode(padded),
                "the lowest normalization helper altered $padded — a padded code became a " +
                    "canonical one BELOW every check written to reject it",
            )
        }
        // And it does not invent one for a value that has none.
        assertEquals("", DarajaCatalog.normalizeCode(null))
    }

    /** §6.4 / §6.5 — every public webhook entry point bounds before it computes or splits. */
    @Test
    @Tag("nv-webhook-bounds")
    fun `webhook bodies and signature headers are bounded before any work is done on them`() {
        val secret = "whsec_test_secret_value"
        val huge = ByteArray(Webhooks.MAX_PAYLOAD_BYTES + 1) { 'a'.code.toByte() }

        // The public SIGNING helper computes an HMAC, so it must bound first (6.4). This was the
        // one public function here without the check.
        assertThrows<PaylodSignatureVerificationException> { Webhooks.sign(huge, secret) }
        // Every verification entry point, byte and string overloads alike.
        assertThrows<PaylodSignatureVerificationException> { Webhooks.verifySignature(huge, "t=1,v1=x", secret) }
        assertThrows<PaylodSignatureVerificationException> { Webhooks.parseAndVerify(huge, "t=1,v1=x", secret) }
        // `verify` is the boolean variant by contract, so the refusal arrives as `false` rather
        // than as a throw. What matters for 6.4 is that it is refused on LENGTH, before the HMAC.
        assertFalse(Webhooks.verify(huge, "t=1,v1=x", secret))

        // 6.5 — the header is length-bounded BEFORE it is split.
        val body = """{"type":"payment.success"}""".toByteArray()
        val vastHeader = "t=1," + "x=y,".repeat(Webhooks.MAX_SIGNATURE_HEADER_CHARS)
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body, vastHeader, secret)
        }

        // §1.1 applied to the header: a whitespace-padded timestamp is not a strict decimal token.
        val sig = Webhooks.sign(body, secret)
        val t = sig.substringAfter("t=").substringBefore(",")
        val v1 = sig.substringAfter("v1=")
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body, "t= $t ,v1=$v1", secret)
        }
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body, "t=$t,v1= $v1", secret)
        }
        // CONTROL (§8.5): the unpadded header is accepted, so the refusals above are about the
        // padding and not about the fixture being broken.
        Webhooks.verifySignature(body, sig, secret)
    }

    // ── §2.1 — the numeric lexeme of a money-critical field survives parsing ────────────────

    /**
     * §2.1. The named failures are `-0` becoming `0` and `1032.0` becoming `1032`. Neither happens,
     * and now the ORIGINAL TOKEN is what the money path actually reads.
     */
    @Test
    @Tag("nv-lexeme-preserved")
    fun `every numeric spelling survives parsing as the token that was written`() {
        val tokens = listOf(
            "0", "-0", "0.0", "0e999", "1032", "1032.0", "1.032e3", "-1032", "1e3", "500.0",
        )
        for (t in tokens) {
            val parsed = dev.paylod.internal.Json.parse(t)
            assertEquals(t, (parsed as dev.paylod.internal.JsonNumber).lexeme, "lexeme lost for $t")
            // And a write round-trip is byte-exact, so a stub cannot hide a spelling from a test.
            assertEquals(t, dev.paylod.internal.Json.write(parsed))
        }

        // The two collapses the requirement names, asserted as NON-collapses.
        val negZero = dev.paylod.internal.Json.parse("-0") as dev.paylod.internal.JsonNumber
        val zero = dev.paylod.internal.Json.parse("0") as dev.paylod.internal.JsonNumber
        assertNotEquals(zero.lexeme, negZero.lexeme)
        val floatCode = dev.paylod.internal.Json.parse("1032.0") as dev.paylod.internal.JsonNumber
        assertNotEquals("1032", floatCode.lexeme)
        // Numerically equal spellings stay DISTINCT documents.
        val exp = dev.paylod.internal.Json.parse("1.032e3") as dev.paylod.internal.JsonNumber
        assertEquals(floatCode.toDouble(), exp.toDouble())
        assertNotEquals(floatCode.lexeme, exp.lexeme)

        // Only the exact token `0` is the canonical success code. Every numeric zero impostor is not.
        assertTrue(DarajaCatalog.isCanonicalSuccessCode(zero))
        for (impostor in listOf("-0", "0.0", "0e999")) {
            assertFalse(
                DarajaCatalog.isCanonicalSuccessCode(dev.paylod.internal.Json.parse(impostor)),
                "$impostor was accepted as the canonical success code",
            )
        }
    }

    /** §2.1, end to end: a raw `1032.0` on the wire never selects the RETRYABLE cancellation entry. */
    @Test
    @Tag("nv-lexeme-preserved")
    fun `a float-spelled cancellation code never becomes a retryable cancellation`() {
        val raw = """{"id":"pay_123","status":"failed","resultCode":1032.0,"mpesaReceipt":null}"""
        val (client, _) = testClient(listOf(Step(status = 200, raw = raw)))
        val outcome = Outcomes.of(client.status("pay_123"))
        assertFalse(outcome.retryable, "1032.0 was laundered into the retryable cancellation entry")
        assertFalse(outcome.paid)
        assertEquals(OutcomeStatus.PENDING, outcome.status)

        // CONTROL (§8.5): the INTEGER 1032 in the same position IS the cancellation, and IS
        // retryable. Without this the test would pass just as well against an SDK that refused
        // everything.
        val ok = """{"id":"pay_123","status":"failed","resultCode":1032,"mpesaReceipt":null}"""
        val (c2, _) = testClient(listOf(Step(status = 200, raw = ok)))
        val good = Outcomes.of(c2.status("pay_123"))
        assertEquals(OutcomeStatus.CANCELLED, good.status)
        assertTrue(good.retryable)
    }

    // ── §2.2 / §2.3 / §2.4 / §2.5 — member names, duplicates, agreement, depth ──────────────

    /** §2.2 — member names are compared AFTER escapes are decoded. */
    @Test
    @Tag("nv-json-escaped-dup")
    fun `an escaped member name is the same member as its literal spelling`() {
        // `resultCode` decodes to `resultCode`. A raw-bytes scan matching only the literal
        // spelling would see two different names and let the duplicate through.
        val body = """{"resultCode":1032,"resultCode":-0}"""
        val e = assertThrows<dev.paylod.internal.Json.JsonParseException> {
            dev.paylod.internal.Json.parse(body)
        }
        assertTrue(e.message!!.contains("duplicate"), e.message)

        // And the decoding itself is right, on a non-duplicate document.
        val ok = dev.paylod.internal.Json.parseObject("""{"resultCode":"0"}""")
        assertEquals("0", ok["resultCode"])
    }

    /**
     * §2.3 — duplicate money-critical members are REFUSED, not resolved.
     *
     * The point is not which value wins. It is that "which value wins" must never be a question
     * this SDK's safety depends on answering the same way as the sender, an upstream proxy, a WAF
     * and a logger. This parser rejects ALL duplicates, which is stricter than the requirement.
     */
    @Test
    @Tag("nv-json-escaped-dup")
    fun `duplicate object names are refused regardless of which value a parser would keep`() {
        val bodies = listOf(
            """{"resultCode":1032,"resultCode":-0}""",
            """{"resultCode":-0,"resultCode":1032}""",
            """{"id":"pay_A","id":"pay_B"}""",
            """{"status":"failed","status":"success"}""",
            """{"mpesaReceipt":"SFF6XYZ123","mpesaReceipt":"[redacted]"}""",
            // Nested, and with an escape on one side only.
            """{"data":{"resultCode":0,"resultCode":1032}}""",
        )
        for (b in bodies) {
            val e = assertThrows<dev.paylod.internal.Json.JsonParseException> {
                dev.paylod.internal.Json.parse(b)
            }
            assertTrue(e.message!!.contains("duplicate"), "$b -> ${e.message}")
        }
        // CONTROL: the same names in SEPARATE objects are perfectly legal.
        dev.paylod.internal.Json.parse("""[{"id":"pay_A"},{"id":"pay_B"}]""")
    }

    /**
     * §2.4 — the scanner and the parser cannot disagree, because there is exactly one reader.
     *
     * PHP needs an incremental scanner running alongside `json_decode` and has to fail closed when
     * the two disagree, since it cannot make `json_decode` preserve a lexeme. This SDK owns its
     * parser, so the lexeme is retained by the ONE reader that produces the values — there is no
     * second opinion to reconcile. This test pins that architectural fact so a future change that
     * introduces a second reader has to confront it.
     */
    @Test
    @Tag("nv-single-reader")
    fun `there is exactly one JSON reader, so no scanner can disagree with a parser`() {
        val main = java.io.File("src/main/kotlin/dev/paylod")
        val readers = main.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            val src = f.readText()
            // Any other JSON entry point would show up as a distinct parse call.
            Regex("""\b(JSONObject|ObjectMapper|Gson|kotlinx\.serialization|Json\.decodeFromString)\b""")
                .findAll(src).map { "${f.name}: ${it.value}" }
        }.toList()
        assertTrue(readers.isEmpty(), "a second JSON reader appeared: $readers")

        // And every production parse goes through the one reader.
        val parseSites = main.walkTopDown().filter { it.extension == "kt" }
            .sumOf { f -> Regex("""Json\.parse(Object)?\(""").findAll(f.readText()).count() }
        assertTrue(parseSites > 0, "found no parse sites at all — this check is measuring nothing")
    }

    /** §2.5 — parse depth is bounded, and exceeding the bound REFUSES. */
    @Test
    @Tag("nv-parse-depth")
    fun `parse depth is bounded and the bound refuses rather than overflows`() {
        val depth = dev.paylod.internal.Json.MAX_DEPTH
        // Just inside: accepted.
        val ok = "[".repeat(depth) + "]".repeat(depth)
        dev.paylod.internal.Json.parse(ok)
        // Just outside: a typed refusal, NOT a StackOverflowError.
        val tooDeep = "[".repeat(depth + 1) + "]".repeat(depth + 1)
        val e = assertThrows<dev.paylod.internal.Json.JsonParseException> {
            dev.paylod.internal.Json.parse(tooDeep)
        }
        assertTrue(e.message!!.contains("nested deeper"), e.message)
        // Far outside: still a typed refusal, which is the case that used to be an Error.
        assertThrows<dev.paylod.internal.Json.JsonParseException> {
            dev.paylod.internal.Json.parse("[".repeat(200_000) + "]".repeat(200_000))
        }
    }

    /** §4.4 — the redaction traversal bound is pinned to the parse bound, asserted not commented. */
    @Test
    @Tag("nv-parse-depth")
    fun `the redaction depth bound is at least the parse depth bound`() {
        assertTrue(
            dev.paylod.internal.Redactor.MAX_DEPTH >= dev.paylod.internal.Json.MAX_DEPTH,
            "REDACT_DEPTH (${dev.paylod.internal.Redactor.MAX_DEPTH}) < " +
                "PARSE_DEPTH (${dev.paylod.internal.Json.MAX_DEPTH}) — content that parses would " +
                "reach a scanner that cannot see it",
        )
    }

    // ── §4.1 / §4.9 — credentials reach no public surface, including the offline ones ───────

    /**
     * §4.1. `resultCode` was the last server-controlled field on a status body with no credential
     * check, and it is copied into `Payment.resultCode`, `PaymentOutcome.code`, `DecodedError.code`
     * and therefore into the GENERATED `toString()` of all three.
     */
    @Test
    @Tag("nv-resultcode-credential")
    fun `a credential echoed into resultCode is refused, not copied into a public object`() {
        val key = "mp_test_abc123"
        val (client, _) = testClient(
            listOf(Step(status = 200, json = paymentJson(resultCode = "Bearer $key-and-more"))),
            apiKey = key,
        )
        val e = assertThrows<PaylodApiException> { client.status("pay_123") }
        assertTrue(e.indeterminate)
        assertFalse(e.toString().contains(key), "the credential survived into the exception")

        // And a `whsec_`-shaped value, which is the credential most likely to be echoed.
        val (c2, _) = testClient(
            listOf(Step(status = 200, json = paymentJson(resultCode = "whsec_abcdefghijkl"))),
        )
        assertThrows<PaylodApiException> { c2.status("pay_123") }
    }

    /**
     * §4.9. The public OFFLINE decoder has no client to redact for it, so it must redact for
     * itself. Both surfaces are checked: the static one (shape masking, all a static function can
     * do) and the instance one (which additionally knows the configured values BY VALUE).
     */
    @Test
    @Tag("nv-decode-error-redaction")
    fun `the offline error decoder redacts on both the static and the instance surface`() {
        val live = "Bearer mp_live_supersecretvalue"
        // Static surface: shape masking, with no client in existence at all.
        val staticDecoded = DarajaCatalog.decodeError("4242", "upstream said: $live")
        assertFalse(staticDecoded.cause.contains("mp_live_supersecretvalue"), staticDecoded.cause)
        assertFalse(staticDecoded.toString().contains("mp_live_supersecretvalue"))

        // A credential echoed into the CODE itself, not just the description.
        val codeDecoded = DarajaCatalog.decodeError("whsec_abcdefghijkl", null)
        assertFalse(codeDecoded.toString().contains("whsec_abcdefghijkl"), codeDecoded.toString())

        // Instance surface: a configured key that matches NO known credential shape. This is the
        // case the static decoder structurally cannot catch, and it is why the instance method
        // does not merely delegate.
        val odd = "zzz-selfhosted-key-9182"
        val (client, _) = testClient(listOf(Step(status = 200, json = ACK)), apiKey = odd)
        val decoded = client.decodeError("4242", "the server echoed $odd back at us")
        assertFalse(decoded.cause.contains(odd), "configured key leaked: ${decoded.cause}")
        assertFalse(decoded.toString().contains(odd))

        // CONTROL (§8.5): ordinary prose still survives, or "redacted" would just mean "destroyed".
        val plain = DarajaCatalog.decodeError("4242", "Insufficient funds in the account")
        assertTrue(plain.cause.contains("Insufficient funds"), plain.cause)
    }

    /** §4.2 — an interpolated server value is length-bounded, not merely redacted. */
    @Test
    @Tag("nv-decode-error-redaction")
    fun `a hostile resultDesc cannot make a decode arbitrarily large`() {
        val huge = "A".repeat(2_000_000)
        val d = DarajaCatalog.decodeError("4242", huge)
        assertTrue(d.cause.length <= 512, "cause was ${d.cause.length} chars — unbounded")
        assertTrue(d.code.length <= 512)
    }

    // ── §2.6 — malformed bytes are refused, never decoded with replacement semantics ────────

    /**
     * §2.6. The demonstration first: replacement decoding turns unreadable bytes into an ordinary
     * nonblank string, and collapses distinct inputs into an identical one.
     */
    @Test
    @Tag("nv-strict-utf8")
    fun `replacement decoding is exactly the laundering this refuses`() {
        val a = byteArrayOf(-1, -2, -1, -2, -1, -2, -1, -2, -1, -2)      // FF FE x5
        val b = byteArrayOf(-64, -128, -64, -128, -64, -128, -64, -128, -64, -128) // C0 80 x5

        // What the JDK's lossy constructor does with them, which is why it may not be used here.
        val lossyA = String(a, Charsets.UTF_8)
        val lossyB = String(b, Charsets.UTF_8)
        assertTrue(lossyA.isNotBlank(), "unreadable bytes became a blank string, not the hazard")
        assertEquals(lossyA, lossyB, "distinct invalid sequences did NOT collapse — fixture is stale")

        // What this SDK does with them.
        for (bytes in listOf(a, b)) {
            assertThrows<dev.paylod.internal.Utf8.InvalidUtf8Exception> {
                dev.paylod.internal.Utf8.decode(bytes, "test input")
            }
        }
        // Valid UTF-8, including multi-byte, still decodes.
        assertEquals("héllo ✓", dev.paylod.internal.Utf8.decode("héllo ✓".toByteArray(Charsets.UTF_8), "x"))
    }

    /** §2.6 — on the real webhook path, end to end, with a genuinely valid signature. */
    @Test
    @Tag("nv-strict-utf8")
    fun `a correctly signed webhook body that is not valid UTF-8 is refused`() {
        val secret = "whsec_test_secret_value"
        // A body that is well-formed JSON in ASCII except for raw invalid bytes inside the receipt.
        val prefix = """{"id":"evt_1","type":"payment.success","data":{"mpesaReceipt":""""
        val suffix = """"}}"""
        val body = prefix.toByteArray(Charsets.UTF_8) +
            byteArrayOf(-1, -2, -1, -2, -1, -2, -1, -2, -1, -2) +
            suffix.toByteArray(Charsets.UTF_8)

        // Signed for real, so the refusal cannot be mistaken for a signature failure.
        val sig = Webhooks.sign(body, secret)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body, sig, secret)
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, e.reason)
        assertTrue(e.message!!.contains("UTF-8"), e.message)

        // CONTROL (§8.5): the identical body with a VALID receipt in place of the bad bytes must be
        // accepted, or this test would pass for the wrong reason.
        val good = (prefix + "SFF6XYZ123" + suffix).toByteArray(Charsets.UTF_8)
        val map = Webhooks.verifySignature(good, Webhooks.sign(good, secret), secret)
        assertEquals("evt_1", map["id"])
    }

    // ── §3.7 — a non-retryable outcome never invites another attempt ────────────────────────

    /**
     * §3.7, enforced as a CATALOG-WIDE INVARIANT rather than as a list of codes.
     *
     * The spec names 17, 26, 1025 and 9999. Seventeen entries actually violated it, which is the
     * argument for the invariant form: a test that checks the four named codes passes while the
     * other thirteen keep telling customers to pay again, and the next entry someone adds is
     * unguarded by construction.
     *
     * Why it matters concretely: `retryable` is a boolean a MERCHANT branches on, but `message` is
     * the sentence a CUSTOMER reads. When they disagree, the customer wins — they tap Pay a second
     * time. None of these codes proves no debit occurred, so the second tap can be a second charge
     * for a payment the first tap may already have completed.
     */
    @Test
    @Tag("nv-no-retry-language")
    fun `no non-retryable catalog entry invites another payment attempt`() {
        val invitation = Regex("try again|retry|pay again|again", RegexOption.IGNORE_CASE)
        val offenders = DarajaCatalog.allEntries
            .filter { !it.retryable && invitation.containsMatchIn(it.customerMessage) }
            .map { "${it.code} (${it.family}): ${it.customerMessage}" }
        assertTrue(
            offenders.isEmpty(),
            "non-retryable entries whose customer message invites another attempt:\n" +
                offenders.joinToString("\n"),
        )
        // The fixture must be able to discriminate (§8.5): assert the catalog is actually populated
        // and actually contains non-retryable entries, so "no offenders" cannot mean "nothing was
        // examined".
        assertTrue(DarajaCatalog.allEntries.size >= 30, "catalog did not load")
        assertTrue(DarajaCatalog.allEntries.count { !it.retryable } >= 15, "nothing to check")
    }

    /** §3.7 — the same invariant on the two synthesised decodes, which are not catalog rows. */
    @Test
    @Tag("nv-no-retry-language")
    fun `the fallback decodes never invite another payment attempt either`() {
        val invitation = Regex("try again|retry|pay again|again", RegexOption.IGNORE_CASE)
        // Unknown, absent, non-canonical and malformed — every route to a synthesised decode.
        for (code in listOf<Any?>(null, "", 4242, "999999", " 0", "0.0", "500.0", "1032\n", true, 1032.0)) {
            val d = DarajaCatalog.decodeError(code, null)
            assertFalse(d.retryable, "synthesised decode for $code was retryable")
            assertFalse(
                invitation.containsMatchIn(d.customerMessage),
                "decode for $code invited another attempt: ${d.customerMessage}",
            )
        }
    }

    /** §3.6 — `retryable` agrees with the verdict at EVERY nesting level, over the cross-product. */
    @Test
    @Tag("nv-retryable-agrees")
    fun `no outcome exposes a nested retryable that contradicts the top-level one`() {
        val codes = listOf(null, "0", "1032", "2001", "4999", "999999", " 0", "0.0", "17", "9999")
        val receipts = listOf(null, "SFF6XYZ123", "[redacted]", "not-a-receipt")
        var checked = 0
        for (claim in PaymentStatus.entries) {
            for (code in codes) {
                for (receipt in receipts) {
                    val out = Outcomes.of(Payment("pay_1", claim, receipt, code, null))
                    if (!out.retryable) {
                        assertFalse(
                            out.detail?.retryable ?: false,
                            "claim=$claim code=$code receipt=$receipt: top-level retryable=false " +
                                "but detail.retryable=true",
                        )
                    }
                    if (out.paid) assertFalse(out.retryable, "a PAID outcome was retryable")
                    checked++
                }
            }
        }
        // §8.5 — the sweep must actually have swept something.
        assertEquals(PaymentStatus.entries.size * codes.size * receipts.size, checked)
        assertTrue(checked >= 100, "cross-product too small to discriminate")
    }

    // ── §3.5 — all seven rows of the required resolution table ──────────────────────────────

    /**
     * §3.5. Every row, INCLUDING the three controls.
     *
     * The controls are the half of the table that a naive over-correction fails: an SDK that
     * answers INDETERMINATE to everything is exactly as non-conformant as one that answers PAID to
     * everything, because it never settles a payment and never lets a merchant retry a genuine
     * failure. Both halves are asserted here so neither can be traded for the other.
     */
    @Test
    @Tag("nv-resolution-table")
    fun `the seven required resolutions, controls included`() {
        fun p(status: PaymentStatus, receipt: String? = null, code: String? = null) =
            Payment("pay_123", status, receipt, code, null)

        // 1. success + code 0 + valid receipt -> PAID.
        val paid = Outcomes.of(p(PaymentStatus.SUCCESS, "SFF6XYZ123", "0"))
        assertTrue(paid.paid)
        assertEquals(OutcomeStatus.SUCCEEDED, paid.status)
        assertEquals("SFF6XYZ123", paid.receipt)
        assertFalse(paid.retryable)

        // 2. pending + code 0 -> NOT paid, indeterminate.
        val pendingZero = Outcomes.of(p(PaymentStatus.PENDING, null, "0"))
        assertFalse(pendingZero.paid)
        assertEquals(OutcomeStatus.PENDING, pendingZero.status)
        assertFalse(pendingZero.retryable)
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(p(PaymentStatus.PENDING, null, "0")).verdict)

        // 3. pending + receipt -> indeterminate.
        val pendingReceipt = Outcomes.of(p(PaymentStatus.PENDING, "SFF6XYZ123", null))
        assertFalse(pendingReceipt.paid)
        assertFalse(pendingReceipt.retryable)
        assertEquals(
            PaymentVerdict.INDETERMINATE,
            PaymentSemantics.judge(p(PaymentStatus.PENDING, "SFF6XYZ123", null)).verdict,
        )

        // 4. failed + receipt + code 1032 -> indeterminate, NOT retryable. The double-charge case.
        val conflict = p(PaymentStatus.FAILED, "SFF6XYZ123", "1032")
        assertEquals(PaymentEvidence.CONFLICT, PaymentSemantics.evidenceFor(conflict))
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(conflict).verdict)
        val conflictOut = Outcomes.of(conflict)
        assertFalse(conflictOut.paid)
        assertFalse(conflictOut.retryable)
        // §3.6 — at EVERY nesting level, not just the top one.
        assertFalse(conflictOut.detail?.retryable ?: false)

        // 5. failed + no evidence -> indeterminate, NOT terminal.
        val bare = p(PaymentStatus.FAILED, null, null)
        assertEquals(PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(bare).verdict)
        val bareOut = Outcomes.of(bare)
        assertEquals(OutcomeStatus.PENDING, bareOut.status)
        assertFalse(bareOut.retryable)

        // ── CONTROL 6. failed + a genuine catalog failure code -> retryable where the catalog says
        // so. 1032 (customer cancelled) is retryable in the catalog; without a contradicting
        // receipt it must stay that way, or the SDK never lets a merchant re-offer payment.
        val genuineCancel = p(PaymentStatus.FAILED, null, "1032")
        assertEquals(PaymentVerdict.FAILED, PaymentSemantics.judge(genuineCancel).verdict)
        val cancelOut = Outcomes.of(genuineCancel)
        assertEquals(OutcomeStatus.CANCELLED, cancelOut.status)
        assertTrue(cancelOut.retryable, "over-correction: a genuine cancellation is no longer retryable")

        // ── CONTROL 6b. A genuine NON-retryable catalog failure is still a terminal FAILED.
        val wrongPin = p(PaymentStatus.FAILED, null, "2001")
        val pinOut = Outcomes.of(wrongPin)
        assertEquals(OutcomeStatus.FAILED, pinOut.status)
        assertFalse(pinOut.paid)

        // ── CONTROL 7. any claim + an unknown/non-canonical code -> indeterminate (§1.5).
        for (code in listOf("999999", " 0", "0.0", "500.0", "1032\n", "+0", "00")) {
            for (claim in PaymentStatus.entries) {
                val out = Outcomes.of(p(claim, null, code))
                assertFalse(out.paid, "code $code with claim $claim produced PAID")
                assertFalse(out.retryable, "code $code with claim $claim invited a retry")
                assertNotEquals(
                    OutcomeStatus.FAILED, out.status,
                    "code $code with claim $claim became a confident terminal failure",
                )
            }
        }
    }
}
