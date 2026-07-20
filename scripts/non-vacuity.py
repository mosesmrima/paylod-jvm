#!/usr/bin/env python3
"""
NON-VACUITY HARNESS.

A test that passes both WITH and WITHOUT the fix it claims to cover proves nothing. Every
protection landed in 0.4.0 is therefore verified the only way that actually settles the question:
REVERT the change in the source, run the single test that is supposed to catch it, and require
that it FAILS. The file is restored afterwards, always.

    JAVA_HOME=/home/mrima/android-jdk17 python3 scripts/non-vacuity.py

Exit code 0 only if every mutation was caught.

── Why the selector is proven live first ─────────────────────────────────────────────────────
The sibling Node harness caught three flaws of its own, the worst being selectors that matched
ZERO tests while the runner still exited 0 — which reads as "the mutation was not caught" when in
truth nothing ever ran. Gradle has exactly the same failure mode, and worse: `gradlew -q` with a
tag that matches nothing prints nothing and exits 0. So every run here goes through
`--console=plain`, the build emits a machine-readable `NVCOUNT tests=N failed=M skipped=S` line from
a real test listener, and a case is only trusted once its tag is shown to select N > 0 tests that all
PASS on clean source. Anything else is reported as a broken case rather than a verdict.

── What a TRUSTED run requires (both the clean run and the mutated one) ──────────────────────
The harness itself had this defect for a while, and it is the worst kind: it made the harness
report CAUGHT for work it had not actually verified. It scraped the `NVCOUNT` line and stopped
there — ignoring the gradle EXIT CODE and the SKIPPED count that the same line already carried.

  • Exit code. Under `-PnvTag` the test task sets `ignoreFailures`, so a tagged run exits 0 even
    when its tests fail; failure travels through the counters. A NON-zero exit therefore never
    means "a test failed" — it means the build did not finish, and its counters describe a
    partial run. `NVCOUNT` is printed from `doLast` and was happily scraped out of exactly such
    a run, so a crashed build could be accepted as a measurement, and if it crashed after some
    tests had failed it was accepted as CAUGHT.

  • Skipped. `ran` counts a skipped test, so a case whose guarding test was disabled or filtered
    still cleared the liveness gate and then "passed" the mutated run without executing — the
    precise vacuity this harness exists to detect, wearing a green badge.

So every measurement now requires EXIT CODE ZERO **and** ZERO SKIPPED TESTS, on both runs. Every
number the verdict rests on is printed in the final table.
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Each case reverts ONE protection to its pre-0.4.0 behaviour. `find` must match EXACTLY ONCE, so
# a silent no-op mutation cannot masquerade as a caught one. `also` reverts a second site when a
# guarantee has two independent implementations — reverting one alone would prove nothing, because
# the other still holds the line, so the mutation has to remove the GUARANTEE.
# The FAILED + NO-EVIDENCE cell, spelled out once and reverted by two cases (the semantic law
# itself, and the webhook rule that leans on it). Kept as constants because the replacement text
# contains quote runs that do not survive being inlined into a triple-quoted literal.
FAILED_NONE_FIND = (
    "                PaymentEvidence.NONE ->\n"
    "                    PaymentVerdict.INDETERMINATE to\n"
    '                        "status claims the payment failed but the record carries neither a receipt " +\n'
    '                        "nor a result code, so there is no evidence it actually failed — a claim " +\n'
    '                        "is not evidence for itself"'
)
FAILED_NONE_REPL = (
    "                PaymentEvidence.NONE ->\n"
    '                    PaymentVerdict.FAILED to "the payment failed terminally"'
)

CASES = [
    # ── ROOT 1 ────────────────────────────────────────────────────────────────────────────────
    dict(
        id="R1-gate", tag="nv-r1-gate",
        what="a custom transport no longer requires the explicit opt-in",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "if (custom != null && !options.allowCustomTransport) {", "if (false) {")],
    ),
    dict(
        id="R1-live", tag="nv-r1-live",
        what="a custom transport is permitted with a LIVE key (BOTH guards reverted)",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                'if (custom != null && apiKey.startsWith("mp_live_")) {', "if (false) {"),
               ("src/main/kotlin/dev/paylod/Transport.kt",
                "if (custom != null && apiKey.startsWith(LIVE_PREFIX)) {", "if (false) {")],
    ),
    dict(
        id="R1-nocred", tag="nv-r1-nocred",
        what="the credential is put back into the headers a custom transport receives",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                'publicHeaders["accept"] = "application/json"',
                'publicHeaders["accept"] = "application/json"\n'
                '        publicHeaders["authorization"] = "Bearer $apiKey"')],
    ),
    dict(
        id="R1-followed", tag="nv-r1-followed",
        what="a 2xx reached by FOLLOWING a redirect is accepted",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                "if (response.redirected) {", "if (false) {")],
    ),
    dict(
        id="R1-origin", tag="nv-r1-origin",
        what="the responding URL is not checked against the pinned origin",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                'if (finalUrl != null) assertOnOrigin(finalUrl, "the responding URL", idempotencyKey)',
                "if (finalUrl != null) Unit")],
    ),
    dict(
        id="R1-noretry", tag="nv-r1-noretry",
        what="a DETECTED credential compromise is caught by the retry loop and replayed",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                """            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            }""",
                """            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            } catch (e: PaylodSecurityException) {
                lastError = e
                attempt++
                continue
            }""")],
    ),
    dict(
        id="R1-noretry-count", tag="nv-r1-noretry-count",
        what="the same revert, measured by counting dispatches instead of by type",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                """            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            }""",
                """            } catch (e: PaylodConnectionException) {
                lastError = e
                attempt++
                continue // network blip -> retry
            } catch (e: PaylodSecurityException) {
                lastError = e
                attempt++
                continue
            }""")],
    ),
    dict(
        id="R1-3xx", tag="nv-r1-3xx",
        what="a 3xx response is not refused",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                "if (response.status in 300..399) {", "if (false) {")],
    ),
    dict(
        id="R1-optsecret", tag="nv-r1-optsecret",
        what="PaylodOptions publishes a public getter for the API key again",
        edits=[("src/main/kotlin/dev/paylod/PaylodOptions.kt",
                "internal fun apiKeyOrNull(): String? = apiKeyValue",
                "fun getApiKey(): String? = apiKeyValue\n"
                "    internal fun apiKeyOrNull(): String? = apiKeyValue")],
    ),

    # ── ROOT 2 ────────────────────────────────────────────────────────────────────────────────
    dict(
        id="R2-bind", tag="nv-r2-bind",
        what="the returned payment id is not bound to the requested id",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "if (id != expectedId) {", "if (false) {")],
    ),
    dict(
        id="R2-202", tag="nv-r2-202",
        what="any 2xx is accepted as a collect acknowledgement",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "if (httpStatus != 202) {", "if (false) {")],
    ),
    dict(
        id="R2-pending0", tag="nv-r2-pending0",
        what="a pending record carrying result code 0 is treated as PAID",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """                PaymentEvidence.SUCCESS ->
                    PaymentVerdict.INDETERMINATE to
                        "status says pending while the evidence says the payment succeeded — a " +
                        "pending record must never be reported as paid\"""",
                '                PaymentEvidence.SUCCESS ->\n'
                '                    PaymentVerdict.PAID to "REVERTED"')],
    ),
    dict(
        id="R2-pendingreceipt", tag="nv-r2-pendingreceipt",
        what="a pending record carrying a receipt is treated as PAID (same table cell)",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """                PaymentEvidence.SUCCESS ->
                    PaymentVerdict.INDETERMINATE to
                        "status says pending while the evidence says the payment succeeded — a " +
                        "pending record must never be reported as paid\"""",
                '                PaymentEvidence.SUCCESS ->\n'
                '                    PaymentVerdict.PAID to "REVERTED"')],
    ),
    dict(
        id="R2-receipt", tag="nv-r2-receipt",
        what="a receipt beside a failure code no longer conflicts (L4 removed)",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """            PaymentEvidence.SUCCESS, PaymentEvidence.NONE -> PaymentEvidence.SUCCESS
            PaymentEvidence.FAILURE, PaymentEvidence.IN_FLIGHT, PaymentEvidence.CONFLICT ->
                PaymentEvidence.CONFLICT""",
                "            PaymentEvidence.NONE -> PaymentEvidence.SUCCESS\n"
                "            else -> codeEvidence")],
    ),
    dict(
        id="R2-evidence", tag="nv-r2-evidence",
        what="a bare status:success with no evidence is treated as PAID (L2 removed)",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """                PaymentEvidence.NONE ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims success but the record carries neither a receipt nor a " +
                        "result code, so there is no evidence the payment actually settled\"""",
                '                PaymentEvidence.NONE ->\n'
                '                    PaymentVerdict.PAID to "REVERTED"')],
    ),
    dict(
        id="R2-total", tag="nv-r2-total",
        what="L2 removed — the cross-product law check must catch it",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """                PaymentEvidence.NONE ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims success but the record carries neither a receipt nor a " +
                        "result code, so there is no evidence the payment actually settled\"""",
                '                PaymentEvidence.NONE ->\n'
                '                    PaymentVerdict.PAID to "REVERTED"')],
    ),
    dict(
        id="R2-inflight", tag="nv-r2-inflight",
        what="a failed row carrying a pending code is reported as a terminal failure",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                """                PaymentEvidence.IN_FLIGHT ->
                    PaymentVerdict.INDETERMINATE to
                        "status claims the payment failed terminally while the result code says it " +
                        "is still in flight — the two contradict, so the record proves nothing; it " +
                        "is neither settled nor safe to charge again\"""",
                '                PaymentEvidence.IN_FLIGHT ->\n'
                '                    PaymentVerdict.FAILED to "REVERTED"')],
    ),

    # ── The simulator runs the same validators ────────────────────────────────────────────────
    dict(
        id="SIM-key", tag="nv-sim-key",
        what="simulate.collect() sends no Idempotency-Key when the caller omits one",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                'val ack = requester.request("POST", "/simulate/collect", body, idempotencyKey) { map, status ->',
                'val ack = requester.request("POST", "/simulate/collect", body, params.idempotencyKey) { map, status ->')],
    ),
    dict(
        id="SIM-bind", tag="nv-sim-bind",
        what="simulate.outcome() validates nothing (no binding, no evidence)",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                """            validated = PaymentValidators.assertPaymentBody(
                normalizeSettleAck(map), status, paymentId, "simulate.outcome()", redact,
            )""",
                """            validated = Payment(
                paymentId,
                PaymentStatus.parseWire(map["status"] as? String) ?: PaymentStatus.PENDING,
                map["mpesaReceipt"] as? String,
                map["resultCode"]?.toString(),
                map["resultDesc"] as? String,
            )
            status.toString()""")],
    ),

    # ── The boundary fixes ────────────────────────────────────────────────────────────────────
    dict(
        id="B-redact-msg", tag="nv-redact-msg",
        what="the API error message is built from the unredacted response",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "val message = redact.text(rawMessage)", "val message = rawMessage")],
    ),
    dict(
        id="B-redact-body", tag="nv-redact-body",
        what="the API error stores the RAW parsed body on its public field",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "redact.body(parsed), idempotencyKey)", "parsed, idempotencyKey)")],
    ),
    dict(
        id="B-keys-any", tag="nv-keys-any",
        what="collectAndWait preserves the charge handles only for a PaylodException",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "} catch (e: Throwable) {", "} catch (e: PaylodTimeoutException) {")],
    ),
    dict(
        id="B-json-dup", tag="nv-json-dup",
        what="a duplicate JSON object name silently overwrites the earlier value",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "if (out.containsKey(key)) {", "if (false) {")],
    ),
    dict(
        id="W-evidence", tag="nv-wh-schema",
        what="a signed payment.success is delivered without evidence",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (type == "payment.success" && judgement.verdict != PaymentVerdict.PAID) {',
                "if (false) {")],
    ),
    dict(
        id="W-consistency", tag="nv-wh-consistency",
        what="type/status consistency is not checked (all three layers reverted)",
        # Consistency is LAYERED with the two evidence checks: a contradiction between `type` and
        # `data.status` is usually also caught by the evidence rule, because the verdict is derived
        # from `status` and compared against `type`. Reverting the consistency check alone would
        # therefore prove nothing — so the mutation removes the guarantee, not one expression of it.
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "if (parsedStatus != expectedStatus) {", "if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (type == "payment.success" && judgement.verdict != PaymentVerdict.PAID) {',
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (type == "payment.failed" && judgement.verdict != PaymentVerdict.FAILED) {',
                "if (false) {")],
    ),
    dict(
        id="W-failedreceipt", tag="nv-wh-failedreceipt",
        what="a signed payment.failed carrying a receipt is delivered as a settled failure",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (type == "payment.failed" && judgement.verdict != PaymentVerdict.FAILED) {',
                "if (false) {")],
    ),

    # ── ROUND 5 — the remaining money-correctness defects ─────────────────────────────────────
    dict(
        id="R5-status-strict", tag="nv-status-strict",
        what="an unknown wire status is coerced to PENDING instead of rejected",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                'val status = PaymentStatus.parseWire(wireStatus)\n'
                '            ?: bad("status \\"$wireStatus\\" is not one of pending/success/failed")',
                "val status = PaymentStatus.parseWire(wireStatus) ?: PaymentStatus.PENDING")],
    ),
    dict(
        id="R5-no-lenient", tag="nv-no-lenient",
        what="the lenient PaymentStatus.fromWire fallback is reintroduced",
        edits=[("src/main/kotlin/dev/paylod/Types.kt",
                "        fun parseWire(value: String?): PaymentStatus? = when (value) {",
                "        fun fromWire(value: String?): PaymentStatus = parseWire(value) ?: PENDING\n\n"
                "        fun parseWire(value: String?): PaymentStatus? = when (value) {")],
    ),
    dict(
        id="R5-wh-decoded", tag="nv-wh-decoded-retryable",
        what="webhook `decoded` is read from the payload instead of the canonical catalog",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                """            if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    // The SECRET-SEEDED redactor, not the shape-only scrubber. `DecodedError` is a
                    // public data class too, and its `description` is server prose.
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""",
                """            @Suppress("UNCHECKED_CAST")
            val decodedMap = data["decoded"] as? Map<String, Any?>
            if (decodedMap != null) {
                DecodedError(
                    code = decodedMap["code"]?.toString() ?: "",
                    title = decodedMap["title"]?.toString() ?: "",
                    cause = decodedMap["cause"]?.toString() ?: "",
                    fix = decodedMap["fix"]?.toString() ?: "",
                    category = DarajaCategory.fromWire(decodedMap["category"]?.toString() ?: "mpesa_system"),
                    retryable = decodedMap["retryable"] as? Boolean ?: false,
                    customerMessage = decodedMap["customerMessage"]?.toString() ?: "",
                )
            } else if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""")],
    ),
    dict(
        id="R5-wh-decoded-malformed", tag="nv-wh-decoded-malformed",
        what="the same revert, seen as a raw parser exception escaping a handler",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                """            if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    // The SECRET-SEEDED redactor, not the shape-only scrubber. `DecodedError` is a
                    // public data class too, and its `description` is server prose.
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""",
                """            @Suppress("UNCHECKED_CAST")
            val decodedMap = data["decoded"] as? Map<String, Any?>
            if (decodedMap != null) {
                DecodedError(
                    code = decodedMap["code"]?.toString() ?: "",
                    title = decodedMap["title"]?.toString() ?: "",
                    cause = decodedMap["cause"]?.toString() ?: "",
                    fix = decodedMap["fix"]?.toString() ?: "",
                    category = DarajaCategory.fromWire(decodedMap["category"]?.toString() ?: "mpesa_system"),
                    retryable = decodedMap["retryable"] as? Boolean ?: false,
                    customerMessage = decodedMap["customerMessage"]?.toString() ?: "",
                )
            } else if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""")],
    ),
    dict(
        id="R5-wh-amount", tag="nv-wh-amount",
        what="data.amount is accepted as any Number, then truncated/wrapped on conversion",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "if (wholeAmount(amount) == null) {", "if (amount !is Number) {")],
    ),
    dict(
        id="R5-wh-tolerance", tag="nv-wh-tolerance-max",
        what="an unbounded positive replay tolerance is accepted again",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "if (toleranceSec > MAX_TOLERANCE_SEC) {", "if (false) {")],
    ),
    dict(
        id="R5-wh-clock", tag="nv-wh-noclock",
        what="clock injection is put back on the PUBLIC webhook API",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,\n"
                "    ): WebhookEvent = parseAndVerifyAt(payload, signature, secret, toleranceSec, null)",
                "        toleranceSec: Long = DEFAULT_TOLERANCE_SEC,\n"
                "        nowSec: Long? = null,\n"
                "    ): WebhookEvent = parseAndVerifyAt(payload, signature, secret, toleranceSec, nowSec)")],
    ),
    dict(
        id="R5-bound-size", tag="nv-bound-size",
        what="the response size limit is removed, so an oversized body is accumulated",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                "if (body.length > MAX_RESPONSE_CHARS) {", "if (false) {")],
    ),
    dict(
        id="R5-bound-depth", tag="nv-bound-depth",
        what="the response depth limit is removed, so the parser is entered on a deep document",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                "if (depth > MAX_JSON_DEPTH) {", "if (false) {")],
    ),
    dict(
        id="R5-json-depth", tag="nv-json-depth",
        what="the JSON reader recurses without a depth limit (StackOverflowError, not an error)",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "if (depth > MAX_DEPTH) {", "if (false) {")],
    ),
    dict(
        id="R5-keys-error", tag="nv-keys-assertionerror",
        what="every Error is rethrown bare again, losing the key on a live charge",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "if (e is VirtualMachineError) {", "if (e is Error) {")],
    ),
    dict(
        id="R5-keys-vm", tag="nv-keys-vmerror",
        what="a VM error escapes without the charge context attached",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "e.addSuppressed(chargeContext(ack, e))", "Unit")],
    ),
    dict(
        id="R5-opt-bounds", tag="nv-opt-bounds",
        what="timeoutMs is accepted unbounded at construction",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "if (options.timeoutMs < MIN_TIMEOUT_MS || options.timeoutMs > MAX_TIMEOUT_MS) {",
                "if (false) {")],
    ),
    dict(
        id="R5-wait-bounds", tag="nv-wait-bounds",
        what="a non-positive wait timeout is silently replaced by the default",
        edits=[("src/main/kotlin/dev/paylod/WaitOptions.kt",
                "if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {", "if (false) {")],
    ),
    dict(
        id="R5-deadline", tag="nv-deadline-overflow",
        what="the poll deadline is compared by ABSOLUTE value instead of by subtraction",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "            val left = remaining(deadline) ?: break\n"
                "            if (left <= delay) break",
                "            if (time.monotonicMillis() + delay >= deadline) break")],
    ),
    dict(
        id="R5-zero-strict", tag="nv-zero-strict",
        what="result code zero is recognised numerically again (toDouble() == 0.0)",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS",
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS"),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "        val lexeme = codeLexeme(resultCode)\n"
                "        if (lexeme != null && lexeme.isNotEmpty() && !isCanonicalCodeLexeme(lexeme)) {\n"
                "            return StkOutcome.PENDING\n"
                "        }\n",
                "")],
    ),
    dict(
        id="R5-zero-evidence", tag="nv-zero-evidence",
        what="the same revert, seen as a zero impostor manufacturing PAID evidence",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS",
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS"),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "        val lexeme = codeLexeme(resultCode)\n"
                "        if (lexeme != null && lexeme.isNotEmpty() && !isCanonicalCodeLexeme(lexeme)) {\n"
                "            return StkOutcome.PENDING\n"
                "        }\n",
                "")],
    ),
    dict(
        id="R5-zero-webhook", tag="nv-zero-webhook",
        what="the same revert, on the webhook delivery path",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS",
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS"),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "        val lexeme = codeLexeme(resultCode)\n"
                "        if (lexeme != null && lexeme.isNotEmpty() && !isCanonicalCodeLexeme(lexeme)) {\n"
                "            return StkOutcome.PENDING\n"
                "        }\n",
                "")],
    ),
    dict(
        id="R5-zero-launder", tag="nv-zero-nolaunder",
        what="a JSON float zero is collapsed into the canonical success code again",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "        is dev.paylod.internal.JsonNumber -> v.lexeme",
                "        is dev.paylod.internal.JsonNumber ->\n"
                "            if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.lexeme")],
    ),

    # ── ROUND 6 ───────────────────────────────────────────────────────────────────────────────
    dict(
        id="R6-rawzero-parse", tag="nv-rawzero-parse",
        what="the JSON reader collapses a raw -0 back into an integral zero",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                'if (token == "-0") return JsonNumber(token, -0.0)',
                'if (token == "-0") return JsonNumber("0", 0L)')],
    ),
    dict(
        id="R6-rawzero-write", tag="nv-rawzero-parse",
        what="the JSON writer re-launders -0.0 into the token 0 on the way out",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "if (isNegativeZero(value)) {\n                    sb.append(\"-0.0\")\n                } else if (value == Math.floor(value) && !value.isInfinite()) {",
                "if (value == Math.floor(value) && !value.isInfinite()) {")],
    ),
    dict(
        id="R6-rawzero-status", tag="nv-rawzero-status",
        what="a raw -0 on the STATUS path is read as the canonical success code",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                'if (token == "-0") return JsonNumber(token, -0.0)',
                'if (token == "-0") return JsonNumber("0", 0L)')],
    ),
    dict(
        id="R6-rawzero-webhook", tag="nv-rawzero-webhook",
        what="a raw -0 on the WEBHOOK path is read as the canonical success code",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                'if (token == "-0") return JsonNumber(token, -0.0)',
                'if (token == "-0") return JsonNumber("0", 0L)')],
    ),
    dict(
        id="R6-body-deadline", tag="nv-body-deadline",
        what="the response body is read without the caller's end-to-end deadline",
        # NOT reverted all the way to an unbounded `get()`: that never returns, and a mutation that
        # hangs is a mutation that stalls the sweep rather than reporting. Widening the deadline
        # past the test's own bound removes the GUARANTEE — the caller's timeout no longer governs
        # the body read — while still terminating. Verified separately: at 120s this test fails, so
        # the deadline really is what bounds the read and the JDK request timeout does not cover it.
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                "future.get(request.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)",
                "future.get(30_000, java.util.concurrent.TimeUnit.MILLISECONDS)")],
    ),
    dict(
        id="R6-wh-maxbytes", tag="nv-wh-maxbytes",
        what="a webhook body is HMAC'd and parsed with no size bound (BOTH entry paths reverted)",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                # Disambiguated by context: `assertWithinLimit(payload.size)` now also appears in
                # the public `sign` helper, which gained the same bound this round (6.4). This
                # edit must revert the VERIFICATION path's bound specifically.
                "        assertWithinLimit(payload.size)\n        if (secret.isEmpty()) {",
                "        if (secret.isEmpty()) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                "if (payload.length > MAX_PAYLOAD_BYTES) assertWithinLimit(payload.length)\n"
                "        val bytes = payload.toByteArray(StandardCharsets.UTF_8)\n"
                "        assertWithinLimit(bytes.size)\n"
                "        return bytes",
                "return payload.toByteArray(StandardCharsets.UTF_8)")],
    ),
    dict(
        id="R6-sim-fields", tag="nv-sim-fields",
        what="malformed simulator outcomes are silently dropped instead of refused",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                """        val outcomesRaw = ack["outcomes"] as? List<Any?>
            ?: simBad("outcomes is missing or is not an array", ackStatus)
        val choices = outcomesRaw.mapIndexed { i, raw ->
            @Suppress("UNCHECKED_CAST")
            val o = raw as? Map<String, Any?> ?: simBad("outcomes[$i] is not an object", ackStatus)
            SimOutcomeChoice(
                id = simString(o["id"], "outcomes[$i].id", ackStatus),
                label = simString(o["label"], "outcomes[$i].label", ackStatus),
                status = simString(o["status"], "outcomes[$i].status", ackStatus),
            )
        }""",
                """        val outcomesRaw = ack["outcomes"] as? List<Any?> ?: emptyList()
        val choices = outcomesRaw.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val o = raw as? Map<String, Any?> ?: return@mapNotNull null
            SimOutcomeChoice(
                id = o["id"]?.toString() ?: "",
                label = o["label"]?.toString() ?: "",
                status = o["status"]?.toString() ?: "",
            )
        }""")],
    ),
    dict(
        id="R6-sim-webhookqueued", tag="nv-sim-webhookqueued",
        what="a missing webhookQueued is reported as true instead of refused",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                """        val webhookQueued = ack["webhookQueued"] as? Boolean
            ?: simBad("webhookQueued is missing or is not a boolean", settleStatus)""",
                """        val webhookQueued = ack["webhookQueued"] != false""")],
    ),
    dict(
        id="R6-baseurl-redact", tag="nv-baseurl-redact",
        what="a rejected baseUrl is echoed verbatim, userinfo and query string included",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                '(got ${redact.field(sanitizeUrl(parsed))})', '(got \\"$baseUrl\\")')],
    ),
    dict(
        id="R6-decode-zero", tag="nv-s6-decode-zero",
        what="the decoder trims before the catalog lookup again, so \" 0\" selects the SUCCESS entry",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt", """        val lexeme = codeLexeme(resultCode)
        if (lexeme == null || !isCanonicalCodeLexeme(lexeme)) {
            return indeterminateFallback(lexeme ?: code, rawDesc)
        }""", ""),
               # A SECOND edit, added this round. `normalizeCode` no longer trims (requirement
               # 1.1), so a padded code fails the catalog lookup on its own and reverting the
               # lexeme check alone left the guarantee standing — the case came back VACUOUS.
               # The two layers are deliberately independent, so removing the GUARANTEE means
               # removing both: the form check ABOVE the lookup, and the losslessness BELOW it.
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "            else -> resultCode.toString()\n        }",
                "            else -> resultCode.toString().trim()\n        }")],
    ),
    dict(
        id="R6-decode-terminal", tag="nv-s6-decode-terminal",
        what="the decoder trims before the catalog lookup again, so \" 1032\" decodes as the retryable cancellation",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt", """        val lexeme = codeLexeme(resultCode)
        if (lexeme == null || !isCanonicalCodeLexeme(lexeme)) {
            return indeterminateFallback(lexeme ?: code, rawDesc)
        }""", ""),
               # A SECOND edit, added this round. `normalizeCode` no longer trims (requirement
               # 1.1), so a padded code fails the catalog lookup on its own and reverting the
               # lexeme check alone left the guarantee standing — the case came back VACUOUS.
               # The two layers are deliberately independent, so removing the GUARANTEE means
               # removing both: the form check ABOVE the lookup, and the losslessness BELOW it.
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "            else -> resultCode.toString()\n        }",
                "            else -> resultCode.toString().trim()\n        }")],
    ),
    dict(
        id="R6-decode-anchor", tag="nv-s6-decode-anchor",
        what="the canonical form check goes back to a `$`-anchored partial match, which accepts a trailing newline",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                """        return CANONICAL_CODE_RE.matches(code) ||
            CANONICAL_DOTTED_RE.matches(code) ||
            CANONICAL_ALNUM_RE.matches(code)""",
                """        return Regex("^(?:0|[1-9][0-9]*)$").containsMatchIn(code) ||
            Regex("^(?:0|[1-9][0-9]*)(?:\\\\.[0-9]{1,8}){1,6}$").containsMatchIn(code) ||
            Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$").containsMatchIn(code)""")],
    ),

    # ── ROUND 7 ───────────────────────────────────────────────────────────────────────────────
    dict(
        id="R7-idem-required", tag="nv-idem-required",
        what="the idempotency key is OPTIONAL again — omitting it generates one instead of throwing",
        edits=[("src/main/kotlin/dev/paylod/Idempotency.kt",
                'if (unsafeGenerated != true) throw PaylodInvalidRequestException(REQUIRED_MESSAGE(what))',
                'if (false) throw PaylodInvalidRequestException(REQUIRED_MESSAGE(what))')],
    ),
    dict(
        id="R7-idem-optout", tag="nv-idem-optout",
        what="a FALSE opt-out is honoured as an opt-out (any value permits a generated key)",
        edits=[("src/main/kotlin/dev/paylod/Idempotency.kt",
                'if (unsafeGenerated != true) throw PaylodInvalidRequestException(REQUIRED_MESSAGE(what))',
                'if (false) throw PaylodInvalidRequestException(REQUIRED_MESSAGE(what))')],
    ),
    dict(
        id="R7-idem-warn-every", tag="nv-idem-warn-every",
        what="the once-per-process warning latch is restored, so a loop of charges warns once",
        edits=[("src/main/kotlin/dev/paylod/Idempotency.kt",
                "    private fun warnUnprotectedCharge(what: String) {",
                "    private val warnedOnce = java.util.concurrent.atomic.AtomicBoolean(false)\n\n"
                "    private fun warnUnprotectedCharge(what: String) {\n"
                "        if (!warnedOnce.compareAndSet(false, true)) return")],
    ),
    dict(
        id="R7-float-status", tag="nv-float-status",
        what="a whole FLOAT result code is collapsed to its integer form again, on the status path "
             "and in the catalog alike",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                # A wire float is now a JsonNumber, so the laundering has to be reintroduced on the
                # lexeme branch — the `is Double` branch it used to live on is unreachable from a
                # parsed body.
                "        is dev.paylod.internal.JsonNumber -> v.lexeme",
                "        is dev.paylod.internal.JsonNumber ->\n"
                "            if (v.toDouble() == Math.floor(v.toDouble()) && v.toDouble() != 0.0)\n"
                "                v.toLong().toString() else v.lexeme"),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "[dev.paylod.internal.JsonNumber].\n"
                "        is JsonNumber -> resultCode.lexeme",
                "[dev.paylod.internal.JsonNumber].\n"
                "        is JsonNumber ->\n"
                "            if (resultCode.toDouble() == Math.floor(resultCode.toDouble()))\n"
                "                resultCode.toLong().toString() else resultCode.lexeme")],
    ),
    dict(
        id="R7-float-webhook", tag="nv-float-webhook",
        what="the webhook reader collapses a whole FLOAT result code to its integer form again "
             "(BOTH the shared lexeme extractor and the webhook's own normalizer reverted)",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        is JsonNumber -> v.lexeme",
                "        is JsonNumber ->\n"
                "            if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.lexeme"),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "[dev.paylod.internal.JsonNumber].\n"
                "        is JsonNumber -> resultCode.lexeme",
                "[dev.paylod.internal.JsonNumber].\n"
                "        is JsonNumber ->\n"
                "            if (resultCode.toDouble() == Math.floor(resultCode.toDouble()))\n"
                "                resultCode.toLong().toString() else resultCode.lexeme")],
    ),
    dict(
        id="R7-detail-retryable", tag="nv-detail-retryable",
        what="the nested detail is passed through unadjusted, so a non-FAILED verdict can expose "
             "detail.retryable = true",
        edits=[("src/main/kotlin/dev/paylod/PaymentOutcome.kt",
                "            if (detail == null || !detail.retryable) detail else detail.copy(retryable = false)",
                "            detail")],
    ),
    dict(
        id="R7-echo-ident", tag="nv-echo-ident",
        what="credential-bearing identifiers are accepted again on the ack and the status read",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                'if (redact.containsCredential(ack["paymentId"] as? String)) {',
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Validators.kt",
                "if (redact.containsCredential(id as? String)) {",
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Validators.kt",
                "if (redact.containsCredential(receipt as? String)) {",
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Validators.kt",
                'if (redact.containsCredential(ack["checkoutRequestId"] as? String)) {',
                "if (false) {")],
    ),
    dict(
        id="R7-echo-text", tag="nv-echo-text",
        what="optional server text is handed back raw, so an echoed token reaches a public toString()",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "            resultDesc = redact.optionalText(resultDesc as String?),",
                "            resultDesc = resultDesc as String?,")],
    ),
    dict(
        id="R7-echo-webhook", tag="nv-echo-webhook",
        what="the webhook reader accepts credential-bearing ids and exposes raw server text",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        if (CredentialShapes.looksLikeCredential(paymentId)) {",
                "        if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (CredentialShapes.looksLikeCredential(data["checkoutRequestId"] as? String)) {',
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (CredentialShapes.looksLikeCredential(data["mpesaReceipt"] as? String)) {',
                "if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                '                resultDesc = redact.optionalText(asString(data["resultDesc"])),',
                '                resultDesc = asString(data["resultDesc"]),')],
    ),
    dict(
        id="R7-failed-noevidence", tag="nv-failed-noevidence",
        what="a FAILED claim with NO evidence is accepted as a terminal failure again",
        edits=[("src/main/kotlin/dev/paylod/Semantics.kt",
                FAILED_NONE_FIND, FAILED_NONE_REPL)],
    ),
    dict(
        id="R7-wh-failed-needscode", tag="nv-wh-failed-needscode",
        what="a payment.failed webhook no longer has to carry a canonical failure code (BOTH the "
             "explicit check and the semantic backstop reverted)",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                '            if (lexeme == null || lexeme.isBlank() || !DarajaCatalog.isCanonicalCodeLexeme(lexeme)) {',
                "            if (false) {"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                '            if (DarajaCatalog.classifyStkResult(lexeme, data["resultDesc"] as? String) != StkOutcome.FAILED) {',
                "            if (false) {"),
               ("src/main/kotlin/dev/paylod/Semantics.kt",
                FAILED_NONE_FIND, FAILED_NONE_REPL)],
    ),

    # ── PREVIOUSLY UNREGISTERED ───────────────────────────────────────────────────────────────
    # These three carried an `nv-` tag and were never in this list, so the protections they name
    # had never once been proven non-vacuous. The completeness assertion below now makes that
    # impossible to repeat.
    dict(
        id="R5-status-typed", tag="nv-status-typed",
        what="the validator asserts and the CALLER re-reads the map, so the record the verdict is "
             "computed from is not the record that was approved",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "            status = status,",
                "            status = PaymentStatus.PENDING,")],
    ),
    dict(
        id="R6-wh-decoded-canonical", tag="nv-wh-decoded-canonical",
        what="the webhook's `decoded` block is taken from the payload instead of the catalog",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                """            if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    // The SECRET-SEEDED redactor, not the shape-only scrubber. `DecodedError` is a
                    // public data class too, and its `description` is server prose.
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""",
                """            @Suppress("UNCHECKED_CAST")
            val block = data["decoded"] as? Map<String, Any?>
            if (block != null) {
                DecodedError(
                    code = block["code"] as String,
                    title = block["title"] as String,
                    cause = block["cause"] as String,
                    fix = block["fix"] as String,
                    category = DarajaCategory.fromWire(block["category"] as String),
                    retryable = block["retryable"] as Boolean,
                    customerMessage = block["customerMessage"] as String,
                )
            } else if (code == null) {
                null
            } else {
                DarajaCatalog.decodeError(
                    normalizeCode(code),
                    redact.optionalText(asString(data["resultDesc"])),
                )
            }""")],
    ),
    dict(
        id="R6-wh-failed-notfailed", tag="nv-wh-failed-notfailed",
        what="a payment.failed whose record assesses as in-flight or indeterminate is delivered",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                'if (type == "payment.failed" && judgement.verdict != PaymentVerdict.FAILED) {',
                "if (false) {")],
    ),
    # ── ROUND 8 ───────────────────────────────────────────────────────────────────────────────
    dict(
        id="R8-classifier-canonical", tag="nv-classifier-canonical",
        what="a non-canonical result code launders into TERMINAL FAILURE at the classifier "
             "(all three guards reverted)",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "        val lexeme = codeLexeme(resultCode)\n"
                "        if (lexeme != null && lexeme.isNotEmpty() && !isCanonicalCodeLexeme(lexeme)) {\n"
                "            return StkOutcome.PENDING\n"
                "        }\n",
                ""),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "            if (!CANONICAL_CODE_RE.matches(raw)) return StkOutcome.PENDING\n",
                ""),
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                'Regex("(?:0|[1-9][0-9]*)(?:\\\\.[0-9]{1,8}){2,6}")',
                'Regex("(?:0|[1-9][0-9]*)(?:\\\\.[0-9]{1,8}){1,6}")'),
               # A FOURTH edit, added this round. The catalog-membership check (requirement 1.5)
               # now also stops a non-canonical code reaching the terminal branch, so reverting the
               # three lexeme guards alone left the guarantee standing and the case came back
               # VACUOUS. That is the harness working: a protection with four independent layers
               # needs all four reverted before the GUARANTEE is actually gone.
               ("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "            if (!knownStkResultCodes.contains(raw)) return StkOutcome.PENDING\n",
                "")],
    ),
    dict(
        id="R8-exotic-message", tag="nv-exotic-message",
        what="the post-acknowledgement wrapper interpolates a hostile getMessage() again, so the "
             "charge handles are lost when it throws",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "            val kind = safeTypeName(e)\n            val detail = safeMessage(e)",
                "            val kind = e.javaClass.simpleName\n            val detail = e.message")],
    ),
    dict(
        id="R8-wh-ident-credential", tag="nv-wh-ident-credential",
        what="applicationId / phone / accountRef accept an echoed credential again",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                'for (field in arrayOf("applicationId", "phone", "accountRef")) {',
                'for (field in arrayOf<String>()) {')],
    ),
    dict(
        id="R8-wh-rawmap-scrub", tag="nv-wh-rawmap-scrub",
        what="verifySignature hands back the raw parsed body again, unknown fields and all "
             "(BOTH public overloads reverted)",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "    ): Map<String, Any?> = refuseOrScrub(\n"
                "        verifySignatureAt(payload, signature, secret, toleranceSec, null), Redactor(listOf(secret)),\n"
                "    )",
                "    ): Map<String, Any?> = verifySignatureAt(payload, signature, secret, toleranceSec, null)"),
               ("src/main/kotlin/dev/paylod/Webhooks.kt",
                "    ): Map<String, Any?> = refuseOrScrub(\n"
                "        verifySignatureAt(boundedBytes(payload), signature, secret, toleranceSec, null),\n"
                "        Redactor(listOf(secret)),\n"
                "    )",
                "    ): Map<String, Any?> = verifySignatureAt(\n"
                "        boundedBytes(payload), signature, secret, toleranceSec, null,\n"
                "    )")],
    ),
    dict(
        id="R8-sim-validators", tag="nv-sim-validators",
        what="simulate.collect() goes back to its own loose body, with amount > 0 as its only rule",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                """        val body = CollectBody.build(
            phone = params.phone ?: DEFAULT_SIM_PHONE,
            amount = params.amount,
            accountReference = params.accountReference,
            description = params.description,
            metadata = params.metadata,
            accountRefField = "accountRef",
            label = "simulate.collect()",
        )""",
                """        if (params.amount <= 0) {
            throw PaylodInvalidRequestException(
                "simulate.collect(): amount must be a positive whole number of KES.",
            )
        }
        val body = LinkedHashMap<String, Any?>()
        body["phone"] = if (params.phone != null) Phone.normalize(params.phone) else DEFAULT_SIM_PHONE
        body["amount"] = params.amount
        if (params.accountReference != null) body["accountRef"] = params.accountReference
        if (params.description != null) body["description"] = params.description
        if (params.metadata != null) body["metadata"] = params.metadata""")],
    ),
    dict(
        id="R8-sim-key", tag="nv-sim-key",
        what="the simulator loses the effective idempotency key — on the returned record and on "
             "every failure path (BOTH reverted)",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                "        } catch (e: PaylodException) {\n"
                "            if (e.idempotencyKey == null) e.idempotencyKey = idempotencyKey\n"
                "            throw e\n"
                "        }",
                "        } catch (e: PaylodException) {\n"
                "            throw e\n"
                "        }"),
               ("src/main/kotlin/dev/paylod/Simulator.kt",
                "            idempotencyKey = idempotencyKey,",
                '            idempotencyKey = "",')],
    ),
    dict(
        id="R8-sim-credential", tag="nv-sim-credential",
        what="the simulator's own server-controlled strings accept an echoed credential again",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                "        if (redact.containsCredential(v)) {", "        if (false) {")],
    ),
    dict(
        id="R8-json-write-bounds", tag="nv-json-write-bounds",
        what="the outbound writer loses its depth, size and cycle bounds (ALL FOUR reverted)",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "        if (sb.length > MAX_WRITE_CHARS) throw budgetExceeded()",
                "        if (false) throw budgetExceeded()"),
               ("src/main/kotlin/dev/paylod/internal/Json.kt",
                "        val remaining = MAX_WRITE_CHARS.toLong() - sb.length.toLong()\n"
                "        if (s.length.toLong() + 2L > remaining) throw budgetExceeded()\n",
                ""),
               ("src/main/kotlin/dev/paylod/internal/Json.kt",
                "        if (depth > MAX_WRITE_DEPTH) {", "        if (false) {"),
               ("src/main/kotlin/dev/paylod/internal/Json.kt",
                "                if (!seen.add(value)) throw cycle()\n                sb.append('{')",
                "                sb.append('{')"),
               ("src/main/kotlin/dev/paylod/internal/Json.kt",
                "                if (!seen.add(value)) throw cycle()\n                sb.append('[')",
                "                sb.append('[')")],
    ),
    dict(
        id="R8-json-escaped-key", tag="nv-json-escaped-key",
        what="a repeated object name resolves last-wins again, so an escaped duplicate can mean "
             "one thing to this parser and another to every other reader",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "                if (out.containsKey(key)) {", "                if (false) {")],
    ),
    dict(
        id="R8-no-decompression", tag="nv-no-decompression",
        what="the SDK asks for a content coding whose EXPANDED size its byte cap does not bound",
        edits=[("src/main/kotlin/dev/paylod/Transport.kt",
                'publicHeaders["accept"] = "application/json"',
                'publicHeaders["accept"] = "application/json"\n'
                '        publicHeaders["accept-encoding"] = "gzip"')],
    ),
    dict(
        id="R8-no-credential-in-message", tag="nv-no-credential-in-cause",
        what="the post-acknowledgement wrapper stops redacting its MESSAGE, so a credential the "
             "foreign throwable mentioned rides out in the message text",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                """            val wrapped = PaylodConnectionException(
                redact.text(
                    "The collect was ACKNOWLEDGED and an STK prompt is live, but waiting for it to " +
                        "settle failed with an unexpected $kind: $detail. " +
                        "The charge state is INDETERMINATE. Read payment ${ack.paymentId} (or retry " +
                        "with THIS idempotencyKey) before starting any new attempt — minting a fresh " +
                        "key would risk a second charge.",
                ),
            )""",
                """            val wrapped = PaylodConnectionException(
                "The collect was ACKNOWLEDGED and an STK prompt is live, but waiting for it to " +
                    "settle failed with an unexpected $kind: $detail. " +
                    "The charge state is INDETERMINATE. Read payment ${ack.paymentId} (or retry " +
                    "with THIS idempotencyKey) before starting any new attempt — minting a fresh " +
                    "key would risk a second charge.",
            )""")],
    ),    # ── ROUND 9 ───────────────────────────────────────────────────────────────────────────────
    dict(
        # THE MUTATION THAT WAS MISSING. Round 9 found that the case above removes only message
        # redaction and never supplies the foreign throwable as a cause — so a CAUGHT verdict
        # established nothing about the CAUSE CHAIN, which is a separate route: `printStackTrace()`
        # walks it without touching a single field. This one keeps the message redacted and
        # reattaches the cause, so only a cause-chain assertion can catch it.
        id="R9-credential-in-cause-chain", tag="nv-adversarial-sweep",
        what="the post-acknowledgement wrapper keeps redacting its message but REATTACHES the "
             "foreign throwable as a cause, so the credential rides out on the cause chain",
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "            val wrapped = PaylodConnectionException(\n                redact.text(",
                "            val wrapped = PaylodConnectionException(\n                cause = e,\n                message = redact.text(")],
    ),
    dict(
        id="R9-wh-secret-not-threaded", tag="nv-wh-refuse-own-secret",
        what="the typed webhook path stops refusing a signed body that echoes the configured "
             "signing secret, so the secret reaches a public field or a diagnostic",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        if (redact.containsCredentialDeep(root)) {",
                "        if (false) {")],
    ),
    dict(
        id="R9-diag-choke-point", tag="nv-diag-choke-point",
        what="an invalid-schema diagnostic interpolates the raw server value again, unredacted "
             "and unbounded",
        edits=[("src/main/kotlin/dev/paylod/internal/Redaction.kt",
                "    fun field(value: Any?): String {\n        if (value == null) return \"absent\"\n        val redacted = text(value.toString())",
                "    fun field(value: Any?): String {\n        if (value == null) return \"absent\"\n        val redacted = value.toString()")],
    ),
    dict(
        id="R9-redact-depth-pin", tag="nv-redact-depth-pin",
        what="the redaction traversal bound drifts back below the parser's, so structures exist "
             "that this SDK parses but cannot scan",
        edits=[("src/main/kotlin/dev/paylod/internal/Redaction.kt",
                "        const val MAX_DEPTH = Json.MAX_DEPTH",
                "        const val MAX_DEPTH = 8")],
    ),
    dict(
        id="R9-short-secret", tag="nv-short-secret-redacted",
        what="configured secrets shorter than eight characters are silently dropped from the "
             "redaction needles again",
        edits=[("src/main/kotlin/dev/paylod/internal/Redaction.kt",
                "        .filter { it.isNotEmpty() }",
                "        .filter { it.length >= 8 }")],
    ),
    dict(
        id="R9-sig-header-bound", tag="nv-sig-header-bound",
        what="the unauthenticated signature header is split with no length bound, so an anonymous "
             "sender commands memory and CPU proportional to its length",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        if (header.length > MAX_SIGNATURE_HEADER_CHARS) return null",
                "        if (false) return null")],
    ),
    dict(
        id="R9-created-integral", tag="nv-created-integral",
        what="signed event `created` accepts a floating-point spelling again, so 1e400 decodes to "
             "infinity and is published as Long.MAX_VALUE",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                "        if (!createdIsIntegral) {",
                "        if (created !is Number || created.toDouble() != Math.floor(created.toDouble())) {")],
    ),
    dict(
        id="R9-baseurl-redaction", tag="nv-adversarial-sweep",
        what="the baseUrl refusal prints the rejected URL verbatim again, so a credential in the "
             "PATH lands in the message a misconfigured integration logs at startup",
        # No quote characters in the replacement: the point of the revert is that the URL is
        # interpolated WITHOUT passing through the redactor, and a quoted spelling only made the
        # mutation a Kotlin syntax error inside the surrounding string literal.
        edits=[("src/main/kotlin/dev/paylod/Paylod.kt",
                "(got ${redact.field(sanitizeUrl(parsed))})",
                "(got ${sanitizeUrl(parsed)})")],
    ),
    dict(
        id="R9-json-escape-decoding", tag="nv-json-escaped-key",
        what="the parser stops decoding \\uXXXX escapes in member names, so an escaped spelling "
             "no longer denotes the key it names",
        edits=[("src/main/kotlin/dev/paylod/internal/Json.kt",
                "                            'u' -> {",
                "                            'u' -> if (true) { sb.append(\"\\\\u\"); } else {")],
    ),
    dict(
        id="R9-sim-outcome-handles", tag="nv-sim-outcome-handles",
        what="simulate.outcome() stops attaching the deterministic settle key and payment id, so "
             "a post-dispatch refusal strands a payment the caller cannot reconcile",
        edits=[("src/main/kotlin/dev/paylod/Simulator.kt",
                "        } catch (e: PaylodException) {\n"
                "            if (e.idempotencyKey == null) e.idempotencyKey = idempotencyKey\n"
                "            if (e.paymentId == null) e.paymentId = paymentId\n"
                "            throw e\n"
                "        }",
                "        } catch (e: PaylodException) {\n            throw e\n        }")],
    ),


    # ══ ROUND 10 — the conformance specification ═══════════════════════════════════════════════
    #
    # One case per requirement closed this round. Each reverts the protection to the behaviour the
    # spec forbids — in most cases the literal pre-round-10 source — so a passing sweep means the
    # guarding test actually distinguishes the two states rather than merely running.
    dict(
        id='R10-receipt-grammar', tag='nv-receipt-grammar',
        what='a receipt is judged by non-emptiness again, not by the positive grammar',
        edits=[('src/main/kotlin/dev/paylod/Semantics.kt', '        return RECEIPT_RE.matches(receipt)', '        return receipt.isNotBlank()')],
    ),
    dict(
        id='R10-receipt-anchor', tag='nv-receipt-grammar-anchor',
        what='the receipt grammar is re-anchored with a dollar sign, which in Java also matches before a trailing newline',
        edits=[('src/main/kotlin/dev/paylod/Semantics.kt', '        return RECEIPT_RE.matches(receipt)', '        return Regex("^[A-Z0-9]{10}$").containsMatchIn(receipt)')],
    ),
    dict(
        id='R10-receipt-placeholder', tag='nv-receipt-placeholder',
        what='sanitizer output is allowed to satisfy the receipt check again',
        edits=[('src/main/kotlin/dev/paylod/Semantics.kt', '        if (CredentialShapes.looksSanitized(receipt)) return false', '        if (false) return false')],
    ),
    dict(
        id='R10-ident-placeholder', tag='nv-ident-placeholder',
        what='a redaction placeholder is accepted as a paymentId, checkoutRequestId and idempotency key (ALL THREE guards reverted)',
        edits=[('src/main/kotlin/dev/paylod/Validators.kt', 'if (CredentialShapes.looksSanitized(ack["paymentId"] as? String)) {', 'if (false) {'),
               ('src/main/kotlin/dev/paylod/Validators.kt', 'if (CredentialShapes.looksSanitized(ack["checkoutRequestId"] as? String)) {', 'if (false) {'),
               ('src/main/kotlin/dev/paylod/Errors.kt', 'if (dev.paylod.internal.CredentialShapes.looksSanitized(key)) {', 'if (false) {')],
    ),
    dict(
        id='R10-retry-language', tag='nv-no-retry-language',
        what='an indeterminate decode tells the customer to try again, beside retryable = false',
        edits=[('src/main/kotlin/dev/paylod/DarajaCatalog.kt', '            customerMessage = "We couldn\'t confirm this payment yet. Please wait while it settles — " +\n                "do not start a new payment.",', '            customerMessage = "The payment didn\'t go through. Please try again.",')],
    ),
    dict(
        # The defect this closes was never "one bad string" -- it was that NOTHING WAS LOOKING at
        # the fallbacks, which is why Python's shipped an invitation for ten review rounds. So the
        # mutation ADDS a fallback that no probe points at. It compiles, it changes no behaviour,
        # and only the coverage guard can notice it.
        id='R10-retry-language-fallback-coverage', tag='nv-no-retry-language',
        what='a decode fallback exists that no probe covers, so a future fallback can ship unchecked',
        edits=[('src/main/kotlin/dev/paylod/DarajaCatalog.kt',
                '    private fun pendingFallback(code: String) = DecodedError(',
                '    @Suppress("unused")\n'
                '    private fun unprobedFallback(code: String) = pendingFallback(code)\n\n'
                '    private fun pendingFallback(code: String) = DecodedError(')],
    ),
    dict(
        id='R11-catalog-drift', tag='nv-catalog-drift',
        what='the vendored Daraja catalog drifts back to the pre-fix wording for code 17, so this SDK '
             'tells a customer something different than the other three for the same result code',
        edits=[('src/main/resources/dev/paylod/daraja-error-codes.json',
                '"customerMessage": "M-Pesa had a problem and we cannot confirm the outcome. Do not pay again yet. Check your M-Pesa messages first."',
                '"customerMessage": "M-Pesa had a hiccup. Please try again in a moment."')],
    ),
    dict(
        id='R11-catalog-key', tag='nv-catalog-key',
        what='a second row claims the (code, family) pair 2001/stk_result, so the catalog key is '
             'ambiguous and a lookup can silently return either of two rows that disagree on retryable',
        edits=[('src/main/resources/dev/paylod/daraja-error-codes.json',
                '"code": "2001",\n      "family": "b2c_c2b_result",',
                '"code": "2001",\n      "family": "stk_result",')],
    ),
    dict(
        id='R10-retryable-agrees', tag='nv-retryable-agrees',
        what='the catalog retryable is passed through on a non-FAILED verdict, so a nested detail.retryable can contradict the top-level one',
        edits=[('src/main/kotlin/dev/paylod/PaymentOutcome.kt', '            if (detail == null || !detail.retryable) detail else detail.copy(retryable = false)', '            detail')],
    ),
    dict(
        id='R10-unknown-code', tag='nv-resolution-table',
        what='a canonically shaped code that is NOT in the catalog becomes terminal failure evidence again',
        edits=[('src/main/kotlin/dev/paylod/DarajaCatalog.kt', '            if (!knownStkResultCodes.contains(raw)) return StkOutcome.PENDING', '            if (false) return StkOutcome.PENDING')],
    ),
    dict(
        id='R10-strict-utf8', tag='nv-strict-utf8',
        what='bytes are decoded with REPLACE semantics again, so malformed input becomes U+FFFD',
        edits=[('src/main/kotlin/dev/paylod/internal/Utf8.kt', '            decoder.decode(ByteBuffer.wrap(bytes)).toString()', '            String(bytes, StandardCharsets.UTF_8)')],
    ),
    dict(
        id='R10-resultcode-cred', tag='nv-resultcode-credential',
        what='a server-controlled resultCode is copied into Payment without a credential check',
        edits=[('src/main/kotlin/dev/paylod/Validators.kt', '        if (redact.containsCredential(resultCode as? String)) {', '        if (false) {')],
    ),
    dict(
        id='R10-decode-redaction', tag='nv-decode-error-redaction',
        what='the public offline decoder stops redacting and stops bounding its server-supplied fields',
        edits=[('src/main/kotlin/dev/paylod/DarajaCatalog.kt', '    private fun sanitized(d: DecodedError): DecodedError = d.copy(\n        code = Redactor.SHAPES_ONLY.text(d.code).take(MAX_DECODED_FIELD_CHARS),\n        cause = Redactor.SHAPES_ONLY.text(d.cause).take(MAX_DECODED_FIELD_CHARS),\n    )', '    private fun sanitized(d: DecodedError): DecodedError = d')],
    ),
    dict(
        id='R10-lexeme', tag='nv-lexeme-preserved',
        what="the parser discards the sender's numeric token and re-renders it from the decoded value",
        edits=[('src/main/kotlin/dev/paylod/internal/Json.kt', '                if (token == "-0") return JsonNumber(token, -0.0)\n                token.toLongOrNull()?.let { return JsonNumber(token, it) }', '                if (token == "-0") return JsonNumber("0", 0L)\n                token.toLongOrNull()?.let { return JsonNumber(it.toString(), it) }')],
    ),
    dict(
        id='R10-json-duplicate', tag='nv-json-escaped-dup',
        what='a repeated object name resolves last-value-wins instead of being refused',
        edits=[('src/main/kotlin/dev/paylod/internal/Json.kt', '                if (out.containsKey(key)) {', '                if (false) {')],
    ),
    dict(
        id='R10-single-reader', tag='nv-single-reader',
        what='a second JSON reader appears in the tree, which the one-reader assertion must see',
        edits=[('src/main/kotlin/dev/paylod/internal/Json.kt', 'internal object Json {', '// a second reader would be spelled ObjectMapper\ninternal object Json {')],
    ),
    dict(
        id='R10-redact-depth', tag='nv-parse-depth',
        what='the redaction traversal bound drifts BELOW the parse bound -- the historical 8-vs-64',
        edits=[('src/main/kotlin/dev/paylod/internal/Redaction.kt', '        const val MAX_DEPTH = Json.MAX_DEPTH', '        const val MAX_DEPTH = 8')],
    ),
    dict(
        id='R10-lower-trim', tag='nv-lower-layer-form',
        what='the lowest normalization helper trims again, laundering a padded code into a canonical one below every check written to reject it',
        edits=[('src/main/kotlin/dev/paylod/DarajaCatalog.kt', '            else -> resultCode.toString()\n        }', '            else -> resultCode.toString().trim()\n        }')],
    ),
    dict(
        id='R10-sign-bound', tag='nv-webhook-bounds',
        what='the public ByteArray signer computes an HMAC before bounding the body',
        edits=[('src/main/kotlin/dev/paylod/Webhooks.kt', '        assertWithinLimit(payload.size)\n        val v1 = hmacHex(secret, timestampSec.toString(), payload)', '        val v1 = hmacHex(secret, timestampSec.toString(), payload)')],
    ),
]

COUNT_RE = re.compile(r"NVCOUNT tests=(\d+) failed=(\d+) skipped=(\d+)")


class Run:
    """One gradle invocation, kept WITH its exit code so a verdict can never be read off the
    counters alone. `trusted` is the single gate every classification below goes through."""

    def __init__(self, rc, ran, failed, skipped):
        self.rc = rc
        self.ran = ran
        self.failed = failed
        self.skipped = skipped

    @property
    def reported(self):
        return self.ran is not None

    @property
    def trusted(self):
        # Exit code zero is the EXPECTED code even for a run whose tests failed, because the test
        # task sets `ignoreFailures` under -PnvTag. So non-zero here is never "a test failed" —
        # it is the build not finishing, and its counters describe a partial run.
        return self.reported and self.rc == 0

    def describe(self):
        if not self.reported:
            return f"exit={self.rc}, no NVCOUNT line"
        return f"exit={self.rc}, tests={self.ran}, failed={self.failed}, skipped={self.skipped}"


def run_tag(tag):
    """
    Run only the tests carrying `tag`.

    Returns (ran, failed, skipped), or None when the RUN ITSELF is untrustworthy.

    ── Why the exit code is load-bearing here ────────────────────────────────────────────────
    This used to return as soon as it found an `NVCOUNT` line, ignoring both `proc.returncode`
    and the skipped count the line already carried. Both omissions manufacture false verdicts:

      • The build task sets `ignoreFailures = (nvTag != null)`, so a tagged run exits 0 EVEN
        WHEN TESTS FAIL — failure is reported through the NVCOUNT counters, not the exit status.
        A non-zero exit therefore never means "a test failed"; it means the build itself broke
        (compile error, daemon death, OOM, an exception in `doLast`). But `NVCOUNT` is emitted
        from `doLast` and is happily scraped out of a partially-completed build, so a crashed
        run that got far enough to print the line was accepted as a measurement. On a MUTATED
        run that reads as `failed=0` -> VACUOUS at best, and if the crash came after some tests
        failed, as CAUGHT — a "caught" verdict produced by a build that did not finish.

      • A SKIPPED test is not a live selector. `ran` counts it, so a case whose guarding test was
        disabled, filtered, or aborted by an assumption still reported `ran > 0` and passed the
        liveness gate — and then "passed" the mutated run without ever executing, which is
        precisely the vacuity this harness exists to detect, wearing a green badge.

    So a trusted measurement now requires exit code ZERO **and** zero skipped tests, on the clean
    run and the mutated run alike. Anything else is classified as broken rather than as a verdict.
    """
    proc = subprocess.run(
        ["./gradlew", "test", "--console=plain", "--rerun-tasks", f"-PnvTag={tag}"],
        cwd=ROOT, capture_output=True, text=True, timeout=900,
    )
    out = proc.stdout + proc.stderr
    m = COUNT_RE.search(out)
    rc = proc.returncode
    if not m:
        # A run with no NVCOUNT is a run that told us nothing, and the reason is in the output we
        # were about to throw away. Diagnosing this used to mean reproducing the mutation by hand.
        sys.stderr.write(
            "\n--- UNTRUSTED RUN (tag=%s, rc=%s): no NVCOUNT line. Last 40 lines ---\n%s\n"
            % (tag, rc, "\n".join(out.splitlines()[-40:]))
        )
        return Run(rc, None, None, None)
    return Run(rc, int(m.group(1)), int(m.group(2)), int(m.group(3)))


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    with open(os.path.join(ROOT, rel), "w", encoding="utf-8") as f:
        f.write(text)


# ── EVERY nv-TAGGED PROTECTION MUST BE LISTED ────────────────────────────────────────────────
#
# Three tests carried an `nv-` tag and were never registered here — nv-status-typed,
# nv-wh-decoded-canonical and nv-wh-failed-notfailed — so three protections advertised themselves
# as non-vacuity-verified and had never once been reverted. That is a worse failure than a missing
# test: it is a missing test wearing the badge of a verified one, and nothing in the tooling could
# see it, because the harness only ever looked at its own list.
#
# So the list is now checked against the SOURCE OF TRUTH — the tags actually present in the test
# tree — before any case runs. An unlisted tag is a hard failure, not a warning: a warning at the
# top of a fifteen-minute run is a warning nobody reads.
# ASCII tag characters only. A looser `[^"]+` also matched the literal `@Tag("nv-…")` that appears
# inside the KDoc of the test files, which is prose, not a tag.
TAG_RE = re.compile(r'@Tag\("(nv-[A-Za-z0-9-]+)"\)')


def discover_tags():
    found = set()
    for dirpath, _, filenames in os.walk(os.path.join(ROOT, "src", "test")):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                found.update(TAG_RE.findall(f.read()))
    return found


declared = {c["tag"] for c in CASES}
present = discover_tags()

unlisted = sorted(present - declared)
if unlisted:
    print(
        "UNREGISTERED nv TAGS — these protections claim non-vacuity verification and have never "
        "been reverted:\n  " + "\n  ".join(unlisted),
        file=sys.stderr,
    )
    sys.exit(2)

# The converse is a failure too: a case naming a tag no test carries is a selector that will match
# zero tests, which the per-case liveness gate catches — but catching it here costs nothing and
# reports it as the bookkeeping error it is rather than as a broken selector.
missing = sorted(declared - present)
if missing:
    print(
        "CASES NAME TAGS THAT NO TEST CARRIES:\n  " + "\n  ".join(missing),
        file=sys.stderr,
    )
    sys.exit(2)

print(f"{len(declared)} nv tags declared, {len(present)} present in the test tree, all matched.\n",
      flush=True)

results = []

# `--only <id>[,<id>...]` runs a subset. A full sweep is ~15 minutes, and diagnosing ONE broken
# case by re-running all 92 is how a broken case gets waved through instead of understood.
ONLY = None
for _a in sys.argv[1:]:
    if _a.startswith("--only="):
        ONLY = set(_a.split("=", 1)[1].split(","))
if ONLY:
    # EVERY REQUESTED ID MUST EXIST. An unknown `--only` id used to select ZERO cases, run
    # nothing, print "0/0 mutations caught" and exit 0 -- a successful-looking run that measured
    # absolutely nothing. That is the same failure this whole harness exists to detect (a selector
    # matching nothing while the runner reports success), sitting in the harness itself; a review
    # probe confirmed it by passing a made-up id. A typo in a case id must be an error.
    unknown = sorted(ONLY - {c["id"] for c in CASES})
    if unknown:
        print("--only names case ids that do not exist:\n  " + "\n  ".join(unknown), file=sys.stderr)
        sys.exit(2)
    CASES = [c for c in CASES if c["id"] in ONLY]
    print(f"--only: running {len(CASES)} case(s): {', '.join(c['id'] for c in CASES)}\n", flush=True)

for case in CASES:
    tag = case["tag"]

    # 1. THE SELECTOR MUST BE LIVE. A tag matching nothing exits 0 and reads as "not caught".
    live = run_tag(tag)
    # ZERO-MATCH IS DIAGNOSED FIRST, before the exit-code gate. The build now FAILS a tagged run
    # that selected nothing (see build.gradle.kts), so such a run is untrusted by exit code too --
    # and reporting it as "the build broke" would bury the actual cause, which is a selector that
    # matches no tests. The specific message is the useful one.
    if live.ran == 0:
        results.append((case, "BROKEN-SELECTOR", "tag matches 0 tests", live, None))
        continue
    if not live.trusted:
        results.append((case, "BROKEN-BUILD", "clean run not trustworthy", live, None))
        continue
    if live.skipped:
        # A skipped test cannot catch anything. Counting it as live is how a disabled guard keeps
        # reporting CAUGHT.
        results.append((case, "BROKEN-SELECTOR",
                        f"{live.skipped} of {live.ran} selected test(s) were SKIPPED on clean source",
                        live, None))
        continue
    if live.failed:
        results.append((case, "BROKEN-SELECTOR",
                        f"{live.failed} test(s) already fail on clean source", live, None))
        continue

    # 2. THE ANCHORS MUST BE UNIQUE. Otherwise a no-op edit looks like a caught mutation.
    originals = {}
    anchors_ok = True
    for rel, find, _ in case["edits"]:
        if rel not in originals:
            originals[rel] = read(rel)
        if originals[rel].count(find) != 1:
            results.append((case, "BROKEN-ANCHOR",
                            f"{rel} matched {originals[rel].count(find)}x", live, None))
            anchors_ok = False
            break
    if not anchors_ok:
        continue

    # 3. REVERT, RUN, RESTORE.
    after = None
    try:
        mutated = dict(originals)
        for rel, find, repl in case["edits"]:
            mutated[rel] = mutated[rel].replace(find, repl, 1)
        for rel, text in mutated.items():
            write(rel, text)

        after = run_tag(tag)
        if not after.trusted:
            status, detail = "BROKEN-MUTATION", "mutated run not trustworthy"
        elif after.skipped > 0:
            # Tests that did not run cannot have caught the mutation, whatever the failure count
            # says about the ones that did.
            status = "BROKEN-MUTATION"
            detail = f"{after.skipped} of {after.ran} test(s) SKIPPED after mutation"
        elif after.ran == 0:
            status, detail = "BROKEN-SELECTOR", "0 tests ran after mutation"
        elif after.failed > 0:
            status = "CAUGHT"
            detail = f"{after.failed} of {after.ran} test(s) failed"
        else:
            status, detail = "VACUOUS", f"all {after.ran} test(s) still PASSED"
    finally:
        for rel, text in originals.items():
            write(rel, text)

    results.append((case, status, f"{detail}; selector covers {live.ran} test(s)", live, after))
    # Report AS WE GO, flushed. The table used to be printed only at the very end, so a run that
    # was interrupted — and a full pass takes a while — produced nothing at all, discarding every
    # case it had already settled. Progress that only exists at the end is progress you can lose.
    print(f"[{len(results)}/{len(CASES)}] {case['id']:<24} {status:<16} {detail}", flush=True)

# The table shows BOTH runs in full — exit code, tests, failed, skipped — because those are the
# numbers the verdict is derived from, and a verdict whose inputs are not shown is a verdict you
# have to take on trust.
print("\n| id | reverted protection | guarding tag | clean run | mutated run | result |")
print("| --- | --- | --- | --- | --- | --- |")
for case, status, detail, live, after in results:
    clean = live.describe() if live else "not run"
    mutated = after.describe() if after else "not run"
    print(f"| {case['id']} | {case['what']} | {case['tag']} | {clean} | {mutated} | {status} ({detail}) |")

# A sweep that measured nothing is not a passing sweep.
if not results:
    print("NO RESULTS -- refusing to report a verdict for a sweep that ran nothing.", file=sys.stderr)
    sys.exit(2)

bad = [(c, s) for c, s, _, _, _ in results if s != "CAUGHT"]
print(f"\n{len(results) - len(bad)}/{len(results)} mutations caught.")
if bad:
    print("NOT ALL MUTATIONS CAUGHT: " + ", ".join(f"{c['id']}={s}" for c, s in bad),
          file=sys.stderr)
    sys.exit(1)
