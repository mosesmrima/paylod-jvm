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
 * The sixth-round fixes.
 *
 * Every test here carries an `nv-…` tag and is wired into `scripts/non-vacuity.py`, which reverts
 * the protection it guards and requires this test to FAIL.
 */
class SixthRoundTest {

    private val secret = "whsec_test"
    private val now = 1_700_000_000L

    // ══ 1 — a RAW `-0` is not laundered into the canonical success code ═══════════════════════

    /**
     * The defect these guard was one layer BELOW the check that was supposed to stop it.
     *
     * `DarajaCatalog.isCanonicalSuccessCode` names `-0` as an impostor and rejects it, and the
     * fifth-round suite proved that for the QUOTED `"-0"`. But a raw, unquoted JSON `-0` never
     * reached it as `-0`: `"-0".toLong()` is `0L`, so the reader handed downstream an integral zero
     * with the sign already gone, and the exact-zero check — correctly — said yes. The guard was
     * fine; it was reading a value that had been normalized before it got there.
     */
    @Test
    @Tag("nv-rawzero-parse")
    fun `the JSON reader preserves a raw -0 instead of collapsing it to an integer zero`() {
        val parsed = Json.parse("-0")
        assertFalse(parsed is Long, "a raw -0 must not arrive as an integral zero: got $parsed")
        assertTrue(parsed is Double)
        assertTrue(
            java.lang.Double.doubleToRawLongBits(parsed as Double) != 0L,
            "the sign bit must survive, otherwise -0 and 0 are indistinguishable downstream",
        )
        // And it is therefore NOT the canonical success code, which is the whole point.
        assertFalse(DarajaCatalog.isCanonicalSuccessCode(parsed))

        // A genuine integer zero is untouched — this is a tightening, not a blanket change.
        assertEquals(0L, Json.parse("0"))
        assertTrue(DarajaCatalog.isCanonicalSuccessCode(Json.parse("0")))

        // The WRITER must not re-launder it on the way out either. `Json.write(-0.0)` used to emit
        // the token `0`, which is why a raw impostor could not survive a round-trip through a stub
        // and this defect stayed invisible to any test built on one.
        assertEquals("-0.0", Json.write(-0.0))
        assertEquals("0", Json.write(0.0))
    }

    @Test
    @Tag("nv-rawzero-status")
    fun `a status read claiming success on a raw -0 result code is never PAID`() {
        // A RAW body is mandatory here. The SDK's own writer emits `Json.write(-0.0)` — and, before
        // this round, `Json.write(0.0)` too — without the sign or fraction, so a `-0` impostor
        // cannot survive a round-trip through a stub that serialises a map. What is under test is
        // what happens when the SERVER puts `-0` on the wire, which only a raw body can express.
        val (paylod, _) = testClient(
            listOf(
                Step(
                    raw = """{"id":"pay_123","status":"success","mpesaReceipt":null,""" +
                        """"resultCode":-0,"resultDesc":null}""",
                ),
            ),
        )
        val payment = paylod.status("pay_123")
        assertNotEquals("0", payment.resultCode, "a raw -0 must not become the canonical \"0\"")
        assertEquals(
            PaymentVerdict.INDETERMINATE, PaymentSemantics.judge(payment).verdict,
            "a raw -0 is not proof that money moved",
        )
        assertFalse(Outcomes.of(payment).paid, "a raw -0 must never render as paid")

        // The canonical integer zero still settles, so the success path is intact.
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
    @Tag("nv-rawzero-webhook")
    fun `a signed payment success carrying a raw -0 result code is refused`() {
        // The delivery channel that most often triggers fulfilment runs the same model, so the same
        // rule has to hold here. The body is built as TEXT, for the same reason as above.
        val body =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":100,"resultCode":-0}}"""
        val err = assertThrows<PaylodSignatureVerificationException>(
            "a raw -0 must not evidence a successful payment",
        ) {
            Webhooks.parseAndVerifyAt(
                body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
            )
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)

        // The canonical integer zero is accepted, signed identically.
        val good =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":100,"resultCode":0}}"""
        val event = Webhooks.parseAndVerifyAt(
            good, Webhooks.sign(good, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
        )
        assertEquals("0", event.data.resultCode)
    }

    // ══ 2 — a stalled response body cannot park the caller forever ═══════════════════════════

    /**
     * A REAL loopback server that sends headers and then stops writing.
     *
     * The stub transport cannot express this defect at all: it hands back a complete `String`, so
     * the hazard — a body that never finishes arriving — has no representation in it. Only a socket
     * that goes quiet after the header block reproduces what a stalled peer actually does.
     */
    private class StallingServer : AutoCloseable {
        private val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        private val held = java.util.Collections.synchronizedList(mutableListOf<java.net.Socket>())
        val port: Int get() = server.localPort

        private val thread = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    held.add(socket)
                    // Announce a body, send the headers, then never send the body. The socket stays
                    // OPEN — this is a stall, not a close, and a close would surface as an ordinary
                    // end-of-stream rather than the indefinite park under test.
                    val out = socket.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: 200\r\n\r\n"
                            ).toByteArray()
                    )
                    out.flush()
                }
            } catch (e: Exception) {
                // The socket closing during teardown is the expected way out of this loop.
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            synchronized(held) { held.forEach { runCatching { it.close() } } }
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    @Test
    @Tag("nv-body-deadline")
    fun `a response that stalls after the headers is abandoned, not waited on forever`() {
        StallingServer().use { server ->
            val paylod = Paylod(
                "mp_test_abc123",
                PaylodOptions.of(
                    baseUrl = "http://127.0.0.1:${server.port}",
                    allowInsecureBaseUrl = true,
                    maxRetries = 0,
                    timeoutMs = 1_000,
                ),
            )

            val started = System.currentTimeMillis()
            val err = assertThrows<PaylodException>(
                "a stalled body must not park the caller indefinitely",
            ) {
                paylod.status("pay_123")
            }
            val elapsed = System.currentTimeMillis() - started

            // The deadline is END-TO-END. Before this fix the request timer stopped at the headers
            // and the body read had no bound at all, so this call never returned.
            assertTrue(
                elapsed < 20_000,
                "the read was not bounded by the deadline: it took ${elapsed}ms",
            )

            // And the money state is reported honestly. The request WAS dispatched, so a charge may
            // exist; the one thing that must not happen is a silent retry under a fresh key.
            assertTrue(
                err is PaylodIndeterminateException,
                "a stalled body after dispatch is INDETERMINATE, not a clean failure: $err",
            )
        }
    }

    // ══ 3 — a webhook body is size-bounded BEFORE any work is done on it ══════════════════════

    @Test
    @Tag("nv-wh-maxbytes")
    fun `an oversized webhook body is refused before conversion, HMAC or parsing`() {
        val huge = ByteArray(Webhooks.MAX_PAYLOAD_BYTES + 1) { '.'.code.toByte() }
        val err = assertThrows<PaylodSignatureVerificationException> {
            // Deliberately a WELL-FORMED signature header: the point is that the body is refused on
            // SIZE, before the HMAC over those bytes is ever computed, so an unauthenticated sender
            // cannot make the process do unbounded work by guessing at a signature.
            Webhooks.parseAndVerifyAt(
                huge, "t=$now,v1=${"a".repeat(64)}", secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
            )
        }
        assertEquals(SignatureFailureReason.INVALID_PAYLOAD, err.reason)
        assertTrue(
            err.message!!.contains("${Webhooks.MAX_PAYLOAD_BYTES}"),
            "the refusal must name the limit: ${err.message}",
        )

        // EVERY overload enforces it — a bound with one entry point is a bound with one bypass.
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(huge, "t=$now,v1=x", secret)
        }
        assertFalse(Webhooks.verify(huge, "t=$now,v1=x", secret))
        val hugeText = ".".repeat(Webhooks.MAX_PAYLOAD_BYTES + 1)
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerify(hugeText, "t=$now,v1=x", secret)
        }
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(hugeText, "t=$now,v1=x", secret)
        }
        assertFalse(Webhooks.verify(hugeText, "t=$now,v1=x", secret))

        // An ordinary event is unaffected.
        val body =
            """{"type":"payment.success","created":$now,"data":{"paymentId":"pay_1",""" +
                """"status":"success","amount":100,"resultCode":0}}"""
        assertNotNull(
            Webhooks.parseAndVerifyAt(
                body, Webhooks.sign(body, secret, now), secret, Webhooks.DEFAULT_TOLERANCE_SEC, now,
            ),
        )
    }

    // ══ 4 — the simulator validates its own response fields exactly ═══════════════════════════

    @Test
    @Tag("nv-sim-fields")
    fun `a malformed simulator outcome list is rejected, never silently dropped`() {
        // `mapNotNull { … ?: return@mapNotNull null }` discarded any entry that was not a map, and
        // `?: ""` filled a missing id/label/status with an empty string. A simulator response that
        // was broken in exactly the way an integration test exists to catch therefore produced a
        // shorter, quieter list and a GREEN test.
        val (paylod, _) = testClient(
            listOf(
                Step(
                    status = 202,
                    raw = """{"paymentId":"pay_123","status":"pending","checkoutRequestId":"ws_1",""" +
                        """"outcomes":[{"id":"success","label":"Paid","status":"success"},"garbage"]}""",
                ),
            ),
            simulate = true,
        )
        assertThrows<PaylodApiException>("a non-object outcome entry must be refused") {
            paylod.simulate.collect()
        }

        // A missing field inside an otherwise well-formed entry is refused too.
        val (missing, _) = testClient(
            listOf(
                Step(
                    status = 202,
                    raw = """{"paymentId":"pay_123","status":"pending","checkoutRequestId":"ws_1",""" +
                        """"outcomes":[{"id":"success","label":"Paid"}]}""",
                ),
            ),
            simulate = true,
        )
        assertThrows<PaylodApiException> { missing.simulate.collect() }

        // A well-formed response still works.
        val (ok, _) = testClient(
            listOf(
                Step(
                    status = 202,
                    raw = """{"paymentId":"pay_123","status":"pending","checkoutRequestId":"ws_1",""" +
                        """"outcomes":[{"id":"success","label":"Paid","status":"success"}]}""",
                ),
            ),
            simulate = true,
        )
        val created = ok.simulate.collect()
        assertEquals("pay_123", created.paymentId)
        assertEquals(1, created.outcomes.size)
    }

    @Test
    @Tag("nv-sim-webhookqueued")
    fun `a missing or non-boolean webhookQueued is refused, never reported as true`() {
        // `ack["webhookQueued"] != false` reported TRUE for a missing field, for a null, and for a
        // string — so "the webhook was queued" was asserted on the strength of the field's ABSENCE.
        // An integration test asserting `webhookQueued` then passed against a simulator that had
        // queued nothing at all.
        fun settleWith(fragment: String): Paylod = testClient(
            listOf(
                Step(
                    raw = """{"paymentId":"pay_123","status":"failed","mpesaReceipt":null,""" +
                        """"resultCode":1032,"resultDesc":"cancelled"$fragment}""",
                ),
            ),
            simulate = true,
        ).first

        for (broken in listOf("", ""","webhookQueued":null""", ""","webhookQueued":"yes"""")) {
            assertThrows<PaylodApiException>(
                "webhookQueued fragment <$broken> must be refused rather than assumed true",
            ) {
                settleWith(broken).simulate.outcome("pay_123", SimOutcomeId.USER_CANCELLED)
            }
        }

        // An explicit boolean is honoured, in both directions.
        assertTrue(
            settleWith(""","webhookQueued":true""")
                .simulate.outcome("pay_123", SimOutcomeId.USER_CANCELLED).webhookQueued,
        )
        assertFalse(
            settleWith(""","webhookQueued":false""")
                .simulate.outcome("pay_123", SimOutcomeId.USER_CANCELLED).webhookQueued,
        )
    }

    // ══ 5 — a rejected baseUrl is not echoed verbatim into an exception message ═══════════════

    @Test
    @Tag("nv-baseurl-redact")
    fun `a rejected baseUrl never leaks its userinfo or query string`() {
        // The message from this path lands in ordinary configuration-error logging, and redaction
        // does not exist yet at construction time — so echoing the raw URL published whatever
        // credential the operator had embedded in it. The rejection must still be USEFUL, so the
        // host survives; the parts that carry secrets do not.
        val leaky = listOf(
            "https://user:sup3rsecret@attacker.example/v1",
            "https://attacker.example/v1?token=sup3rsecret",
            "https://attacker.example/v1#sup3rsecret",
        )
        for (url in leaky) {
            val err = assertThrows<PaylodConfigException> {
                Paylod("mp_test_abc123", PaylodOptions.of(baseUrl = url))
            }
            val msg = err.message!!
            assertFalse(
                msg.contains("sup3rsecret"),
                "the secret embedded in <$url> leaked into the message: $msg",
            )
            assertFalse(msg.contains(url), "the raw URL must not be echoed verbatim: $msg")
            // Still diagnosable: the operator has to be able to tell WHICH origin was refused.
            assertTrue(
                msg.contains("attacker.example"),
                "the sanitized form must keep the host so the error is actionable: $msg",
            )
        }
    }
}
