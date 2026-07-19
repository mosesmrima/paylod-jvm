package dev.paylod

import dev.paylod.internal.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ROUND 8 — the final gate before Maven Central, where a release is PERMANENT and can never be
 * deleted or replaced.
 *
 * Every test here carries an `nv-…` tag wired into `scripts/non-vacuity.py`, which reverts the
 * protection in the source, runs the tagged test alone, and requires that it FAILS.
 */
class EighthRoundTest {

    private val testKey = "mp_test_abc123"

    // ══ HIGH 1 — the CLASSIFIER laundered a non-canonical code into terminal failure ═════════
    //
    // `decodeError` has required a canonical lexeme since round 6. `classifyStkResult` — the call
    // that produces the MONEY VERDICT — did not. It went through `normalizeCode`, which TRIMS, so
    // `" 1032"`, `"1032\n"`, `"+1032"` and the string `"1032.0"` all parsed as a non-zero finite
    // number and returned FAILED. A token Daraja never sent became terminal failure evidence, and
    // `PaymentSemantics` turned it into a `FAILED` verdict on a payment whose real state nobody
    // knew. A guard at the decoder is not a guard at the classifier.

    @Test
    @Tag("nv-classifier-canonical")
    fun `a non-canonical failure code is never terminal evidence at the classifier`() {
        // Every one of these is numerically 1032 to `toDoubleOrNull`, and none is a code Daraja
        // writes. `"1032\n"` is the trailing-newline case specifically.
        for (wire in listOf(" 1032", "1032\n", "1032 ", "+1032", "01032", "1032.0", "1.032e3")) {
            assertEquals(
                StkOutcome.PENDING, DarajaCatalog.classifyStkResult(wire),
                "non-canonical code ${wire.replace("\n", "\\n")} classified as terminal",
            )
        }
    }

    @Test
    @Tag("nv-classifier-canonical")
    fun `a non-canonical failure code on a status read yields INDETERMINATE, not FAILED`() {
        for (wire in listOf(" 1032", "1032\n", "+1032", "1032.0")) {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 200,
                        json = paymentJson(
                            status = "failed", resultCode = wire,
                            resultDesc = "Request cancelled by user",
                        ),
                    ),
                ),
            )
            val judged = PaymentSemantics.judge(paylod.status("pay_123"))
            assertEquals(
                PaymentVerdict.INDETERMINATE, judged.verdict,
                "non-canonical code ${wire.replace("\n", "\\n")} produced a terminal verdict",
            )

            val outcome = Outcomes.of(paylod.status("pay_123"))
            assertFalse(outcome.paid)
            assertFalse(outcome.retryable, "laundered code advertised a safe retry")
            assertFalse(outcome.detail?.retryable ?: false)
        }
    }

    @Test
    @Tag("nv-classifier-canonical")
    fun `CONTROL — the canonical forms still classify exactly as they always did`() {
        // The fix must be a NARROWING, not a blanket refusal. A genuine 1032 is still a terminal
        // failure, a genuine 0 is still success, and a genuine pending code is still pending.
        assertEquals(StkOutcome.FAILED, DarajaCatalog.classifyStkResult(1032L))
        assertEquals(StkOutcome.FAILED, DarajaCatalog.classifyStkResult("1032"))
        assertEquals(StkOutcome.SUCCESS, DarajaCatalog.classifyStkResult(0L))
        assertEquals(StkOutcome.SUCCESS, DarajaCatalog.classifyStkResult("0"))
        assertEquals(StkOutcome.PENDING, DarajaCatalog.classifyStkResult(4999L))
        // The dotted business codes are canonical too and must keep working.
        assertEquals(StkOutcome.PENDING, DarajaCatalog.classifyStkResult("500.001.1001"))
        // And a genuine failure still reaches the catalog with its real retryability.
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "failed", resultCode = 1032, resultDesc = "cancelled"))),
        )
        val outcome = Outcomes.of(paylod.status("pay_123"))
        assertEquals("1032", outcome.code)
        assertTrue(outcome.retryable, "a genuine 1032 must still be a retryable failure")
    }

    // ══ HIGH 2 — an exotic throwable took the reconciliation handles down with it ════════════
    //
    // After the collect is ACKNOWLEDGED, the fallback wrapper interpolated `e.message` into its
    // own message BEFORE assigning `idempotencyKey` and `paymentId`. `Throwable.getMessage()` is
    // ordinary overridable code. If it throws, the throw happens inside the string template, so
    // the catch block itself unwinds and the caller gets that second throwable with NO handles —
    // for a charge that is live on a handset right now.

    /** A throwable whose `getMessage()` fails. Entirely ordinary code, no reflection. */
    private class HostileThrowable : RuntimeException() {
        override val message: String
            get() = throw IllegalStateException("getMessage() itself failed")
    }

    @Test
    @Tag("nv-exotic-message")
    fun `a throwable whose getMessage throws still carries both charge handles`() {
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(throwable = HostileThrowable()),
            ),
            apiKey = testKey,
        )

        val e = assertThrows<PaylodException> {
            paylod.collectAndWait(
                CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "key-abc"),
            )
        }
        assertEquals("key-abc", e.idempotencyKey, "the idempotency key was lost")
        assertEquals("pay_123", e.paymentId, "the payment id was lost")
    }

    @Test
    @Tag("nv-exotic-message")
    fun `the hostile throwable really does fail — the fixture can discriminate`() {
        // A fixture that cannot produce the failure it claims to test proves nothing, so the
        // premise is asserted directly.
        assertThrows<IllegalStateException> { HostileThrowable().message }
    }

    // ══ MEDIUM 1 — server-controlled credentials on the webhook surface ══════════════════════

    private val secret = "whsec_test_secret_value_long_enough"

    private fun signedEvent(dataExtra: Map<String, Any?>): Pair<ByteArray, String> {
        val data = linkedMapOf<String, Any?>(
            "paymentId" to "pay_123",
            "status" to "success",
            "amount" to 100,
            "mpesaReceipt" to "SFF6XYZ123",
            "resultCode" to 0,
        )
        data.putAll(dataExtra)
        val body = Json.write(
            linkedMapOf(
                "type" to "payment.success",
                "created" to 1_700_000_000L,
                "data" to data,
            ),
        ).toByteArray()
        return body to Webhooks.sign(body, secret)
    }

    @Test
    @Tag("nv-wh-ident-credential")
    fun `an echoed credential in applicationId, phone or accountRef is REFUSED`() {
        for (field in listOf("applicationId", "phone", "accountRef")) {
            val (body, sig) = signedEvent(mapOf(field to "Bearer mp_live_abcdefghijklmnop"))
            assertThrows<PaylodSignatureVerificationException>(
                "data.$field carried a credential into the typed event",
            ) {
                Webhooks.parseAndVerify(body, sig, secret)
            }
        }
    }

    @Test
    @Tag("nv-wh-ident-credential")
    fun `CONTROL — ordinary values in those fields still verify`() {
        val (body, sig) = signedEvent(
            mapOf("applicationId" to "app_1", "phone" to "254712345678", "accountRef" to "INV-9"),
        )
        val event = Webhooks.parseAndVerify(body, sig, secret)
        assertEquals("app_1", event.data.applicationId)
        assertEquals("254712345678", event.data.phone)
        assertEquals("INV-9", event.data.accountRef)
    }

    @Test
    @Tag("nv-wh-rawmap-scrub")
    fun `verifySignature scrubs credentials and the signing secret out of the raw map`() {
        // `verifySignature` returns the WHOLE parsed body — unknown fields, nested objects, array
        // elements and object NAMES included, none of which any allowlist ever sees.
        val body = Json.write(
            linkedMapOf(
                "type" to "payment.success",
                "echoedAuth" to "Bearer mp_live_abcdefghijklmnop",
                "echoedSecret" to "the secret is $secret ok",
                "nested" to linkedMapOf("deep" to listOf("mp_live_abcdefghijklmnop")),
            ),
        ).toByteArray()
        val map = Webhooks.verifySignature(body, Webhooks.sign(body, secret), secret)

        val rendered = map.toString()
        assertFalse(rendered.contains("mp_live_abcdefghijklmnop"), "a bearer token survived: $rendered")
        assertFalse(rendered.contains(secret), "the signing secret survived: $rendered")
        // Still usable: the fields that carried nothing sensitive are untouched.
        assertEquals("payment.success", map["type"])
    }

    // ══ MEDIUM 2 — simulator parity ══════════════════════════════════════════════════════════

    private val simAck: Map<String, Any?> = mapOf(
        "paymentId" to "pay_sim_1",
        "checkoutRequestId" to "sim_ws_CO_1",
        "status" to "pending",
        "outcomes" to listOf(mapOf("id" to "approve", "label" to "Approve", "status" to "success")),
    )

    @Test
    @Tag("nv-sim-validators")
    fun `simulate collect enforces the SAME amount ceiling and field rules production does`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey)

        // The amount ceiling — 150,001 KES is refused by `collect()` and used to be accepted here.
        assertThrows<PaylodInvalidRequestException> {
            paylod.simulate.collect(SimulateCollectParams(amount = 150_001, idempotencyKey = "k1"))
        }
        // A provided-but-blank reference.
        assertThrows<PaylodInvalidRequestException> {
            paylod.simulate.collect(
                SimulateCollectParams(amount = 10, accountReference = "   ", idempotencyKey = "k2"),
            )
        }
        // An over-long reference and an over-long description.
        assertThrows<PaylodInvalidRequestException> {
            paylod.simulate.collect(
                SimulateCollectParams(amount = 10, accountReference = "x".repeat(13), idempotencyKey = "k3"),
            )
        }
        assertThrows<PaylodInvalidRequestException> {
            paylod.simulate.collect(
                SimulateCollectParams(amount = 10, description = "y".repeat(65), idempotencyKey = "k4"),
            )
        }
        // Every one of those was refused BEFORE anything was dispatched.
        assertEquals(0, t.count, "a refused simulate.collect still hit the wire")
    }

    @Test
    @Tag("nv-sim-validators")
    fun `simulate collect transmits the TRIMMED value it validated`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey)
        paylod.simulate.collect(
            SimulateCollectParams(
                amount = 10, accountReference = "  INV-9  ", description = "  hello  ",
                idempotencyKey = "k5",
            ),
        )
        assertEquals("INV-9", t.calls[0].body?.get("accountRef"))
        assertEquals("hello", t.calls[0].body?.get("description"))
    }

    @Test
    @Tag("nv-sim-key")
    fun `simulate collect returns the effective key and keeps it on every failure path`() {
        val (ok, _) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey)
        val created = ok.simulate.collect(SimulateCollectParams(amount = 10, idempotencyKey = "sim-key-1"))
        assertEquals("sim-key-1", created.idempotencyKey)

        // A malformed simulator response is refused AFTER dispatch — exactly when the key matters.
        val (bad, _) = testClient(
            listOf(Step(status = 202, json = mapOf("paymentId" to "pay_sim_1", "status" to "pending"))),
            apiKey = testKey,
        )
        val e = assertThrows<PaylodException> {
            bad.simulate.collect(SimulateCollectParams(amount = 10, idempotencyKey = "sim-key-2"))
        }
        assertEquals("sim-key-2", e.idempotencyKey, "the simulator lost the effective key")
    }

    @Test
    @Tag("nv-sim-key")
    fun `the key the simulator DISPATCHES is the effective key, not the raw parameter`() {
        // The returned record and the failure envelope can both be right while the header on the
        // wire is wrong — and the header is the one the backend deduplicates on. With the
        // `unsafeGeneratedIdempotencyKey` opt-out the caller supplies NO key at all, so sending
        // `params.idempotencyKey` would put a null on the wire and every retry would be a fresh
        // charge, with nothing in the returned object to reveal it.
        val (paylod, t) = testClient(listOf(Step(status = 202, json = simAck)), apiKey = testKey)
        val created = paylod.simulate.collect(
            SimulateCollectParams.builder()
                .amount(10)
                .unsafeGeneratedIdempotencyKey(true)
                .build(),
        )
        val sent = t.calls[0].headers.mapKeys { it.key.lowercase() }["idempotency-key"]
        assertNotNull(sent, "no Idempotency-Key reached the wire")
        assertEquals(created.idempotencyKey, sent, "the dispatched key is not the effective key")
        assertTrue(created.idempotencyKey.isNotEmpty())
    }

    @Test
    @Tag("nv-sim-credential")
    fun `the simulator refuses its own server-controlled strings when they carry a credential`() {
        for (field in listOf("paymentId", "checkoutRequestId")) {
            val ack = simAck.toMutableMap()
            ack[field] = "Bearer mp_live_abcdefghijklmnop"
            val (paylod, _) = testClient(listOf(Step(status = 202, json = ack)), apiKey = testKey)
            assertThrows<PaylodException>("$field carried a credential onto SimulatedPayment") {
                paylod.simulate.collect(SimulateCollectParams(amount = 10, idempotencyKey = "k"))
            }
        }
        // And the outcome list, which is the simulator's own field and had no check at all.
        val ack = simAck.toMutableMap()
        ack["outcomes"] = listOf(
            mapOf("id" to "approve", "label" to "mp_live_abcdefghijklmnop", "status" to "success"),
        )
        val (paylod, _) = testClient(listOf(Step(status = 202, json = ack)), apiKey = testKey)
        assertThrows<PaylodException> {
            paylod.simulate.collect(SimulateCollectParams(amount = 10, idempotencyKey = "k"))
        }
    }

    // ══ LOW — the outbound writer had no bounds at all ═══════════════════════════════════════

    @Test
    @Tag("nv-json-write-bounds")
    fun `a self-referencing metadata map is refused before dispatch, not as a StackOverflowError`() {
        val cyclic = LinkedHashMap<String, Any?>()
        cyclic["self"] = cyclic

        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = testKey)
        val e = assertThrows<PaylodInvalidRequestException> {
            paylod.collect(
                CollectParams.builder("254712345678", 10)
                    .idempotencyKey("k")
                    .metadata(mapOf("loop" to cyclic))
                    .build(),
            )
        }
        assertNotNull(e.message)
        assertEquals(0, t.count, "the cyclic body was dispatched anyway")
    }

    @Test
    @Tag("nv-json-write-bounds")
    fun `a deeply nested metadata map is refused rather than overflowing the stack`() {
        var deep: Any? = "leaf"
        repeat(2_000) { deep = mapOf("n" to deep) }

        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = testKey)
        assertThrows<PaylodInvalidRequestException> {
            paylod.collect(
                CollectParams.builder("254712345678", 10)
                    .idempotencyKey("k")
                    .metadata(mapOf("deep" to deep))
                    .build(),
            )
        }
        assertEquals(0, t.count)
    }

    @Test
    @Tag("nv-json-write-bounds")
    fun `an enormous metadata value is refused before it is dispatched`() {
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = testKey)
        assertThrows<PaylodInvalidRequestException> {
            paylod.collect(
                CollectParams.builder("254712345678", 10)
                    .idempotencyKey("k")
                    .metadata(mapOf("blob" to "x".repeat(Json.MAX_WRITE_CHARS + 1)))
                    .build(),
            )
        }
        assertEquals(0, t.count)
    }

    @Test
    @Tag("nv-json-write-bounds")
    fun `CONTROL — an ordinary metadata map, and a DAG, still serialise`() {
        // Only a value reachable from INSIDE ITSELF is a cycle. The same object appearing twice as
        // siblings is legal and must still be written, or the fix breaks ordinary callers.
        val shared = mapOf("k" to "v")
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = testKey)
        paylod.collect(
            CollectParams.builder("254712345678", 10)
                .idempotencyKey("k")
                .metadata(mapOf("a" to shared, "b" to shared, "n" to 1, "list" to listOf(1, 2, 3)))
                .build(),
        )
        assertEquals(1, t.count)
        @Suppress("UNCHECKED_CAST")
        val meta = t.calls[0].body?.get("metadata") as Map<String, Any?>
        assertEquals(mapOf("k" to "v"), meta["a"])
        assertEquals(mapOf("k" to "v"), meta["b"])
    }

    // ══ CROSS-SDK CHECKS — the roots found in the sibling SDKs this round ════════════════════

    @Test
    @Tag("nv-json-escaped-key")
    fun `an ESCAPED member name decodes to the same key, and a duplicate key is fatal`() {
        // PHP's Critical was a raw-bytes scan for the literal `"resultCode"` that a JSON-escaped
        // spelling walked straight past. This SDK parses the JSON itself and looks the key up on
        // the DECODED map, so an escaped spelling cannot mean one thing to a scanner and another to
        // the reader — there is no scanner. Asserted rather than assumed.
        // THE FIXTURE MUST ACTUALLY BE ESCAPED. This test previously spelled every key as the
        // literal `resultCode`, which means it passed whether or not escaped-key decoding existed
        // at all — a test guarding PHP's Critical that could not fail. `r` is `r`, so the
        // name below decodes to `resultCode` while sharing not one byte of its spelling.
        val escaped = Json.parseObject("""{"\u0072esultCode":-0}""")
        assertTrue(
            escaped.containsKey("resultCode"),
            "an escaped member name did not decode to the key it denotes",
        )
        // And nothing is left under the RAW escaped spelling.
        // A RAW string: Kotlin processes \u escapes in an ordinary literal, so the non-raw
        // spelling here would just be "resultCode" again and the assertion would be vacuous.
        assertFalse(escaped.containsKey("""\u0072esultCode"""), "the raw escape survived as a key")

        // A repeated name is refused outright rather than resolved last-wins, so it cannot mean
        // different things to different readers.
        assertThrows<Json.JsonParseException> {
            Json.parseObject("""{"resultCode":1032,"resultCode":-0}""")
        }
        // INCLUDING the mixed literal/escaped pair — the shape that defeats a byte-level
        // duplicate check, because the two keys are equal only AFTER decoding.
        assertThrows<Json.JsonParseException> {
            Json.parseObject("""{"resultCode":1032,"\u0072esultCode":-0}""")
        }
        // And the other way round, so the check cannot depend on which spelling came first.
        assertThrows<Json.JsonParseException> {
            Json.parseObject("""{"\u0072esultCode":1032,"resultCode":-0}""")
        }
    }

    @Test
    @Tag("nv-json-escaped-key")
    fun `an escaped-key negative zero is still not the success code`() {
        // The whole point of the PHP bypass: the escaped key decoded to integer zero and was
        // accepted as PAID. Here it reaches the ordinary reader, which keeps `-0` as a negative
        // zero, which is not the canonical success code on any path.
        val parsed = Json.parseObject("""{"\u0072esultCode":-0}""")
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(parsed["resultCode"]))
        assertNotEquals(StkOutcome.SUCCESS, DarajaCatalog.classifyStkResult(parsed["resultCode"]))
    }

    @Test
    @Tag("nv-no-decompression")
    fun `the SDK never asks for a compressed response, so the byte cap is the WIRE cap`() {
        // Python's decompression bomb: its size cap ran AFTER automatic decompression, so a 9 KB
        // gzip body became a 9 MB allocation that defeated both the byte cap and the deadline.
        //
        // The JVM's `java.net.http.HttpClient` neither sends `Accept-Encoding` nor decodes a
        // content coding on its own, and this SDK does not add the header — so the bounded
        // subscriber counts the same bytes that crossed the wire and there is no expansion step to
        // outrun. That is a property of the request we build, so it is asserted on the request.
        val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = testKey)
        paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))

        val headers = t.calls[0].headers.mapKeys { it.key.lowercase() }
        assertFalse(
            headers.containsKey("accept-encoding"),
            "the SDK asked for a content coding it does not bound: $headers",
        )
    }

    @Test
    @Tag("nv-no-credential-in-cause")
    fun `a refusal after dispatch carries no cause chain and no credential in its text`() {
        // Python found a bearer token reachable through a local in an inner traceback frame. The
        // JVM equivalent is a retained request object on an exception `cause` chain, or a data
        // class whose generated `toString()` a crash reporter serialises.
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(throwable = RuntimeException("boom Bearer $testKey extra")),
            ),
            apiKey = testKey,
        )
        val e = assertThrows<PaylodException> {
            paylod.collectAndWait(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }

        // Nothing anywhere on the throwable — message, cause chain, suppressed list — names the key.
        val seen = StringBuilder()
        var cur: Throwable? = e
        var guard = 0
        while (cur != null && guard++ < 16) {
            seen.append(cur.toString())
            cur.suppressed.forEach { seen.append(it.toString()) }
            cur = cur.cause
        }
        assertFalse(seen.contains(testKey), "the API key reached the throwable: $seen")
        // The handles are still there, which is the other half of the guarantee.
        assertEquals("k", e.idempotencyKey)
        assertEquals("pay_123", e.paymentId)
    }
}
