# Changelog

All notable changes to `dev.paylod:paylod` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/mosesmrima/paylod-jvm/releases/tag/v0.1.0
