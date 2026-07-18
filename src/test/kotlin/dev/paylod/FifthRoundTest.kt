package dev.paylod

import dev.paylod.internal.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random

/**
 * The fifth-round money-correctness fixes.
 *
 * Every test here carries an `nv-…` tag and is wired into `scripts/non-vacuity.py`, which reverts
 * the protection it guards and requires this test to FAIL. A test that passes with and without its
 * fix proves nothing.
 */
class FifthRoundTest {

    private val secret = "whsec_test"
    private val now = 1_700_000_000L

    private fun failedEvent(resultCode: String, decodedJson: String): String =
        """{"type":"payment.failed","created":$now,"data":{"paymentId":"pay_1","status":"failed",""" +
            """"amount":100,"resultCode":$resultCode,"resultDesc":"nope","decoded":$decodedJson}}"""

    // ══ 3 — the lenient status fallback is gone from every money path ════════════════════════

    @Test
    @Tag("nv-status-strict")
    fun `an unrecognised wire status is REJECTED, never coerced to pending`() {
        // `PaymentStatus.fromWire` used to map anything unknown to PENDING, and the money path used
        // it to BUILD the record after a separate strict check had vetted the map. Two readings of
        // one body; money used the permissive one. The record now comes out of the validator.
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "settled_maybe"))),
        )
        val err = assertThrows<PaylodApiException> { paylod.status("pay_123") }
        assertTrue(err.indeterminate, "an unreadable status body is INDETERMINATE")
        assertTrue(
            err.message!!.contains("settled_maybe"),
            "the message must name the status it refused: ${err.message}",
        )
    }

    @Test
    @Tag("nv-status-typed")
    fun `the validated record is the record the verdict is computed from`() {
        // The status, receipt and result code that reach `Outcomes` are the ones the validator
        // approved — not a second, laxer re-reading of the same map.
        val (paylod, _) = testClient(
            listOf(Step(json = paymentJson(status = "success", resultCode = 0, mpesaReceipt = "SFF6XYZ123"))),
        )
        val payment = paylod.status("pay_123")
        assertEquals(PaymentStatus.SUCCESS, payment.status)
        assertEquals("SFF6XYZ123", payment.mpesaReceipt)
        // A whole JSON number must normalise to "0", not "0.0" — the catalog is keyed on the former.
        assertEquals("0", payment.resultCode)
        assertTrue(Outcomes.of(payment).paid)
    }

    // ══ 4 — `data.decoded` is recomputed, never taken from the payload ════════════════════════

    @Test
    @Tag("nv-wh-decoded-retryable")
    fun `a signed payload cannot advertise retryable for a code that is not`() {
        // Code 1001 is NON-retryable in the canonical catalog. The payload says otherwise, and a
        // handler doing the documented `if (decoded.retryable) recharge()` would have charged again
        // on the sender's say-so. A signature proves WHO sent the bytes, not that their claims about
        // M-Pesa semantics are true.
        val body = failedEvent(
            "1001",
            """{"code":"1001","title":"LIES","cause":"","fix":"","category":"customer",""" +
                """"retryable":true,"customerMessage":"Please try again!"}""",
        )
        val event = Webhooks.parseAndVerifyAt(
            body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
        )
        val decoded = event.data.decoded
        assertNotNull(decoded)
        decoded!!
        assertFalse(decoded.retryable, "retryable must come from the catalog, never from the payload")

        // Every other field is canonical too, not the payload's version of it.
        val canonical = DarajaCatalog.decodeError("1001")
        assertEquals(canonical, decoded)
        assertFalse(decoded.title == "LIES")
    }

    @Test
    @Tag("nv-wh-decoded-malformed")
    fun `a malformed decoded object cannot throw a raw parser exception`() {
        // `DarajaCategory.fromWire` THROWS IllegalArgumentException on an unknown category, and
        // `retryable` was an unchecked cast. Both used to escape a webhook handler as raw parser
        // exceptions. The payload's `decoded` is ignored entirely now, so neither is reachable.
        val body = failedEvent(
            "1001",
            """{"category":"not_a_category","retryable":"yes","code":{"nested":1}}""",
        )
        val event = Webhooks.parseAndVerifyAt(
            body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
        )
        val decoded = event.data.decoded
        assertNotNull(decoded)
        decoded!!
        assertEquals(DarajaCategory.CUSTOMER, decoded.category)
        assertFalse(decoded.retryable)
    }

    // ══ 7 — the amount is an exact, positive, whole, in-range value ═══════════════════════════

    @Test
    @Tag("nv-wh-amount")
    fun `a fractional or out-of-range amount is REFUSED, never truncated or wrapped`() {
        fun reject(amount: String, why: String) {
            val body =
                """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                    """"status":"success","amount":$amount,"resultCode":0,"mpesaReceipt":"SFF6XYZ123"}}"""
            val err = assertThrows<PaylodSignatureVerificationException>(why) {
                Webhooks.parseAndVerifyAt(
                    body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
                )
            }
            assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason, why)
        }

        // `Double.toInt()` TRUNCATED this to 100 and delivered it as a whole-shilling amount.
        reject("100.7", "a fractional amount is not a KES amount")
        // `Long.toInt()` WRAPPED 2^32 + 100 to 100 — a four-billion-shilling event delivered as 100.
        reject("4294967396", "an out-of-range amount must not wrap")
        reject("0", "zero is not a payment")
        reject("-5", "a negative amount is not a payment")

        // A legitimate amount is untouched and converted exactly.
        val ok =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":150000,"resultCode":0,"mpesaReceipt":"SFF6XYZ123"}}"""
        val event = Webhooks.parseAndVerifyAt(
            ok, Webhooks.sign(ok, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
        )
        assertEquals(150_000, event.data.amount)
    }

    // ══ 8a — the replay window is bounded ABOVE as well as below ══════════════════════════════

    @Test
    @Tag("nv-wh-tolerance-max")
    fun `an unbounded positive tolerance is refused — it disables replay protection`() {
        val body =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":100,"resultCode":0,"mpesaReceipt":"SFF6XYZ123"}}"""
        val header = Webhooks.sign(body, secret, now)

        // A window this wide accepts a webhook captured years ago while looking, in a config file,
        // like a positive number that passed validation.
        val err = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, header, secret, Long.MAX_VALUE, now)
        }
        assertEquals(SignatureFailureReason.INSECURE_TOLERANCE, err.reason)

        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, header, secret, Webhooks.MAX_TOLERANCE_SEC + 1, now)
        }
        // The boundary itself is allowed, so the rule is a ceiling and not an off-by-one refusal.
        Webhooks.parseAndVerifyAt(body, header, secret, Webhooks.MAX_TOLERANCE_SEC, now)
    }

    @Test
    @Tag("nv-wh-noclock")
    fun `clock injection is not part of the public webhook API`() {
        // `nowSec` used to be a public parameter on every entry point. The anti-replay check is
        // `abs(nowSec - t) > toleranceSec`, so a caller-supplied clock moves the window anywhere —
        // including onto a captured webhook from last year. It is an internal test seam now.
        //
        // This is reflection over the PUBLIC surface, which is the thing being constrained. It is
        // not a claim that `internal` is enforceable against in-process code (it is not — see
        // SECURITY.md); it is a claim that the documented API no longer offers the parameter.
        val publicVerify = Webhooks::class.java.methods
            .filter { it.name == "parseAndVerify" || it.name == "verify" || it.name == "verifySignature" }
        assertTrue(publicVerify.isNotEmpty(), "the selector must actually match the public methods")
        for (m in publicVerify) {
            assertTrue(
                m.parameterCount <= 4,
                "${m.name} still takes ${m.parameterCount} parameters — a clock parameter is back " +
                    "on the public surface",
            )
        }
    }

    // ══ 6 — responses are bounded before allocation and before recursion ══════════════════════

    @Test
    @Tag("nv-bound-size")
    fun `an unbounded response is refused with the idempotency key, not an OOM`() {
        // Accumulating this used to be the plan. An OutOfMemoryError from a code path that has
        // ALREADY dispatched a charge is not a PaylodException, so it escaped every block that
        // attaches the effective key — a live charge with no handle on it.
        val huge = "x".repeat((1 shl 20) + 1)
        val (paylod, _) = testClient(listOf(Step(status = 202, raw = huge)))
        val err = assertThrows<PaylodIndeterminateException> {
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k-huge"))
        }
        assertEquals("k-huge", err.idempotencyKey, "the key must survive an oversized response")
        assertTrue(err.indeterminate)
    }

    @Test
    @Tag("nv-bound-depth")
    fun `a deeply nested response is refused with the key, not a StackOverflow`() {
        val deep = "[".repeat(200) + "]".repeat(200)
        val (paylod, _) = testClient(listOf(Step(status = 202, raw = deep)))
        val err = assertThrows<PaylodIndeterminateException> {
            paylod.collect(CollectParams("0712345678", 100, idempotencyKey = "k-deep"))
        }
        assertEquals("k-deep", err.idempotencyKey, "the key must survive an over-deep response")
    }

    @Test
    @Tag("nv-json-depth")
    fun `the JSON reader refuses to recurse past its depth limit`() {
        // Enforced INSIDE the parser as well as at the transport, because webhook bodies are parsed
        // from caller-supplied bytes that never pass through a transport at all.
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        val err = assertThrows<Json.JsonParseException> { Json.parse(deep) }
        assertTrue(err.message!!.contains("nested deeper"), "unhelpful message: ${err.message}")

        // A document at the limit still parses, so this is a ceiling and not a blanket refusal.
        val ok = "[".repeat(60) + "]".repeat(60)
        assertNotNull(Json.parse(ok))
    }

    // ══ 5 — the charge handles survive EVERY throwable ════════════════════════════════════════

    @Test
    @Tag("nv-keys-assertionerror")
    fun `an AssertionError after the ack does not lose the charge handles`() {
        // `if (e is Error) throw e` rethrew this bare, with no key and no payment id, for a charge
        // that is live on a handset. An AssertionError is thrown by ordinary application code in a
        // perfectly healthy JVM — an `onPoll` listener with an assertion in it is the routine case.
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
            ),
        )
        val err = assertThrows<PaylodConnectionException> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-assert"),
                WaitOptions.of(timeoutMs = 30_000, onPoll = { throw AssertionError("listener assertion") }),
            )
        }
        assertEquals("k-assert", err.idempotencyKey)
        assertEquals("pay_123", err.paymentId)
        assertTrue(err.message!!.contains("INDETERMINATE"))
    }

    @Test
    @Tag("nv-keys-vmerror")
    fun `a VirtualMachineError still carries the charge handles, as a suppressed exception`() {
        // A genuine VM error is NOT wrapped — allocating a wrapper on the way out of an OOM is the
        // wrong move — but it must not escape unlabelled either. The handles ride along suppressed,
        // so they appear in the stack trace the caller logs.
        val (paylod, _) = testClient(
            listOf(
                Step(status = 202, json = ACK),
                Step(json = paymentJson(status = "pending")),
            ),
        )
        val err = assertThrows<StackOverflowError> {
            paylod.collectAndWait(
                CollectParams("0712345678", 100, idempotencyKey = "k-vm"),
                WaitOptions.of(timeoutMs = 30_000, onPoll = { throw StackOverflowError("boom") }),
            )
        }
        val context = err.suppressed.filterIsInstance<PaylodException>().firstOrNull()
        assertNotNull(context, "a VM error must still carry the charge context")
        assertEquals("k-vm", context!!.idempotencyKey)
        assertEquals("pay_123", context.paymentId)
    }

    // ══ 8b — client and wait options are bounded, and deadlines cannot overflow ════════════════

    @Test
    @Tag("nv-opt-bounds")
    fun `timeouts and retry counts are validated at construction`() {
        // `timeoutMs = 0` is not "no limit" — `Duration.ofMillis(0)` expires every request at once.
        for (bad in listOf(0L, -1L, 600_001L)) {
            assertThrows<PaylodConfigException>("timeoutMs=$bad must be refused") {
                Paylod("mp_test_abc", PaylodOptions.of(timeoutMs = bad))
            }
        }
        for (bad in listOf(-1, 11)) {
            assertThrows<PaylodConfigException>("maxRetries=$bad must be refused") {
                Paylod("mp_test_abc", PaylodOptions.of(maxRetries = bad))
            }
        }
        // The ordinary values still construct.
        Paylod("mp_test_abc", PaylodOptions.of(timeoutMs = 30_000, maxRetries = 2))
    }

    @Test
    @Tag("nv-wait-bounds")
    fun `a non-positive wait timeout is refused, not silently defaulted`() {
        // It used to become 120_000 with no error anywhere, so a caller who asked for something else
        // waited two minutes believing otherwise. Substituting a different value for the one
        // supplied is worse than refusing it.
        for (bad in listOf(0L, -1L, 3_600_001L)) {
            assertThrows<PaylodInvalidRequestException>("WaitOptions timeoutMs=$bad must be refused") {
                WaitOptions.of(timeoutMs = bad)
            }
        }
        assertEquals(120_000L, WaitOptions.DEFAULT.timeoutMs)
    }

    @Test
    @Tag("nv-deadline-overflow")
    fun `a monotonic clock near Long MAX does not collapse the wait deadline`() {
        // `monotonicMillis` is nanoTime-derived with an arbitrary origin, so it can sit anywhere in
        // the Long range — including where `startedAt + timeout` wraps NEGATIVE.
        //
        // The wrap itself is harmless as long as every comparison is done by SUBTRACTION, which
        // cancels it exactly. Comparing ABSOLUTE values is what breaks: `now + delay >= deadline`
        // puts a huge positive against a huge negative, answers "out of time" on the first poll,
        // and `wait()` gives up on a payment it has looked at exactly once.
        //
        // The clock is placed so that the wrong comparison and the right one DISAGREE. There are
        // two steps: a pending read then a success. Correct behaviour polls both and settles;
        // the absolute comparison breaks after the first and throws a timeout for a live charge.
        val transport = StubTransport(
            listOf(
                Step(json = paymentJson(status = "pending")),
                Step(json = paymentJson(status = "success", resultCode = 0, mpesaReceipt = "SFF6XYZ123")),
            ),
        )
        val clock = FakeTimeSource(now = 0, mono = Long.MAX_VALUE - 3_000)
        val paylod = Paylod(
            "mp_test_abc123",
            PaylodOptions.of(transport = transport, allowCustomTransport = true),
            clock,
            Random(1),
        )
        val outcome = paylod.wait("pay_123", WaitOptions.of(timeoutMs = 5_000))
        assertEquals(OutcomeStatus.SUCCEEDED, outcome.status)
        assertEquals(
            2, transport.count,
            "the wait must poll past the first read — a wrapped deadline made it give up after one",
        )
    }

    // ══ Result code ZERO is recognised exactly, never numerically ═════════════════════════════

    /**
     * Values that are numerically zero but are NOT the schema-approved success code. Each one is
     * accepted by some standard JVM parse — `Integer.parseInt("+0")`, `Integer.decode("0x0")`,
     * `Double.parseDouble("0e999")` — and the old rule was `raw.toDoubleOrNull() == 0.0`.
     */
    private val zeroImpostors = listOf("0e999", "+0", "00", "0.0", "-0", "0x0", " 0", "0.00", "-0.0")

    @Test
    @Tag("nv-zero-strict")
    fun `only the integer 0 and the canonical string 0 count as success evidence`() {
        // The canonical forms ARE success.
        assertTrue(DarajaCatalog.isCanonicalSuccessCode(0))
        assertTrue(DarajaCatalog.isCanonicalSuccessCode(0L))
        assertTrue(DarajaCatalog.isCanonicalSuccessCode("0"))
        assertEquals(StkOutcome.SUCCESS, DarajaCatalog.classifyStkResult(0))
        assertEquals(StkOutcome.SUCCESS, DarajaCatalog.classifyStkResult("0"))

        // A float zero is NOT the integer zero, however it is spelled.
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(0.0))
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(-0.0))

        for (impostor in zeroImpostors) {
            assertFalse(
                DarajaCatalog.isCanonicalSuccessCode(impostor),
                "\"$impostor\" must not be the canonical success code",
            )
            assertEquals(
                StkOutcome.PENDING,
                DarajaCatalog.classifyStkResult(impostor),
                "\"$impostor\" must classify as ambiguous, never SUCCESS",
            )
        }
    }

    @Test
    @Tag("nv-zero-evidence")
    fun `a success claim backed only by a zero impostor is INDETERMINATE, never PAID`() {
        // This is where it costs money. On a `success` claim, SUCCESS evidence is the difference
        // between PAID (ship the goods) and INDETERMINATE (wait for the webhook). A crafted,
        // re-encoded or mangled result code must not be able to manufacture that evidence.
        for (impostor in zeroImpostors) {
            val payment = Payment("pay_1", PaymentStatus.SUCCESS, null, impostor, null)
            val judgement = PaymentSemantics.judge(payment)
            assertEquals(
                PaymentVerdict.INDETERMINATE, judgement.verdict,
                "result code \"$impostor\" must not prove a payment settled",
            )
            val outcome = Outcomes.of(payment)
            assertFalse(outcome.paid, "\"$impostor\" must never render as paid")
            assertFalse(outcome.retryable, "an indeterminate payment is never retryable")
        }

        // The canonical code still proves it, so this is a tightening and not a blanket refusal.
        val real = Payment("pay_1", PaymentStatus.SUCCESS, null, "0", null)
        assertEquals(PaymentVerdict.PAID, PaymentSemantics.judge(real).verdict)
        assertTrue(Outcomes.of(real).paid)
    }

    @Test
    @Tag("nv-zero-nolaunder")
    fun `a JSON float zero is not laundered into the canonical success code`() {
        // The whole-Double-to-integer collapse is a catalog-lookup convenience. Applied to zero it
        // would manufacture "0" out of a JSON `0.0`, defeating the check above one layer earlier.
        //
        // A RAW body is required here: the SDK's own writer emits a whole `Double` without its
        // fraction, so `Json.write(0.0)` is the text `0` and a float zero cannot survive a
        // round-trip through the stub. What is under test is what happens when the SERVER puts
        // `0.0` on the wire, which only a raw body can express.
        val (paylod, _) = testClient(
            listOf(
                Step(
                    raw = """{"id":"pay_123","status":"success","mpesaReceipt":null,""" +
                        """"resultCode":0.0,"resultDesc":null}""",
                ),
            ),
        )
        val payment = paylod.status("pay_123")
        assertFalse(payment.resultCode == "0", "a float zero must not become the canonical \"0\"")
        assertFalse(Outcomes.of(payment).paid, "a float zero is not proof a payment settled")

        // A genuine integer zero still is.
        val (ok, _) = testClient(
            listOf(
                Step(
                    raw = """{"id":"pay_123","status":"success","mpesaReceipt":null,""" +
                        """"resultCode":0,"resultDesc":null}""",
                ),
            ),
        )
        assertTrue(Outcomes.of(ok.status("pay_123")).paid)
    }

    @Test
    @Tag("nv-zero-webhook")
    fun `a signed payment success carrying a zero impostor is refused`() {
        // The webhook path runs the same semantic model, so the same rule has to hold there — that
        // is the point of having one model rather than two agreeing implementations.
        for (impostor in listOf("\"0e999\"", "\"+0\"", "\"00\"", "\"-0\"", "0.0")) {
            val body =
                """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                    """"status":"success","amount":100,"resultCode":$impostor}}"""
            val err = assertThrows<PaylodSignatureVerificationException>(
                "resultCode $impostor must not evidence a success",
            ) {
                Webhooks.parseAndVerifyAt(
                    body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
                )
            }
            assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
        }

        // The canonical integer zero is accepted.
        val good =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":100,"resultCode":0}}"""
        val event = Webhooks.parseAndVerifyAt(
            good, Webhooks.sign(good, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
        )
        assertEquals("pay_1", event.data.paymentId)
    }

    // ══ A cross-check: the money surface exposes no lenient status parse at all ════════════════

    @Test
    @Tag("nv-no-lenient")
    fun `PaymentStatus offers no lenient wire parse`() {
        // The fix was DELETING the permissive fallback, not leaving it unused. A permissive fallback
        // that exists is one a future caller reaches for, and the comment saying when it is safe is
        // not a mechanism.
        val names = PaymentStatus.Companion::class.java.declaredMethods.map { it.name }
        assertFalse("fromWire" in names, "a lenient status parse is back: $names")
        assertTrue("parseWire" in names, "the strict parse must still be there")
        assertNull(PaymentStatus.parseWire("settled_maybe"))
        assertEquals(PaymentStatus.SUCCESS, PaymentStatus.parseWire("success"))
    }
}
