package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.Redactor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.lang.reflect.Modifier

/**
 * ROUND 9 — the permanent adversarial credential sweep, plus the round-9 findings.
 *
 * ── Why a SWEEP rather than more per-field tests ──────────────────────────────────────────────
 * Every credential leak this SDK has shipped had the same shape and was found the same way: a
 * reviewer noticed one field. The fix was for that field. The next round found another field, on
 * another type, with the same root cause — server-controlled data reaching a public surface without
 * passing through redaction.
 *
 * A test per field cannot close that, because the failure mode is *the field nobody thought of*.
 * So this sweep does not enumerate fields at all. It enumerates TYPES — every public exception and
 * every public data class in `dev.paylod` — discovers them from the COMPILED CLASS FILES rather
 * than from a list a human maintains, builds each one from a hostile server response that echoes
 * both configured credentials into every string it can reach at several nesting depths, and then
 * asserts that neither credential is reachable from the finished object by any route an integrator
 * has: `toString()`, the exception message, the cause chain, or any nested field.
 *
 * The self-check at the end is the part that keeps it honest. A sweep that silently skips a type it
 * could not construct is a sweep that reports success for types it never looked at — which is
 * exactly the vacuous test this round was asked to eliminate elsewhere. So discovery is compared
 * against coverage, and an unconstructed public type FAILS the sweep.
 */
class NinthRoundTest {

    private companion object {
        /** The two credentials a hostile response would echo. Both are exact configured values. */
        // DELIBERATELY `mp_test_`: a custom transport is structurally refused for a live key, so
        // a live-shaped key here would give every scenario a real HTTP client and quietly run none
        // of them. The redaction question is identical either way — this is the configured
        // credential, and it is what a hostile response echoes back.
        const val API_KEY = "mp_test_sweepaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val WEBHOOK_SECRET = "whsec_sweepbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        /** Where the compiled main classes land. Discovery reads this, not a hand-written list. */
        val CLASSES_DIR = File("build/classes/kotlin/main/dev/paylod")

        val REDACTOR = Redactor(listOf(API_KEY, WEBHOOK_SECRET))
    }

    // ══ 1. THE TRAVERSAL BOUND ══════════════════════════════════════════════════════════════

    @Test
    @Tag("nv-redact-depth-pin")
    fun `the redaction depth bound is never shallower than the parser's`() {
        // Python found its redact depth was 12 against a parse bound of 64; Node's was 8. This
        // SDK's was 8 against 64 as well — so every structure between depth 9 and 64 parsed
        // successfully and then met a scanner that gave up on it.
        //
        // The invariant, not the number, is what matters: anything the PARSER will construct, the
        // REDACTOR must be able to walk. A redactor shallower than its parser has a region of
        // inputs it cannot see, and whether that region is a leak or merely a blind spot depends
        // only on which way the recursion happens to fail today.
        assertTrue(
            Redactor.MAX_DEPTH >= Json.MAX_DEPTH,
            "redaction depth (${Redactor.MAX_DEPTH}) is shallower than the parse depth " +
                "(${Json.MAX_DEPTH}) — structures exist that this SDK will parse but cannot scan",
        )
    }

    @Test
    @Tag("nv-redact-depth-pin")
    fun `a credential at the deepest parseable position is still redacted, and over-deep fails closed`() {
        // Built at exactly the depth the parser permits, so this is not a hypothetical structure —
        // it is one a response can actually deliver.
        var deep: Any? = "the key is $API_KEY"
        repeat(Json.MAX_DEPTH) { deep = mapOf("n" to deep) }

        assertFalse(
            flatten(REDACTOR.body(deep)).contains(API_KEY),
            "a credential at the deepest parseable depth survived redaction",
        )

        // And past the bound the traversal must REPLACE rather than return. "We could not look" and
        // "there is nothing there" are different answers; only one is safe to act on.
        var tooDeep: Any? = "the key is $API_KEY"
        repeat(Json.MAX_DEPTH + 5) { tooDeep = mapOf("n" to tooDeep) }
        assertFalse(
            flatten(REDACTOR.body(tooDeep)).contains(API_KEY),
            "an over-deep structure leaked rather than failing closed",
        )
        assertTrue(
            REDACTOR.containsCredentialDeep(tooDeep),
            "the refusal scan did not fail closed on a structure too deep to finish scanning",
        )
    }

    // ══ 2. THE ADVERSARIAL SWEEP ════════════════════════════════════════════════════════════

    @Test
    @Tag("nv-adversarial-sweep")
    fun `no public type produced from a hostile server response can carry a configured credential`() {
        val discovered = discoverPublicSurfaceTypes()
        assertTrue(
            discovered.size >= 15,
            "discovery found only ${discovered.size} public types — the class directory is " +
                "probably wrong, and a sweep that finds nothing passes trivially",
        )

        // Every object below came OUT of the SDK, driven by a server response that echoes both
        // credentials into every string it can reach. Constructing the data classes directly would
        // prove nothing: a data class handed a credential obviously contains one. The question is
        // whether the SDK's own paths can be made to put one there.
        val produced = captureFromHostileResponses()

        val failures = mutableListOf<String>()
        // Types seen ANYWHERE in the walk, not just at the root. `WebhookEventData` is only ever
        // reached through a `WebhookEvent`, and it is exactly as public.
        val covered = mutableSetOf<Class<*>>()
        for ((label, obj) in produced) {
            for ((route, text) in reachableStrings(obj, covered)) {
                if (text.contains(API_KEY)) failures += "[$label] leaked the API KEY via $route"
                if (text.contains(WEBHOOK_SECRET)) failures += "[$label] leaked the WEBHOOK SECRET via $route"
            }
        }

        // THE SELF-CHECK. A sweep that silently skips a type reports success for something it never
        // looked at — the vacuous test this round was asked to eliminate. Coverage must account for
        // every discovered public type, either by producing one or by an explicit, justified
        // exemption.
        val unexplained = discovered.filterNot { d ->
            covered.any { d.isAssignableFrom(it) } || EXEMPT.contains(d.simpleName)
        }
        assertTrue(
            unexplained.isEmpty(),
            "these public types were neither produced by the sweep nor exempted, so nothing here " +
                "tests them: " + unexplained.joinToString { it.simpleName },
        )

        assertTrue(
            failures.isEmpty(),
            "the adversarial sweep found ${failures.size} credential leak(s):\n  " +
                failures.joinToString("\n  "),
        )
    }

    /**
     * Public types that CANNOT be produced from a server response, with the reason.
     *
     * Kept as short as possible and justified individually — an exemption list is where a sweep
     * goes to die, so anything added here needs a reason that is about the type's direction of
     * travel, never about the difficulty of writing the scenario.
     */
    private val EXEMPT = setOf(
        // An INPUT to the SDK: the caller's transport builds it and hands it in. Nothing the SDK
        // produces. Its outbound counterpart, HttpRequestSpec, IS swept — that one the SDK builds,
        // and it is the object a custom transport gets to see.
        "HttpResponseSpec",
        // Kotlin `internal`, which has no JVM equivalent and so compiles to ACC_PUBLIC. Not part of
        // the surface any integrator can reach from Kotlin or from Java without deliberately
        // defeating the module boundary. Discovery cannot tell the difference without kotlin-reflect,
        // which this SDK does not depend on, so they are named here.
        "TransportRequest",
        "TransportResponse",
    )

    /** Collect every string in a structure, ignoring the writer's own (shallower) depth bound. */
    private fun flatten(v: Any?): String = when (v) {
        null -> ""
        is Map<*, *> -> v.entries.joinToString(" ") { "${it.key} ${flatten(it.value)}" }
        is Iterable<*> -> v.joinToString(" ") { flatten(it) }
        else -> v.toString()
    }

    /**
     * Drive the real entry points with hostile responses and collect everything that comes back —
     * returned objects AND thrown exceptions, since both are surfaces an integrator logs.
     */
    private fun captureFromHostileResponses(): List<Pair<String, Any>> {
        val out = mutableListOf<Pair<String, Any>>()
        fun keep(label: String, v: Any?) { if (v != null) out += label to v }
        fun attempt(label: String, block: () -> Any?) {
            try { keep(label, block()) } catch (e: Throwable) { keep("$label!thrown", e) }
        }

        // A response whose every string field, every header, and several nesting levels carry a
        // credential. This is the shape a gateway rendering the whole request on a 502 produces.
        val poisonHeaders = mapOf(
            "x-echo" to "Bearer $API_KEY",
            "x-secret" to WEBHOOK_SECRET,
            "content-type" to "application/json",
        )
        val poisonBody = nestedHostileMap()

        // ── collect: a clean ack, but poisoned response HEADERS and a poisoned error envelope ──
        // Python's sweep found response headers that were never scrubbed at all. Same probe here.
        attempt("collect/poisoned-headers") {
            val (paylod, _) = testClient(
                listOf(Step(status = 202, json = ACK, headers = poisonHeaders)), apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }
        attempt("collect/poisoned-5xx") {
            val (paylod, _) = testClient(
                listOf(Step(status = 500, json = poisonBody, headers = poisonHeaders)), apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }
        attempt("collect/poisoned-ack-identifiers") {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 202,
                        json = mapOf(
                            "paymentId" to "pay_$API_KEY",
                            "status" to "pending",
                            "checkoutRequestId" to "ws_$WEBHOOK_SECRET",
                        ),
                        headers = poisonHeaders,
                    ),
                ),
                apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }
        attempt("collect/malformed-2xx") {
            val (paylod, _) = testClient(
                listOf(Step(status = 202, raw = "not json $API_KEY", headers = poisonHeaders)), apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }

        // ── status / check: Payment, PaymentOutcome, PaymentJudgement ──────────────────────────
        attempt("status/poisoned-desc") {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 200,
                        json = paymentJson(
                            status = "failed", resultCode = "1032",
                            resultDesc = "cancelled, config was $API_KEY / $WEBHOOK_SECRET",
                        ),
                        headers = poisonHeaders,
                    ),
                ),
                apiKey = API_KEY,
            )
            paylod.status("pay_123")
        }
        attempt("check/poisoned-desc") {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 200,
                        json = paymentJson(
                            status = "failed", resultCode = "1032",
                            resultDesc = "cancelled, config was $API_KEY / $WEBHOOK_SECRET",
                        ),
                        headers = poisonHeaders,
                    ),
                ),
                apiKey = API_KEY,
            )
            paylod.check("pay_123")
        }
        keep(
            "judge/poisoned",
            PaymentSemantics.judge(
                Payment(
                    id = "pay_123", status = PaymentStatus.FAILED, mpesaReceipt = null,
                    resultCode = "1032", resultDesc = "desc $API_KEY $WEBHOOK_SECRET",
                ),
            ),
        )

        // ── collectAndWait: the post-acknowledgement failure paths, which carry charge handles ──
        attempt("collectAndWait/poisoned-timeout") {
            val (paylod, _) = testClient(
                listOf(
                    Step(status = 202, json = ACK, headers = poisonHeaders),
                    Step(status = 200, json = paymentJson(status = "pending"), headers = poisonHeaders),
                ),
                apiKey = API_KEY,
            )
            paylod.collectAndWait(
                CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"),
                WaitOptions.of(timeoutMs = 1),
            )
        }
        attempt("collectAndWait/poisoned-transport-throw") {
            val (paylod, _) = testClient(
                listOf(
                    Step(status = 202, json = ACK, headers = poisonHeaders),
                    Step(throwable = IllegalStateException("transport died holding $API_KEY")),
                ),
                apiKey = API_KEY,
            )
            paylod.collectAndWait(
                CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"),
                WaitOptions.of(timeoutMs = 5_000),
            )
        }

        // ── the credential-compromise path ─────────────────────────────────────────────────────
        attempt("security/redirect") {
            val (paylod, _) = testClient(
                listOf(Step(status = 302, headers = poisonHeaders + mapOf("location" to "https://evil.example/$API_KEY"))),
                apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }

        // ── local validation and configuration refusals ────────────────────────────────────────
        attempt("invalid-request") {
            val (paylod, _) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = API_KEY)
            paylod.collect(CollectParams(phone = "not-a-phone $API_KEY", amount = -1, idempotencyKey = "k"))
        }
        attempt("config/bad-base-url") {
            Paylod("mp_live_$WEBHOOK_SECRET", PaylodOptions.of(baseUrl = "http://evil.example/$API_KEY"))
        }
        attempt("config/sandbox-only") {
            val (paylod, _) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = "mp_live_$API_KEY")
            paylod.simulate.collect()
        }

        // ── the webhook paths ──────────────────────────────────────────────────────────────────
        val okBody = """{"type":"payment.failed","created":1700000000,"data":{""" +
            """"paymentId":"pay_1","status":"failed","amount":10,"resultCode":"1032",""" +
            """"resultDesc":"customer cancelled; gateway echoed Bearer $API_KEY"}}"""
        attempt("webhook/typed-foreign-credential") {
            Webhooks.parseAndVerifyAt(
                okBody, Webhooks.sign(okBody, WEBHOOK_SECRET, 1_700_000_000L),
                WEBHOOK_SECRET, 300L, 1_700_000_000L,
            )
        }
        attempt("webhook/echoes-own-secret") {
            val b = okBody.replace("Bearer $API_KEY", "config $WEBHOOK_SECRET")
            Webhooks.parseAndVerifyAt(
                b, Webhooks.sign(b, WEBHOOK_SECRET, 1_700_000_000L), WEBHOOK_SECRET, 300L, 1_700_000_000L,
            )
        }
        attempt("webhook/bad-schema") {
            val b = """{"type":"nonsense $API_KEY","created":1700000000,"data":{}}"""
            Webhooks.parseAndVerifyAt(
                b, Webhooks.sign(b, WEBHOOK_SECRET, 1_700_000_000L), WEBHOOK_SECRET, 300L, 1_700_000_000L,
            )
        }
        attempt("webhook/bad-signature") {
            Webhooks.parseAndVerifyAt(okBody, "t=1,v1=${"a".repeat(64)}", WEBHOOK_SECRET, 300L, 1L)
        }

        // ── the offline catalog: DecodedError and CatalogEntry, given hostile server prose ─────
        keep("catalog/decode", DarajaCatalog.decodeError("1032", "cancelled by $API_KEY / $WEBHOOK_SECRET"))
        keep("catalog/entry", DarajaCatalog.allEntries.first())

        // ── the simulator: SimulatedPayment and the nested SimOutcomeChoice ────────────────────
        attempt("simulate/collect") {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 202,
                        json = mapOf(
                            "paymentId" to "pay_sim_1", "status" to "pending", "checkoutRequestId" to "ws_1",
                            "outcomes" to listOf(
                                mapOf("id" to "success", "label" to "paid, ref $API_KEY", "status" to "success"),
                            ),
                        ),
                        headers = poisonHeaders,
                    ),
                ),
                apiKey = API_KEY,
            )
            paylod.simulate.collect(
                SimulateCollectParams.builder().phone("0712345678").amount(250).idempotencyKey("sim-1").build(),
            )
        }

        // The same call with a CLEAN body but poisoned response headers. The hostile-label variant
        // above is refused outright — correctly — which means it never actually builds a
        // `SimulatedPayment`, and a type the sweep never builds is a type the sweep says nothing
        // about. This one produces the object so its fields and headers are genuinely inspected.
        attempt("simulate/collect-poisoned-headers") {
            val (paylod, _) = testClient(
                listOf(
                    Step(
                        status = 202,
                        json = mapOf(
                            "paymentId" to "pay_sim_1", "status" to "pending", "checkoutRequestId" to "ws_1",
                            "outcomes" to listOf(
                                mapOf("id" to "success", "label" to "Paid in full", "status" to "success"),
                            ),
                        ),
                        headers = poisonHeaders,
                    ),
                ),
                apiKey = API_KEY,
            )
            paylod.simulate.collect(
                SimulateCollectParams.builder().phone("0712345678").amount(250).idempotencyKey("sim-2").build(),
            )
        }

        // ── the credential-compromise type: a 2xx REACHED BY FOLLOWING a redirect ──────────────
        // By the time this is true the bearer token is already gone, so the exception exists to be
        // loud — which means it is also a string an integrator logs immediately.
        attempt("security/redirected-2xx") {
            val compromised = object : HttpTransport {
                override fun execute(request: HttpRequestSpec): HttpResponseSpec =
                    HttpResponseSpec(200, poisonHeaders, Json.write(poisonBody), redirected = true)
            }
            Paylod(
                API_KEY,
                PaylodOptions.of(transport = compromised, allowCustomTransport = true),
            ).collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k"))
        }

        // ── indeterminate: an oversized response AFTER the charge may have dispatched ──────────
        attempt("indeterminate/oversized") {
            val (paylod, _) = testClient(
                listOf(Step(status = 202, raw = "$API_KEY " + "x".repeat((1 shl 20) + 1), headers = poisonHeaders)),
                apiKey = API_KEY,
            )
            paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k-huge"))
        }

        // ── interrupted on the money path ─────────────────────────────────────────────────────
        // The REAL time source, with the thread already interrupted, so the retry backoff sleep
        // raises for real rather than being simulated.
        attempt("interrupted/real-sleep") {
            val retrying = Paylod(
                API_KEY,
                PaylodOptions.of(
                    maxRetries = 1,
                    transport = StubTransport(listOf(Step(status = 500, json = poisonBody, headers = poisonHeaders))),
                    allowCustomTransport = true,
                ),
            )
            try {
                Thread.currentThread().interrupt()
                retrying.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k-int"))
            } finally {
                // Never leave the flag set for the rest of the suite.
                Thread.interrupted()
            }
        }

        // ── the OUTBOUND request spec: what a custom transport is allowed to see ───────────────
        // SECURITY.md states the credential never crosses that boundary. This is that claim, swept.
        run {
            val (paylod, t) = testClient(listOf(Step(status = 202, json = ACK)), apiKey = API_KEY)
            runCatching { paylod.collect(CollectParams(phone = "254712345678", amount = 10, idempotencyKey = "k")) }
            keep("transport/request-spec", t.lastRequestSpec)
        }

        return out
    }

    /**
     * A response-shaped map with credentials buried at several depths — including in KEYS.
     *
     * Depth is the whole point. A scalar-only or shallow redactor passes a top-level check and
     * leaks from level four, which is precisely where an echoed request envelope puts the
     * `Authorization` header.
     */
    private fun nestedHostileMap(): Map<String, Any?> = mapOf(
        "error" to "surface $API_KEY",
        "headers" to mapOf("authorization" to "Bearer $API_KEY"),
        // The credential as an object NAME, not a value.
        API_KEY to "the key was the key",
        "nested" to mapOf(
            "l2" to mapOf(
                "l3" to mapOf(
                    "l4" to listOf("buried $WEBHOOK_SECRET deep", mapOf("l6" to "deeper $API_KEY")),
                ),
            ),
        ),
    )

    /**
     * Every public type in `dev.paylod` that an integrator can hold and print: exceptions, and data
     * classes.
     *
     * Read off the COMPILED CLASS FILES rather than a maintained list, so a public type added later
     * is swept automatically instead of being swept only if someone remembers to add it here.
     */
    private fun discoverPublicSurfaceTypes(): Set<Class<*>> {
        val out = sortedSetOf<Class<*>>(compareBy { it.name })
        val files = CLASSES_DIR.listFiles { f: File -> f.isFile && f.name.endsWith(".class") } ?: return out
        for (f in files) {
            // Top-level classes only. Kotlin emits `Foo$Companion`, `Foo$WhenMappings` and friends,
            // none of which are a surface an integrator holds.
            if (f.name.contains('$')) continue
            val cls = try {
                Class.forName("dev.paylod." + f.name.removeSuffix(".class"))
            } catch (e: Throwable) {
                continue
            }
            if (!Modifier.isPublic(cls.modifiers)) continue
            if (cls.isInterface || cls.isEnum || cls.isAnnotation) continue
            val isThrowable = Throwable::class.java.isAssignableFrom(cls)
            // A Kotlin data class always emits `component1`. That is the marker available without
            // kotlin-reflect, which this SDK deliberately does not depend on.
            val isData = cls.declaredMethods.any { it.name == "component1" }
            if (isThrowable || isData) out.add(cls)
        }
        return out
    }

    /**
     * Every string an integrator can get out of [root], paired with the route they got it by.
     *
     * The routes are the ones people actually use: `toString()` on the object, the exception
     * message, the whole cause chain, and every public field — walked recursively, because a data
     * class holding a data class holding a map is the normal shape here and a scalar-only check
     * would miss all of it.
     */
    private fun reachableStrings(root: Any, covered: MutableSet<Class<*>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

        fun walk(label: String, v: Any?, depth: Int) {
            if (v == null || depth > 8) return
            if (v is String) { out += label to v; return }
            if (v is Number || v is Boolean || v is Char) return
            if (!seen.add(v)) return
            covered.add(v.javaClass)

            if (v is Map<*, *>) {
                for (entry in v.entries) {
                    entry.key?.toString()?.let { out += "$label{key}" to it }
                    walk("$label[${entry.key}]", entry.value, depth + 1)
                }
                return
            }
            if (v is Iterable<*>) {
                v.forEachIndexed { i, e -> walk("$label[$i]", e, depth + 1) }
                return
            }

            // `toString()` — the single most common way any of this reaches a log, and the one
            // Kotlin GENERATES for every data class.
            runCatching { out += "$label.toString()" to v.toString() }

            if (v is Throwable) {
                runCatching { v.message?.let { out += "$label.message" to it } }
                walk("$label.cause", v.cause, depth + 1)
            }

            // Public fields (Kotlin `@JvmField`) and public zero-arg getters (`val`).
            for (fld in runCatching { v.javaClass.fields }.getOrDefault(emptyArray())) {
                if (Modifier.isStatic(fld.modifiers)) continue
                runCatching { walk("$label.${fld.name}", fld.get(v), depth + 1) }
            }
            for (m in runCatching { v.javaClass.methods }.getOrDefault(emptyArray())) {
                if (m.parameterCount != 0 || Modifier.isStatic(m.modifiers)) continue
                if (!m.name.startsWith("get") && !m.name.startsWith("component")) continue
                if (m.name == "getClass" || m.name == "getStackTrace" || m.name == "getSuppressed") continue
                runCatching { m.isAccessible = true; walk("$label.${m.name}()", m.invoke(v), depth + 1) }
            }
        }

        walk(root.javaClass.simpleName, root, 0)
        return out
    }

    // ══ 3. THE REMAINING ROUND-9 FINDINGS ═══════════════════════════════════════════════════

    @Test
    @Tag("nv-short-secret-redacted")
    fun `a configured secret shorter than eight characters is still redacted`() {
        // The exclusion was justified by a minimum length that is enforced NOWHERE — neither the
        // API key nor the webhook secret has a length floor — so the filter's precondition was
        // never actually true and a short secret got a redactor that declined to redact it.
        val short = "abc123"
        val r = Redactor(listOf(short))
        assertFalse(r.text("the secret is $short").contains(short), "a 6-character secret was not redacted")
        assertTrue(r.containsCredential("the secret is $short"))
        assertTrue(r.containsCredentialDeep(mapOf("a" to mapOf("b" to "x $short y"))))
    }

    @Test
    @Tag("nv-sig-header-bound")
    fun `an enormous signature header is refused before it is split`() {
        // Unauthenticated input. `split(",")` on it allocated substrings totalling its whole
        // length, so an anonymous sender could command memory and CPU proportional to whatever
        // header size the surrounding server tolerated.
        val body = """{"type":"payment.success"}"""
        val huge = "t=1," + "x=y,".repeat(200_000) + "v1=" + "a".repeat(64)
        assertTrue(huge.length > Webhooks.MAX_SIGNATURE_HEADER_CHARS)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.verifySignature(body.toByteArray(), huge, WEBHOOK_SECRET)
        }
        assertEquals(SignatureFailureReason.MALFORMED_SIGNATURE, e.reason)
        // And the ordinary, well-formed header still parses — the bound must not have eaten it.
        assertTrue("t=1,v1=${"a".repeat(64)}".length < Webhooks.MAX_SIGNATURE_HEADER_CHARS)
    }

    @Test
    @Tag("nv-created-integral")
    fun `a signed event whose created is 1e400 is refused, not published as Long MAX_VALUE`() {
        // `floor(inf) == inf`, so the old wholeness test accepted infinity, and `Double.toLong()`
        // then saturated — publishing a timestamp ~292 billion years in the future.
        val body = """{"type":"payment.success","created":1e400,"data":{""" +
            """"paymentId":"pay_1","status":"success","amount":10,"mpesaReceipt":"SFF6XYZ123","resultCode":"0"}}"""
        val sig = Webhooks.sign(body, WEBHOOK_SECRET, 1_700_000_000L)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, sig, WEBHOOK_SECRET, 300L, 1_700_000_000L)
        }
        assertTrue(
            e.message!!.contains("integral"),
            "expected an integral-number refusal, got: ${e.message}",
        )
        // A float spelling of a legitimate value is refused on the same rule.
        val floaty = body.replace("1e400", "1700000000.0")
        assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(
                floaty, Webhooks.sign(floaty, WEBHOOK_SECRET, 1_700_000_000L),
                WEBHOOK_SECRET, 300L, 1_700_000_000L,
            )
        }
    }

    @Test
    @Tag("nv-wh-refuse-own-secret")
    fun `a correctly signed body echoing our own signing secret is REFUSED, not scrubbed`() {
        // Refusal rather than masking is the deliberate choice. The signature proves the sender
        // holds the secret, so this is either a misconfigured emitter echoing its own
        // configuration or a sender who already has it. A scrubbed copy handed to the handler
        // would look like an ordinary event.
        val body = """{"type":"payment.success","created":1700000000,"data":{""" +
            """"paymentId":"pay_1","status":"success","amount":10,"mpesaReceipt":"SFF6XYZ123",""" +
            """"resultCode":"0","resultDesc":"config dump: $WEBHOOK_SECRET"}}"""
        val sig = Webhooks.sign(body, WEBHOOK_SECRET, 1_700_000_000L)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, sig, WEBHOOK_SECRET, 300L, 1_700_000_000L)
        }
        // The diagnostic must not reproduce the leak it is reporting.
        assertFalse(e.message!!.contains(WEBHOOK_SECRET), "the refusal message quoted the secret it refused")
        assertTrue(e.message!!.contains("Rotate the webhook signing secret"))
    }

    @Test
    @Tag("nv-diag-choke-point")
    fun `an invalid-schema diagnostic redacts and BOUNDS the server value it quotes`() {
        // The message is the thing integrators log. A server-controlled field reaching it
        // unredacted is a leak; reaching it unbounded is a log-flooding primitive.
        val long = "z".repeat(5000)
        val body = """{"type":"payment.success","created":1700000000,"data":{""" +
            """"paymentId":"pay_1","status":"$long","amount":10}}"""
        val sig = Webhooks.sign(body, WEBHOOK_SECRET, 1_700_000_000L)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, sig, WEBHOOK_SECRET, 300L, 1_700_000_000L)
        }
        assertFalse(e.message!!.contains(long), "the whole 5000-char server value was interpolated")
        assertTrue(e.message!!.contains("truncated"), "the value was not marked as truncated")
        assertTrue(e.message!!.length < 2000, "the diagnostic was not bounded: ${e.message!!.length} chars")
    }

    @Test
    @Tag("nv-diag-choke-point")
    fun `an invalid-schema diagnostic REDACTS a credential the server put in the quoted field`() {
        // The bounding test alone was not enough: it passes whether or not `field()` redacts, so
        // the redaction half of the choke point needs its own assertion. A FOREIGN credential is
        // used deliberately — a body echoing our OWN secret is refused earlier, before any
        // diagnostic is built, so it could never reach this path to prove anything about it.
        val foreign = "mp_live_someoneelseskeyaaaaaaaaaaaaaaaa"
        val body = """{"type":"payment.success","created":1700000000,"data":{""" +
            """"paymentId":"pay_1","status":"$foreign","amount":10}}"""
        val sig = Webhooks.sign(body, WEBHOOK_SECRET, 1_700_000_000L)
        val e = assertThrows<PaylodSignatureVerificationException> {
            Webhooks.parseAndVerifyAt(body, sig, WEBHOOK_SECRET, 300L, 1_700_000_000L)
        }
        assertFalse(
            e.message!!.contains(foreign),
            "the invalid-schema diagnostic quoted a credential verbatim: ${e.message}",
        )
        assertTrue(e.message!!.contains("[redacted]"), "the value was not masked, merely absent")
    }

    @Test
    @Tag("nv-diag-choke-point")
    fun `the credential SHAPE list covers whsec_ and sk_`() {
        // `whsec_` was absent, which is the shape of the webhook signing secret — the credential
        // this very module handles, and the one most likely to be echoed by a misconfigured
        // emitter. A shape list that omits the local credential is the wrong list.
        val shapes = Redactor.SHAPES_ONLY
        assertFalse(shapes.text("secret whsec_abcdefghijklmnop here").contains("whsec_abcdefghijklmnop"))
        assertFalse(shapes.text("secret sk_abcdefghijklmnop here").contains("sk_abcdefghijklmnop"))
        assertFalse(shapes.text("secret mp_live_abcdefghijklmnop").contains("mp_live_abcdefghijklmnop"))
    }

    @Test
    // Tagged to the WRITE-BOUNDS case, which reverts the writer's depth, size, cycle and
    // string-emission bounds as a set. `nv-write-budget` was removed as a standalone case because
    // allocation ORDERING is not behaviourally distinguishable from the in-loop bound, and a tag
    // with no registered case is a protection claiming a verification it never had.
    @Tag("nv-json-write-bounds")
    fun `an oversized metadata string is refused BEFORE it is copied, not after`() {
        // The bound used to be checked before `writeValue` was entered and after it returned, which
        // is the wrong side of the work for a scalar: the whole value was copied into the builder
        // and only then measured. The refusal has to happen without the allocation, so the probe is
        // a string far larger than the budget rather than budget+1 — the old implementation
        // "passed" at budget+1 while still copying everything.
        val huge = "x".repeat(Json.MAX_WRITE_CHARS * 8)
        assertThrows<Json.JsonWriteException> { Json.write(mapOf("metadata" to huge)) }
        // A map KEY is exactly as caller-controlled and takes the same route.
        assertThrows<Json.JsonWriteException> { Json.write(mapOf(huge to "v")) }
        // CONTROL: an ordinary body still serialises.
        assertEquals("""{"a":"b"}""", Json.write(mapOf("a" to "b")))
    }

    @Test
    fun `no source file contains a raw NUL byte, so the whole tree stays greppable`() {
        // `Json.kt` carried one, as the end-of-input sentinel in `peek()`. A single NUL makes a file
        // `data` rather than text: `grep` skips it SILENTLY, printing no match and no warning. A
        // reviewer grepping for the parser's depth bound therefore got nothing back and could
        // reasonably conclude the parser had none — which is exactly the drift this round found.
        //
        // A parser on a payments path is the last file that should be invisible to review tooling,
        // so the property is asserted rather than left to whoever next types a control character.
        val offenders = File("src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readBytes().contains(0) }
            .map { it.path }
            .toList()
        assertTrue(offenders.isEmpty(), "source files contain raw NUL bytes: $offenders")
    }

    @Test
    @Tag("nv-sim-outcome-handles")
    fun `simulate outcome attaches its deterministic key and payment id to every failure`() {
        // Each of these fails AFTER the settle may have taken effect, so each is a state the caller
        // must reconcile — and each one used to escape with no handle at all.
        val cases = mapOf(
            "malformed 2xx" to Step(status = 200, raw = "not json"),
            "wrong-record body" to Step(status = 200, json = paymentJson(id = "pay_OTHER", status = "success")),
            "missing webhookQueued" to Step(
                status = 200,
                json = paymentJson(id = "pay_1", status = "success", mpesaReceipt = "SFF6XYZ123", resultCode = "0"),
            ),
        )
        for ((label, step) in cases) {
            val (paylod, _) = testClient(listOf(step), apiKey = API_KEY)
            val e = assertThrows<PaylodException>(label) {
                paylod.simulate.outcome("pay_1", SimOutcomeId.APPROVE)
            }
            assertEquals("sim-outcome-pay_1-approve", e.idempotencyKey, "[$label] lost the settle key")
            assertEquals("pay_1", e.paymentId, "[$label] lost the payment id")
        }
    }

    @Test
    @Tag("nv-sim-outcome-handles")
    fun `simulate pay propagates the created payment when the settle fails`() {
        val (paylod, _) = testClient(
            listOf(
                Step(
                    status = 202,
                    json = mapOf(
                        "paymentId" to "pay_sim_9", "status" to "pending", "checkoutRequestId" to "ws_9",
                        "outcomes" to listOf(mapOf("id" to "success", "label" to "Paid", "status" to "success")),
                    ),
                ),
                Step(status = 200, raw = "not json"),
            ),
            apiKey = API_KEY,
        )
        val e = assertThrows<PaylodException> {
            paylod.simulate.pay(
                SimOutcomeId.APPROVE,
                SimulateCollectParams.builder().phone("0712345678").amount(10).idempotencyKey("sim-pay-1").build(),
            )
        }
        assertEquals("pay_sim_9", e.paymentId, "the payment the collect already created was stranded")
    }
}
