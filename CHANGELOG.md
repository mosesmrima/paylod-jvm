# Changelog

All notable changes to `dev.paylod:paylod` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
