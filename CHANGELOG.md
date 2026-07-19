# Changelog

All notable changes to `dev.paylod:paylod` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.0] - 2026-07-19

A seventh independent review, conducted against the threat model in `SECURITY.md`. Two High
findings on the money path, three Medium, one Low. Every fix carries a test that is verified
non-vacuously by `scripts/non-vacuity.py`, which reverts the fix in the source and requires the
guarding test to FAIL. Signing is unchanged (the shared golden webhook vector still matches
byte-for-byte) and the Java interop suite still passes.

### HIGH — a non-canonical result code became TERMINAL FAILURE evidence at the classifier

`DarajaCatalog.decodeError` has required a canonical code lexeme before a catalog lookup since
0.6.0. `classifyStkResult` — the call that actually produces the money verdict — did not. It went
through `normalizeCode`, which TRIMS, so `" 1032"`, `"1032\n"`, `"1032 "`, `"+1032"`, `"01032"`
and the string `"1032.0"` all parsed as a non-zero finite number and returned `FAILED`. A token
Daraja never sent became proof of terminal failure, and `PaymentSemantics` turned it into a
`FAILED` verdict on a payment whose real state nobody knew.

`"1032.0"` is the worst of the set, because it is what `PaymentValidators.normalizeResultCode`
produces *deliberately* for a raw JSON `1032.0` — precisely so the float cannot be laundered into
`"1032"`. It then matched `CANONICAL_DOTTED_RE`, which accepted a TWO-component dotted value, and
was pronounced canonical. Every dotted code Daraja actually issues has three components, so that
pattern now requires at least two dots and the two-component space — the one in which a decimal
float and a business code are spelled identically — no longer exists. Three guards now stand where
one did: the lexeme check at the classifier, the tightened dotted pattern, and a bare-decimal
requirement on the terminal branch itself.

A non-canonical code is neither proof of success nor proof of failure. It is ambiguous, and
ambiguity resolves to `PENDING`/`INDETERMINATE` — never to a terminal verdict. A genuine `1032`,
`0`, `4999` and `500.001.1001` are unaffected.

### HIGH — an exotic throwable took the reconciliation handles down with it

After a collect is ACKNOWLEDGED, an STK prompt is live on a handset and the idempotency key is the
only safe way to ask about it. The fallback wrapper in `collectAndWait` built its message — which
interpolates `e.message` — BEFORE assigning `idempotencyKey` and `paymentId`.

`Throwable.getMessage()` is ordinary overridable code. A third-party throwable is free to compute
it lazily and fail while doing so, and that throw happens *inside the string template*, so the
catch block itself unwinds and the caller receives that second throwable with NO handles at all —
for a live charge. The whole point of the block is that no failure after acknowledgement loses the
handles, and the block was reachable in a state where it lost them.

Every term of the message now comes from a helper that cannot propagate, and the same treatment is
applied to the two places in `Transport.kt` that format a foreign throwable's message — one of
them inside a `Flow.Subscriber`, where a throw would have left the result future uncompleted.

### MEDIUM — server-controlled credentials on the webhook surface

Two gaps, both on objects a handler logs on its first line:

- `data.paymentId`, `data.checkoutRequestId` and `data.mpesaReceipt` were refused when they carried
  something credential-shaped. `data.applicationId`, `data.phone` and `data.accountRef` were not —
  and all six land on the same `WebhookEventData` data class, whose generated `toString()` prints
  every field. The guard covered the fields someone thought of rather than every identifier on the
  object. All six are now refused.
- `Webhooks.verifySignature` returns THE WHOLE PARSED BODY: unknown fields, nested objects, array
  elements and object NAMES, none of which any allowlist sees. A signature proves who sent the
  bytes; it does not stop a compromised or buggy signer echoing an `Authorization: Bearer` header —
  or the webhook signing secret itself — into a field of its choosing. The returned map is now
  deep-scrubbed by a redactor seeded with the caller's own secret. The typed `parseAndVerify` path
  is unchanged: it still refuses rather than masks.

### MEDIUM — the simulator diverged from production on the collect path

`simulate.collect()` enforced `amount > 0` and nothing else, so it accepted 150,001 KES, a
whitespace-only `accountReference` and a 200-character `description` — every one of which
`collect()` refuses. A simulator LOOSER than production certifies a request that cannot be made,
which is the opposite of what a test double is for. Both surfaces now share one implementation
(`CollectBody`); the only difference left is the backend's wire name for the reference field, which
is a parameter rather than a second validator.

Two further gaps closed on the same surface: `simulate.collect()` had no failure envelope, so every
post-dispatch failure arrived with `idempotencyKey = null` (it now attaches the effective key on
every path, dispatch and field checks alike); and `SimulatedPayment` did not report the effective
key at all, so a caller using the generated-key opt-out could not retry with the key that was
actually sent. The simulator's own server-controlled strings — `paymentId`, `checkoutRequestId` and
every outcome `id`/`label`/`status` — are now refused when credential-shaped, exactly as the
production ack and status read have been since 0.7.0.

### MEDIUM — the non-vacuity harness had three dead anchors

`R5-wh-decoded`, `R5-wh-decoded-malformed` and `R5-zero-launder` named source text that no longer
existed, so all three reported `BROKEN-ANCHOR` instead of a verdict. The harness caught this
correctly and refused to pass — the guard was working — but three protections had no live
measurement behind them. All three anchors are repaired against current source, and eleven new
cases cover this round's fixes.

The harness's own safeguards were re-verified and are intact: a measurement still requires exit
code ZERO and zero skipped tests on both runs, every `nv-` tag in the test tree is checked against
the case list before any case runs, and a non-unique or non-matching anchor is a hard failure.

### LOW — the outbound JSON writer had no bounds at all

The reader is bounded because a response is attacker-controlled. The writer serialises the SDK's
own request bodies — except for `metadata`, which is arbitrary caller data, very often built from
the caller's own users' input. A deeply nested map overflowed the stack (a `StackOverflowError`,
which is an `Error`, so it escaped every catch on the money path); a self-referencing map recursed
forever; a huge one materialised the whole document before a byte was dispatched. All three are
DoS against the SDK's own caller, and all three are now refused BEFORE dispatch as a typed
`PaylodInvalidRequestException` — the one moment at which refusing is free, because no charge yet
exists. Cycle detection is identity-based, so a DAG (the same object as two siblings) still
serialises.

### Cross-SDK checks carried over from the sibling reviews

Three defects found in the PHP and Python SDKs this round were checked here explicitly, and are
now covered by tests so they stay checked:

- **Escaped member names / duplicate keys.** PHP scanned raw JSON bytes for a literal
  `"resultCode"` key, which an escaped spelling walked past. This SDK has no such scanner: it parses
  the JSON itself and looks keys up on the DECODED map, and a repeated object name is already fatal
  rather than last-wins, so a body cannot mean different things to different readers.
- **Decompression bombs.** Python applied its size cap AFTER automatic decompression. The JDK's
  `HttpClient` neither requests nor decodes a content coding, and this SDK adds no
  `Accept-Encoding` header, so the bounded subscriber counts the same bytes that crossed the wire
  and there is no expansion step to outrun.
- **Credentials surviving in inner frames.** The credentialed request never crosses a public
  boundary, no foreign throwable is attached as a `cause`, and every message on the path is
  redacted — asserted now by walking the whole cause and suppressed chain of a post-acknowledgement
  refusal.

## [0.7.0] - 2026-07-18

A sixth independent review, conducted against the threat model in `SECURITY.md`, found no
criticals and the shortest defect list of the six rounds — a normal pre-release set. Every fix
carries a test that is verified non-vacuously. Signing is unchanged (the shared golden webhook
vector still matches byte-for-byte) and the Java interop suite still passes.

### The non-vacuity harness could report CAUGHT for work it had not verified — fixed FIRST

This is listed first because it invalidated the evidence for everything else. `scripts/non-vacuity.py`
scraped the `NVCOUNT` line and returned, discarding two signals it already had:

- **The gradle exit code.** Under `-PnvTag` the test task sets `ignoreFailures`, so a tagged run
  exits 0 even when its tests fail — failure travels through the counters, never the exit status.
  A non-zero exit therefore never means "a test failed"; it means the build did not finish. But
  `NVCOUNT` is printed from `doLast` and was happily scraped out of exactly such a partial run, so
  a crashed build could be accepted as a measurement — and if it crashed after some tests had
  failed, accepted as CAUGHT.
- **The skipped count**, captured by the regex as group 3 and then dropped. `ran` counts a skipped
  test, so a case whose guarding test was disabled or filtered still cleared the liveness gate and
  then "passed" the mutated run without ever executing. That is the vacuity the harness exists to
  detect, wearing a green badge.

A measurement is now trusted only with **exit code zero AND zero skipped tests**, on the clean run
and the mutated run alike; anything else is classified as broken rather than as a verdict. Both
runs are carried into the final table, so every number a verdict rests on is shown. The full sweep
was re-run after this fix and every prior verdict re-confirmed.

### A raw JSON `-0` was laundered into the canonical success code

`DarajaCatalog.isCanonicalSuccessCode` names `-0` as an impostor and rejects it, and 0.6.0 proved
that — for the *quoted* `"-0"`. A raw, unquoted `-0` never reached the check as `-0`: `"-0".toLong()`
is `0L`, so the JSON reader handed downstream an integral zero with the sign already gone, and the
exact-zero check correctly said yes to what it was given. The guard was right; a lower layer had
normalized the impostor into canonical form before the guard could see it. A signed
`{"resultCode":-0}` therefore manufactured SUCCESS evidence on both the status path and the webhook
path — the difference between INDETERMINATE and PAID.

- The reader now returns the `Double` `-0.0`, the one JVM value that represents negative zero
  distinctly, so classification refuses it exactly as it always refused a float zero. It is
  deliberately not rejected at parse time: `-0` is legal JSON and must round-trip inside an
  arbitrary `metadata` map. Rejection belongs at classification.
- The **writer** is fixed to match. `Json.write(-0.0)` emitted the token `0`, re-creating the same
  laundering on the way out — which is why a raw impostor could not survive a round-trip through a
  stub and the defect stayed invisible to any test built on one. The regression tests construct the
  wire body as text.

### A stalled response body could park the caller forever, hiding a live charge

`BodyHandlers.ofInputStream()` completes as soon as the headers arrive, and `HttpRequest.timeout`
stops applying at exactly that point — so every byte of the body was read outside the request timer
with no deadline of any kind. A server that sent headers and then stopped writing parked the calling
thread indefinitely, and the JDK's response stream swallows interrupts, so the thread could not
reliably be broken out of even deliberately. The request had already been dispatched, so a charge
may have been raised and the idempotency key needed to ask about it was held in the stuck frame.

- The body is consumed by a bounded `BodySubscriber` driven through `sendAsync`, whose future
  completes only once the body is fully read. One deadline now covers connect, headers **and** body,
  with an explicit `cancel` on expiry so the exchange is torn down rather than leaked.
- The size ceiling is still enforced *during* the read, so an oversized body is never materialised.
- Verified against a real loopback server that sends headers and then stalls.

### Webhook bodies are size-bounded before conversion, HMAC and parsing

A webhook body arrives from an unauthenticated sender — that is the premise of the signature check,
not an edge case. But the signature is computed over the body, so every byte was copied, HMAC'd and
parsed before any verdict existed. A sender supplying a well-formed but bogus signature could hand
over a body of any size and have the process allocate and hash all of it.

- A 1 MiB ceiling is enforced first, on the length alone. It holds on **every** overload: the
  `String` entry points no longer call `toByteArray` themselves but route through one guarded
  conversion that refuses before allocating.

### The simulator validated its own response fields permissively

- Malformed outcome entries were **silently dropped** and missing `id`/`label`/`status` defaulted to
  `""`, so a broken simulator response produced a shorter, quieter list and a green test.
- `webhookQueued` was read as `ack["webhookQueued"] != false`, reporting **true** for a missing
  field, a null, or a string — so "a webhook was queued" was asserted on the field's *absence*.
- Both are now required and exactly typed, refused in the same INDETERMINATE posture the shared
  payment validator uses.

### A rejected `baseUrl` leaked its credentials into log output

`assertSecureBaseUrl` interpolated the raw URL into every rejection. That message is thrown at
construction — before a redactor exists — and goes wherever startup failures are logged. So it
published userinfo (`https://svc:sup3rsecret@host/…`), `?token=` query strings and fragments: three
of the shapes this very function *rejects*, meaning the rejection was the thing that leaked them.
The URL is now rendered sanitized, keeping scheme, host, port and path so the error stays actionable.

## [0.6.0] - 2026-07-18

**Breaking.** A fifth independent review found eight money-correctness defects that survived the
0.5.0 architectural rework. Every one is fixed here, each with a test that is verified
non-vacuously — the protection is reverted in the source and the test is required to fail. Signing
is unchanged (the shared golden webhook vector still matches byte-for-byte) and the Java interop
suite still passes.

This release also adds `SECURITY.md`, an explicit threat model stating what the SDK defends against
and — more importantly — what it does not.

### The worst one: a detected credential compromise was RETRIED

The redirect and off-origin detections were raised as `PaylodConnectionException`. That is the one
exception type the client's retry loop catches and re-sends. So the sequence was: a transport
follows a cross-origin 302 and replays `Authorization: Bearer mp_…` to a host we never addressed;
the SDK detects it and raises "your key is compromised, rotate it now"; the retry loop catches that
as a network blip and **sends the request again**, up to `maxRetries` more times. The SDK's own
compromise detection was a trigger for replaying the compromised credential.

- **New `PaylodSecurityException`** — extends `PaylodException` directly, NOT
  `PaylodConnectionException`. The retry loop has no branch that catches it, so a security refusal
  propagates on the FIRST occurrence with no further dispatch. That is structural: retrying one
  would require someone to add a new `catch` for this exact type.
- Off-origin request URLs, off-origin responding URLs and followed redirects all raise it. It
  carries the idempotency key, because the request was dispatched and the money state is unknown.

### Semantics: the last exception to law L3 is gone

- **`FAILED` claim + in-flight evidence is now `INDETERMINATE`, not `IN_FLIGHT`.** That cell
  resolved by picking a winner between two signals that contradict each other, which is precisely
  what L3 forbids everywhere else in the table. What an integrator sees is unchanged — `Outcomes`
  renders INDETERMINATE as `PENDING`, `paid = false`, `retryable = false`, so `wait()` still polls
  and no caller is ever invited to charge again — but the SDK no longer claims to know the prompt
  is live when it does not.

### The lenient status parse is deleted, not merely unused

- **`PaymentStatus.fromWire` (unknown -> `PENDING`) is removed.** It was documented as "only safe
  after `parseWire` has vetted the body", and it was not used only there: the money path validated
  with the strict parse and then BUILT the record from the same map with the lenient one. Two
  readings of one body, and money used the permissive one.
- **`PaymentValidators.assertPaymentBody` now RETURNS a typed `Payment`.** Validating and
  constructing are one act, so the caller cannot reach a different conclusion from the bytes than
  the validator that approved them. `Simulator.outcome()` had the identical double-read and is
  fixed the same way.

### Webhooks

- **`data.decoded` is RECOMPUTED from the canonical catalog; the payload's claim is ignored.** A
  signed payload could advertise `retryable = true` for a result code that is not retryable, and a
  handler doing the documented `if (decoded.retryable) recharge()` charged again on the sender's
  say-so. A signature proves who sent the bytes, not that their claims about M-Pesa semantics are
  true. This also removes the raw-parser-exception path: `DarajaCategory.fromWire` throws on an
  unknown category, and `retryable` was an unchecked cast.
- **`data.amount` must be an exact, positive, whole, in-range value.** It was accepted as any
  `Number` and then converted lossily: `100.7` TRUNCATED to `100`, and `4294967396` WRAPPED to
  `100` via `Long.toInt()` — a four-billion-shilling event delivered as a hundred-shilling one.
- **The replay window is bounded ABOVE as well as below** (`MAX_TOLERANCE_SEC`, one hour). A window
  is disabled as effectively by making it enormous as by making it zero, and an enormous one has the
  advantage of looking like a valid positive number in a config file.
- **Clock injection is no longer a public parameter.** `nowSec` is gone from `Webhooks.verify` /
  `verifySignature` / `parseAndVerify` and from `Paylod.verifyWebhook` / `parseWebhook`; it lives
  behind an internal test seam. The anti-replay check is `abs(now - t) > tolerance`, so a
  caller-supplied clock can move the window anywhere.

### Bounded responses, bounded recursion

- **Response bodies are bounded before allocation.** Dispatch streams the body and refuses past
  1 MiB rather than accumulating it with `BodyHandlers.ofString()`. An `OutOfMemoryError` from a
  path that has already dispatched a charge is not a `PaylodException`, so it escaped every block
  that attaches the idempotency key — a live charge with no handle on it.
- **JSON nesting is bounded before recursion**, both at the transport (by a flat scan that cannot
  itself overflow) and inside the reader (because webhook bodies never pass through a transport).
- Both raise the new **`PaylodIndeterminateException`**, terminal and key-carrying.

### The charge handles survive every throwable

- **`if (e is Error) throw e` is now `if (e is VirtualMachineError)`.** Every `Error` was rethrown
  bare, with no idempotency key and no payment id, for a charge live on a handset. `AssertionError`
  is thrown by ordinary application code in a healthy JVM — an `onPoll` listener with an assertion
  in it is the routine case. Non-VM errors are wrapped like any other throwable; a genuine
  `VirtualMachineError` is still rethrown unwrapped, but the handles are attached as a **suppressed
  exception** so they appear in the stack trace instead of being lost.

### Bounds and overflow

- `timeoutMs` and `maxRetries` are validated at `Paylod` construction. `timeoutMs = 0` is not "no
  limit" — `Duration.ofMillis(0)` expires every request immediately.
- `WaitOptions.timeoutMs` is validated at construction. It used to be silently replaced by the
  120s default when non-positive, so a caller who asked for something else waited two minutes
  believing otherwise.
- Wait deadlines use saturating addition. `monotonicMillis` is `nanoTime`-derived with an arbitrary
  origin, so `startedAt + timeout` could wrap negative — making every poll "out of time", so
  `wait()` returned instantly and reported a timeout on a payment it never looked at.

## [0.5.0] - 2026-07-18

**Breaking.** Two architectural roots were closed rather than patched, mirroring the Node SDK's
0.7.0. Four rounds of per-finding fixes had not converged because the same two shapes kept
producing new findings: a credential that crossed a replaceable boundary, and a payment record
judged by a scatter of per-field checks with no stated rules. Signing is unchanged — the shared
golden webhook vector still matches byte-for-byte — and the Java interop suite still passes.

### ROOT 1 — the transport owns the credential

The fix is not "validate harder", it is making misuse unrepresentable.

- **The API key never leaves the SDK.** Credentialed dispatch now happens inside an SDK-owned
  `Transport` that builds its own URL and its own headers from a private field. Callers pass a
  method, a path and a body; they never see the key, never construct headers, never supply a URL or
  a redirect mode. Previously the bearer token was an ordinary entry in a public `Map` handed to
  arbitrary caller-supplied code on every request, and the SDK tried to police the RESULT — which
  is too late, because an injected transport can follow a cross-origin 302 itself and hand back an
  ordinary `200` long after the credential has been replayed.
- **A custom `HttpTransport` is a gated test seam.** It requires the new
  `allowCustomTransport = true` opt-in and is refused outright for `mp_live_` keys — the same
  posture `allowInsecureBaseUrl` already had — enforced BOTH in `Paylod`'s constructor and again
  inside `Transport`, so the guarantee does not rest on one call site. It also receives an
  `HttpRequestSpec` with **no credential in it at all**.
- **The origin is pinned per dispatch**, recomputed and compared on every call rather than once at
  construction.
- **Redirects are refused in three layered ways**: a 3xx status, a transport that reports having
  FOLLOWED a redirect, and a final responding URL that is off the pinned origin. The last two are
  detections rather than preventions — the key is already gone — so both tell the caller to
  **rotate the key**.
- **`PaylodOptions` no longer exposes `apiKey`, `webhookSecret` or `transport`.** They were public
  `@JvmField`s on an object that gets passed around, held by DI containers and printed by
  debuggers. They are now write-only, and `toString()` renders `[redacted]`.

### ROOT 2 — the semantic model

New `Semantics.kt` states the model and the four laws that every sibling SDK mirrors.

- A record makes ONE **CLAIM** (`status`) and carries **EVIDENCE** (`mpesaReceipt`, `resultCode`).
  Neither ever substitutes for the other.
- `evidenceFor()` → `NONE | SUCCESS | FAILURE | IN_FLIGHT | CONFLICT`; `judge()` resolves
  (claim, evidence) through **one total table with no `else` branch**, so adding a status or an
  evidence kind is a compile error rather than a silent fallthrough to a permissive default.
  Defaults are exactly where the old logic went wrong, so there are none.
- **L1 BINDING** — a status body whose `id` is not the id that was REQUESTED is rejected as
  indeterminate before anything else is read. Nothing previously compared them, so any mechanism
  returning a different payment's record (a mis-keyed cache, a proxy collapsing requests, a routing
  bug, a crafted response) produced a body the SDK validated happily and classified on its own
  merits — and if that other payment was paid, the caller shipped goods for an order nobody paid
  for.
- **L2 EVIDENCE** — `paid` requires a receipt OR result code 0. Success *without* a receipt stays
  legitimate; receipts attach asynchronously.
- **L3 CONSISTENCY** — a contradiction is INDETERMINATE, never a *retryable* failure.
- **L4 RECEIPT** — a receipt forces paid-or-indeterminate, never failed and never in flight.
- A collect acknowledgement now requires HTTP **202** with `status` the literal `"pending"`. A bare
  `200` is what a cache, a proxy, a captive portal or a rewritten route produces; it is not a
  dispatched charge.

#### Behaviour changes you may see

| record | 0.4.0 | 0.5.0 |
| --- | --- | --- |
| `pending` + `resultCode: 0` | **paid**, `receipt = null` | indeterminate (rendered `PENDING`) |
| `failed` + receipt + `1032` | `CANCELLED`, **`retryable = true`** | indeterminate, never retryable |
| `pending` + receipt | paid | indeterminate |
| bare `success`, no evidence | indeterminate error | indeterminate outcome (rendered `PENDING`) |

The second row is the one that mattered most: the SDK was telling a merchant it was safe to charge
again for a payment carrying an M-Pesa confirmation receipt.

### The simulator runs the same validators

`simulate.collect()` now **generates an idempotency key** when the caller omits one (production
always did, so a retried simulate-collect really could create a second payment), and runs the same
202 acknowledgement validator. `simulate.outcome()` previously validated **nothing** — it read the
ack straight into a `Payment` — so it could return `paid` with no evidence, or a body describing a
different payment. It now runs the shared payment validator, ID binding included, and settles under
a deterministic idempotency key.

### Boundary fixes

- **API error messages and bodies are deeply redacted.** Both were built from the raw response, so
  a server that echoes the request back — a validation error quoting headers, a debug envelope, a
  gateway rendering the request on a 502 — put the live bearer token into `PaylodApiException`
  `message` and its public `body` field, and from there into every log sink. Redaction is
  depth-capped rather than trusting the structure.
- **`collectAndWait()` attaches the charge handles to ANY throwable.** It caught only
  `PaylodException`, so a transport `IOException` or an `onPoll` listener blowing up escaped with no
  idempotency key and no payment id — for a charge live on a handset. Non-`Paylod` throwables are
  normalised into a `PaylodConnectionException` carrying both handles, with a redacted message and
  no attached cause.
- **The JSON reader rejects a duplicate object name.** Last-value-wins made a body mean different
  things to different parsers, and the fields at stake are exactly the ones money depends on — the
  bound id, the status claim, the receipt and the result code.
- **Webhook events are fully validated.** Verification stopped at "`type` is a String and `data` is
  a Map" and then built a typed event out of whatever else arrived, so `data.status`, `data.amount`
  and `data.mpesaReceipt` were typed lies. Now: full shape, `type`/`data.status` consistency, and
  evidence via the SAME `judge()` a status read uses — a `payment.success` with nothing proving
  settlement, and a `payment.failed` carrying a receipt, are both refused. `Webhooks.verifySignature`
  is new: the signature layer alone, which is what the cross-repo golden vector pins.

### Non-vacuity

`scripts/non-vacuity.py` reverts each protection in the source, requires the guarding test to
**FAIL**, and restores. **24/24 mutations caught.** Every selector is proven to match a non-zero
number of passing tests on clean source before its verdict is trusted — a Gradle tag that matches
nothing exits 0, which reads as "not caught" when in truth nothing ran.

### Migration

- Add `.allowCustomTransport(true)` wherever you inject an `HttpTransport` in a test; a live key
  can no longer use one at all.
- `JdkHttpTransport` is gone. It was the default and never needed to be named.
- `HttpRequestSpec.headers` no longer contains `authorization`.
- Read `PaylodOptions.apiKey` / `.webhookSecret` / `.transport`? You cannot any more — keep your own
  reference to the value you set.
- A `pending` or contradictory record now renders as `PENDING` rather than paid or retryable. If you
  branched on `outcome.retryable` to offer a retry button, that is the change you want.

## [0.4.0] - 2026-07-18

Third-pass hardening from a codex review of 0.3.0, led by a **false-`paid`** bug in status decoding.
Signing is unchanged — the shared golden webhook vector still passes byte-for-byte, and the Java
interop suite is unchanged in shape.

### Money-correctness

- **A status response can no longer report `paid = true` with no evidence.** `GET /status/:id`
  validated exactly one field, `id`. So `{"id":"pay_123","status":"success"}` was accepted, the
  status STRING alone was trusted, no result code meant the classifier never ran, and `Outcomes.of`
  returned `paid = true` with `receipt = null` and `code = null`. A caller doing the documented
  `if (outcome.paid) fulfil(order)` shipped goods against a response that evidenced nothing. The
  whole state-dependent schema is now validated before a `Payment` is constructed: `status` must be a
  known wire value; `mpesaReceipt`, `resultCode` and `resultDesc` must have the right shapes when
  present; and a terminal **success must carry a receipt or a result code**. Every malformed 2xx now
  raises an **indeterminate** `PaylodApiException` (they previously omitted `indeterminate = true`
  too, so even the one case that was caught did not read as a stop signal).

- **`collectAndWait()` no longer discards the acknowledged idempotency key.** The collect succeeded
  and returned a key; the subsequent `wait()` knows nothing about it, so every post-collect failure —
  timeout, transport blip, 5xx, malformed poll body, interrupt — surfaced with
  `idempotencyKey = null`. A caller following the documented "retry with the key from the exception"
  rule found none, minted a fresh one, and fired a **second STK prompt** at a customer whose first
  prompt was still live. Failures after the ack now carry both `idempotencyKey` and the new
  `PaylodException.paymentId`.

- **The whole collect acknowledgement is validated, not just `paymentId`.** A missing, blank or
  wrong-typed `checkoutRequestId` previously produced a silent `CollectAck` carrying empty strings
  that callers went on to use as real checkout references. `POST /collect` always answers `202` with
  a hardcoded `status: "pending"` — an idempotent replay returns the *stored original* ack, not the
  settled state — so the literal is now required too, and any malformed 2xx is an indeterminate,
  key-bearing exception.

- **Thread interrupts no longer escape raw from a sleep.** `RealTimeSource.sleep` let
  `InterruptedException` propagate from retry, `Retry-After` and polling waits. That cleared the
  interrupt flag on the way out AND, because it is not a `PaylodException`, slipped past the handlers
  that attach the effective `Idempotency-Key` — leaving a caller with a possibly-live charge and no
  key to retry it under. It now restores the flag and throws `PaylodInterruptedException`.

- **Deadlines are measured on a monotonic clock.** `wait()` budgets and every remaining-time
  calculation used `System.currentTimeMillis()`, so a backward wall-clock adjustment (NTP step, VM
  resume, manual change) landing mid-wait extended polling past the promised deadline — unboundedly,
  in the limit. `TimeSource` now exposes `monotonicMillis()` for all duration arithmetic; the wall
  clock is retained only for HTTP-date `Retry-After` parsing, which genuinely needs civil time.

### Security

- **The API key can no longer leak through a header error.** A key carrying an embedded control
  character reached the JDK's header validation *outside* the transport's `try`, and the
  `IllegalArgumentException` it raises embeds the entire `Bearer …` value. API-key syntax is now
  validated at construction (printable ASCII, with a message that names the offending code point and
  never echoes the key), and header assembly is guarded so any failure is re-reported with the header
  NAME only.

- **The raw header map no longer prints secrets.** `HttpRequestSpec.toString()` redacted, but
  `spec.headers` is a public field whose own `toString()` rendered `authorization` verbatim — which
  is what string templates, structured-log field dumps and debuggers actually call. Headers are now
  passed as a redacting map: lookups return the real value, printing never does.

- **The no-redirect guarantee is no longer bypassable.** It applied only to the default constructor.
  `JdkHttpTransport`'s client-taking constructor is now `private` (it was public JVM bytecode) behind
  an internal factory, every instance is rejected unless `followRedirects() == NEVER`, and — since
  the SDK cannot police an arbitrary injected `HttpTransport` — the client itself now refuses any
  3xx outright rather than treating it as something to chase.

### Correctness

- **The JSON reader is RFC 8259-strict.** It accepted unescaped control characters inside strings,
  leading zeros, and half-formed numbers (`-`, `1.`, `.5`, `1e+`), plus non-hex `\u` escapes. A
  response could therefore carry valid required fields and pass 2xx validation while the document as
  a whole was not JSON that any conforming parser would read. Malformed bodies are now rejected, which
  routes them into the indeterminate path.

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
- **BREAKING: idempotency keys must be printable ASCII (0x20-0x7E).** HTTP header values are ASCII on
  the wire (RFC 9110), but a printable NON-ASCII key (`"ordr-cafe-1"` with an accented e, a CJK or
  Cyrillic character) passed every other rule below: not blank, no control characters, nothing
  invisible, well inside the length bound. Such a key either fails to encode at the transport — an
  error nowhere near its real cause — or, on a laxer stack, is silently re-encoded, so two requests the
  caller intended to share ONE key arrive carrying different bytes. The server sees two distinct keys
  and the duplicate-charge guard is gone with no error raised anywhere. Keys are now validated as
  printable ASCII, with an error naming the offending character. **A caller passing a non-ASCII key
  will now get a `PaylodInvalidRequestException` where 0.2.0 dispatched the request** — which is the
  point: the failure moves to the line that caused it, before any charge can start. Found independently
  by the Python SDK review after the rest of this release landed.
- **Idempotency key charset tightened.** Rejection now covers the **full** Unicode control ranges
  (C0, C1, DEL) and Unicode-only whitespace / zero-width characters (NBSP, ZWSP, BOM, line separator,
  ideographic space) — a key like `"order-123<ZWSP>"` is visually identical to `"order-123"` in every
  log and dashboard while being a different key on the wire, which is precisely how a "protected"
  retry becomes a second charge. Length is bounded in **UTF-8 bytes** (255), not `String` chars, which
  is what a server actually counts. (With printable ASCII now enforced, one character is one byte, so
  the byte bound and the character bound coincide by construction rather than by accident.)

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
