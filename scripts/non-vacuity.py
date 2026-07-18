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
`--console=plain`, the build emits a machine-readable `NVCOUNT tests=N failed=M` line from a real
test listener, and a case is only trusted once its tag is shown to select N > 0 tests that all
PASS on clean source. Anything else is reported as a broken case rather than a verdict.
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
                """        val decoded = if (rawType == "payment.failed") {
            val code = data["resultCode"]
            if (code == null) null else DarajaCatalog.decodeError(normalizeCode(code), asString(data["resultDesc"]))
        } else {
            null
        }""",
                """        val decodedMap = data["decoded"] as? Map<String, Any?>
        val decoded = if (decodedMap != null) {
            DecodedError(
                code = decodedMap["code"]?.toString() ?: "",
                title = decodedMap["title"]?.toString() ?: "",
                cause = decodedMap["cause"]?.toString() ?: "",
                fix = decodedMap["fix"]?.toString() ?: "",
                category = DarajaCategory.fromWire(decodedMap["category"]?.toString() ?: "mpesa_system"),
                retryable = decodedMap["retryable"] as? Boolean ?: false,
                customerMessage = decodedMap["customerMessage"]?.toString() ?: "",
            )
        } else {
            null
        }""")],
    ),
    dict(
        id="R5-wh-decoded-malformed", tag="nv-wh-decoded-malformed",
        what="the same revert, seen as a raw parser exception escaping a handler",
        edits=[("src/main/kotlin/dev/paylod/Webhooks.kt",
                """        val decoded = if (rawType == "payment.failed") {
            val code = data["resultCode"]
            if (code == null) null else DarajaCatalog.decodeError(normalizeCode(code), asString(data["resultDesc"]))
        } else {
            null
        }""",
                """        val decodedMap = data["decoded"] as? Map<String, Any?>
        val decoded = if (decodedMap != null) {
            DecodedError(
                code = decodedMap["code"]?.toString() ?: "",
                title = decodedMap["title"]?.toString() ?: "",
                cause = decodedMap["cause"]?.toString() ?: "",
                fix = decodedMap["fix"]?.toString() ?: "",
                category = DarajaCategory.fromWire(decodedMap["category"]?.toString() ?: "mpesa_system"),
                retryable = decodedMap["retryable"] as? Boolean ?: false,
                customerMessage = decodedMap["customerMessage"]?.toString() ?: "",
            )
        } else {
            null
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
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS")],
    ),
    dict(
        id="R5-zero-evidence", tag="nv-zero-evidence",
        what="the same revert, seen as a zero impostor manufacturing PAID evidence",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS",
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS")],
    ),
    dict(
        id="R5-zero-webhook", tag="nv-zero-webhook",
        what="the same revert, on the webhook delivery path",
        edits=[("src/main/kotlin/dev/paylod/DarajaCatalog.kt",
                "if (isCanonicalSuccessCode(resultCode)) return StkOutcome.SUCCESS",
                "if (raw.toDoubleOrNull() == 0.0) return StkOutcome.SUCCESS")],
    ),
    dict(
        id="R5-zero-launder", tag="nv-zero-nolaunder",
        what="a JSON float zero is collapsed into the canonical success code again",
        edits=[("src/main/kotlin/dev/paylod/Validators.kt",
                "            if (v == Math.floor(v) && v != 0.0) v.toLong().toString() else v.toString()",
                "            if (v == Math.floor(v)) v.toLong().toString() else v.toString()")],
    ),
]

COUNT_RE = re.compile(r"NVCOUNT tests=(\d+) failed=(\d+) skipped=(\d+)")


def run_tag(tag):
    """Run only the tests carrying `tag`. Returns (ran, failed) or None if the build broke."""
    proc = subprocess.run(
        ["./gradlew", "test", "--console=plain", "--rerun-tasks", f"-PnvTag={tag}"],
        cwd=ROOT, capture_output=True, text=True, timeout=900,
    )
    out = proc.stdout + proc.stderr
    m = COUNT_RE.search(out)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    with open(os.path.join(ROOT, rel), "w", encoding="utf-8") as f:
        f.write(text)


results = []

for case in CASES:
    tag = case["tag"]

    # 1. THE SELECTOR MUST BE LIVE. A tag matching nothing exits 0 and reads as "not caught".
    live = run_tag(tag)
    if live is None:
        results.append((case, "BROKEN-BUILD", "clean source did not build/report"))
        continue
    ran, failed = live
    if ran == 0:
        results.append((case, "BROKEN-SELECTOR", "tag matches 0 tests"))
        continue
    if failed:
        results.append((case, "BROKEN-SELECTOR", f"{failed} test(s) already fail on clean source"))
        continue

    # 2. THE ANCHORS MUST BE UNIQUE. Otherwise a no-op edit looks like a caught mutation.
    originals = {}
    anchors_ok = True
    for rel, find, _ in case["edits"]:
        if rel not in originals:
            originals[rel] = read(rel)
        if originals[rel].count(find) != 1:
            results.append((case, "BROKEN-ANCHOR",
                            f"{rel} matched {originals[rel].count(find)}x"))
            anchors_ok = False
            break
    if not anchors_ok:
        continue

    # 3. REVERT, RUN, RESTORE.
    try:
        mutated = dict(originals)
        for rel, find, repl in case["edits"]:
            mutated[rel] = mutated[rel].replace(find, repl, 1)
        for rel, text in mutated.items():
            write(rel, text)

        after = run_tag(tag)
        if after is None:
            status, detail = "BROKEN-MUTATION", "mutated source did not compile"
        else:
            m_ran, m_failed = after
            if m_failed > 0:
                status = "CAUGHT"
                detail = f"{m_failed} of {m_ran} test(s) failed"
            elif m_ran == 0:
                status, detail = "BROKEN-SELECTOR", "0 tests ran after mutation"
            else:
                status, detail = "VACUOUS", f"all {m_ran} test(s) still PASSED"
    finally:
        for rel, text in originals.items():
            write(rel, text)

    results.append((case, status, f"{detail}; selector covers {ran} test(s)"))
    # Report AS WE GO, flushed. The table used to be printed only at the very end, so a run that
    # was interrupted — and a full pass takes a while — produced nothing at all, discarding every
    # case it had already settled. Progress that only exists at the end is progress you can lose.
    print(f"[{len(results)}/{len(CASES)}] {case['id']:<24} {status:<16} {detail}", flush=True)

print("\n| id | reverted protection | guarding tag | result |")
print("| --- | --- | --- | --- |")
for case, status, detail in results:
    print(f"| {case['id']} | {case['what']} | {case['tag']} | {status} ({detail}) |")

bad = [(c, s) for c, s, _ in results if s != "CAUGHT"]
print(f"\n{len(results) - len(bad)}/{len(results)} mutations caught.")
if bad:
    print("NOT ALL MUTATIONS CAUGHT: " + ", ".join(f"{c['id']}={s}" for c, s in bad),
          file=sys.stderr)
    sys.exit(1)
