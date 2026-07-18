# Changelog

All notable changes to `dev.paylod:paylod` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-07-18

Second-pass hardening from a codex re-verification of 0.2.0. The first pass was directionally right
but incomplete; each item below closes a hole that survived it. Signing is unchanged — the shared
golden webhook vector still passes byte-for-byte, and the Java interop suite is unchanged in shape.

### Money-correctness

- **Family-aware decoding is now airtight.** `DarajaCatalog.decodeError` could still return an STK
  *pending* entry for an explicitly non-STK lookup: `decodeError("4999", family = API_ERROR)` fell
  through a last-resort `matches.firstOrNull()` and decoded as "payment still in progress". A code
  that arrived on a terminal surface has no in-flight semantics, so telling a caller to keep polling
  was exactly the 4999 bug the family-awareness was added to kill. A non-STK lookup now selects the
  requested family, or another non-STK entry, or the terminal **non-retryable** fallback — never an
  STK pending entry. The STK surface is unchanged.

### Reliability / deadlines

- **`Retry-After` is parsed properly.** It was read from a lowercase-only key, parsed as a bare
  number, and then truncated to ten seconds. It is now read **case-insensitively** and parsed in both
  RFC 9110 forms — delta-seconds *and* HTTP-date — with unusable values (`1e3`, `+5`, an unparseable
  or past date) ignored rather than misread as a delay. The flat 10s truncation is gone: a server that
  asks for 60s gets 60s, because retrying at 10s under the same `Idempotency-Key` only adds load to a
  system that just said it needs room.
- **The deadline bounds every sleep.** A `Retry-After` (or backoff) is clamped to the operation's
  remaining deadline, so a throttled poll can no longer overrun a `wait(timeoutMs = …)` budget. Where
  there is no deadline at all, a single sleep is ceilinged at 60s so a hostile `Retry-After: 86400`
  cannot park a caller's thread for a day.

### Security

- **`baseUrl` is an ORIGIN ALLOWLIST, not just an `https://` check.** Any HTTPS host was accepted, so
  `PAYLOD_BASE_URL=https://attacker.example` would ship a live `Bearer mp_live_…` header to an origin
  of the attacker's choosing — a full credential handover from one environment variable. The host must
  now be **exactly** `paylod.dev` or `api.paylod.dev` on port 443 (an exact set, not a
  `.paylod.dev` suffix match, which would wave through any subdomain). The URL shapes used to smuggle
  a different effective origin past a naive check are refused too: **userinfo**
  (`https://paylod.dev@attacker.example`), a missing host, a non-443 port, a query string or fragment,
  and raw IP literals (private, link-local, and loopback). The test-only loopback exception is kept
  and now covers **both** schemes — an `https://localhost` dev server needs the same explicit
  `allowInsecureBaseUrl` opt-in as a plaintext one, rather than passing on its scheme alone — and is
  still **never** allowed with an `mp_live_` key.
- **Webhook replay tolerance can no longer be switched off — by anyone.** 0.2.0 allowed a
  non-positive `toleranceSec` whenever a fixed `nowSec` was injected. That is a disabled anti-replay
  window reachable from production code that happens to pass a clock. A positive tolerance is now
  required **unconditionally** (0 and negative are both refused), an injected `nowSec` is validated,
  and the pinned fixed-vector tests verify their ancient fixtures the right way: a **normal** window
  plus a pinned clock.
- **Strict webhook timestamp parsing.** `t` is validated lexically as decimal digits only, so `1e3`,
  `+1000`, `-1000`, `0x3e8` and `1000.0` are malformed rather than coerced by a lenient numeric parse.
- **Idempotency key charset tightened.** Rejection now covers the **full** Unicode control ranges
  (C0, C1, DEL) and Unicode-only whitespace / zero-width characters (NBSP, ZWSP, BOM, line separator,
  ideographic space) — a key like `"order-123<ZWSP>"` is visually identical to `"order-123"` in every
  log and dashboard while being a different key on the wire, which is precisely how a "protected"
  retry becomes a second charge. Length is bounded in **UTF-8 bytes** (255), not `String` chars, which
  is what a server actually counts.

## [0.2.0] - 2026-07-18

Security- and money-correctness hardening. Mirrors the canonical `@paylod/node` 0.4.0 review, plus
JVM-specific findings from a codex review of this SDK. Signing is unchanged — the shared golden
webhook vector still passes. Only parsing/validation got stricter.

### Money-correctness

- **Raw `status` can no longer override the classifier.** `Outcomes.of()` derives the outcome from
  `classifyStkResult` alone whenever a `resultCode` is present; the raw `status` field can never force
  a `paid` result. `status:"success"` carrying a pending code (`4999`) or a failure code (`1032`) is
  no longer reported as paid. A genuine contradiction between two terminal signals (e.g.
  `success` + `1032`, or `failed` + `0`) is treated as **indeterminate** — not paid, not retryable —
  and surfaced as `PENDING` so `wait()` lets it settle rather than reporting a false success.
- **Catalog `retryable` flags corrected (owner-approved).** Codes **17, 26, 1025, 9999** changed from
  `retryable:true` to `retryable:false` — "safe to charge again" had been set on non-authoritative
  community evidence. The catalog JSON is now byte-identical to the canonical `@paylod/node` table.
- **Family-aware decoding.** `DarajaCatalog.decodeError` no longer routes every code through the STK
  classifier. Dotted `api_error` codes and alphanumeric `b2c_c2b_result` codes decode terminally by
  family; the overloaded `500.001.1001` decodes as the terminal server error on `api_error` and as
  "still processing" on the STK surface. `"insufficient funds"` was added to the terminal-500 matcher.

### Idempotency / double-charge

- **Idempotency keys are validated** in both `collect()` and `simulate.collect()`: blank,
  whitespace-only, control-character, and over-long keys are rejected up front.
- **A generated key is never lost on failure.** When `collect()` throws, the effective key is attached
  to the thrown exception (`PaylodException.idempotencyKey`) so a caller can retry with the SAME key.
- **In-progress `409` handling.** Only an explicit "already in progress" `409` is retried (bounded by
  `maxRetries`, honouring `Retry-After`). Body-conflict and indeterminate 409s stay terminal.
- **The request body is snapshotted once** before the retry loop, so a mutable `metadata` map cannot
  change under a fixed Idempotency-Key between attempts.

### Security

- **HTTPS enforced on `baseUrl`.** A non-HTTPS origin is refused at construction. Loopback HTTP is
  permitted only behind the new test-only `allowInsecureBaseUrl` flag, never with an `mp_live_` key.
- **Redirects are refused.** The default transport uses `HttpClient.Redirect.NEVER`, so a cross-origin
  3xx can never carry the `Authorization` header to another host.
- **Secrets redacted.** `HttpRequestSpec.toString()` redacts the `Authorization` and `Idempotency-Key`
  headers, so a stray log line or wrapped exception cannot leak the API key.
- **Interrupts are not retried.** A mid-request `InterruptedException` restores the interrupt flag and
  aborts as a `PaylodInterruptedException` instead of being mistaken for a transient network failure.

### Robustness

- **Malformed 2xx is indeterminate.** A `collect()` / `status()` 2xx with no payment id raises a
  `PaylodApiException` (new `indeterminate` flag; `collect()` also carries the idempotency key)
  instead of silently returning an empty id.
- **`wait()` respects its deadline.** The remaining deadline is propagated into every poll; each
  request timeout and every `Retry-After` / backoff sleep is capped to it.
- **Webhook header strictness.** The signature header must carry exactly one integer `t` and exactly
  one 64-char lowercase-hex `v1`; duplicate, malformed, or comma-combined multi-value headers are
  rejected. A non-positive `toleranceSec` is refused (new `INSECURE_TOLERANCE` reason) unless a fixed
  `nowSec` is injected. `verify()` returns `false` for all schema/type failures, not only signature ones.
- **`accountReference` / `description` are validated and transmitted as the same trimmed value**, and
  a provided-but-blank value is rejected.
- **Catalog collections are unmodifiable.** `DarajaCatalog.allEntries` / `pendingResultCodes` (and
  entry `sources`) are exposed as unmodifiable views, unreachable-to-mutate from Java.

### Docs

- Corrected the "zero runtime dependencies" claim: the SDK has **no third-party** runtime dependencies
  beyond the Kotlin standard library (which the POM depends on).

## [0.1.0] - 2026-07-18

First release. The JVM (Kotlin + Java) client for the hosted paylod M-Pesa API, a faithful port of
the surface and behaviour of `@paylod/node`.

### Added

- `Paylod` client — construct with an API key (`mp_test_` / `mp_live_`); base URL is baked in.
- `collect(...)` — send an STK Push, returns a `CollectAck` as soon as the prompt is on the phone.
  Available as a `CollectParams` object, a Java builder, and telescoping `collect(phone, amount, ...)`
  overloads.
- `collectAndWait(...)` — collect, then poll with a jittered backoff ramp (1s -> 5s) to a terminal
  `PaymentOutcome` (`SUCCEEDED` / `FAILED` / `CANCELLED`), or a thrown `PaylodTimeoutException` if
  still pending at the deadline.
- `status(paymentId)` / `check(paymentId)` — raw payment read, and its decoded, renderable form.
- `wait(paymentId, ...)` — poll an existing payment to settlement.
- `verifyWebhook(rawBody, signatureHeader, secret)` -> `Boolean`, plus `parseWebhook(...)` for the
  typed `WebhookEvent`. HMAC-SHA256 over `${t}.${rawBody}`, `t=,v1=` header, 300s replay tolerance,
  constant-time compare. `Webhooks.sign(...)` is exposed for building test fixtures.
- `decodeError(code)` — offline M-Pesa result-code decoding from the catalog the API itself uses
  (`DarajaCatalog`, ported verbatim from the canonical `daraja-error-codes.json`).
- `simulate` — the sandbox simulator (`collect` / `outcome` / `pay`) driving the five outcomes with
  no handset. Refuses a `mp_live_` key locally, before any request leaves the process.
- Local MSISDN normalisation and validation (`Phone`), mirroring the backend spec.
- Zero runtime dependencies: HTTP on the JDK's `java.net.http.HttpClient`, JSON via a small internal
  reader/writer.

### Notes

- `retryable` means **safe to charge again** — an in-flight/pending payment is never retryable.
- A pending code (`4999` / `500.001.1001`) on a row the API marks `failed` is classified as pending,
  so a customer mid-PIN is never reported as a failure.
- Sync API for v1. Coroutine / `CompletableFuture` variants could be layered on later without
  changing this surface.

[0.2.0]: https://github.com/mosesmrima/paylod-jvm/releases/tag/v0.2.0
[0.1.0]: https://github.com/mosesmrima/paylod-jvm/releases/tag/v0.1.0
