# Security

This document states what the paylod JVM SDK defends against, what it does not, and why. It is
identical in substance across the paylod SDKs (Node, PHP, Python, JVM) — the guarantees are
properties of the protocol and the client design, not of one language.

A threat model that does not say what is OUT of scope is not a threat model. Most of the value of
this file is in the second half.

## Reporting a vulnerability

Email **security@paylod.dev**. Please do not open a public issue for a suspected vulnerability.
Include the SDK version, a description, and a reproduction if you have one. We will acknowledge
within three working days.

---

## In scope

These are threats the SDK is designed to resist. Each one has tests, and each of those tests is
verified non-vacuously — the protection is reverted in the source and the test is required to fail.

### 1. Network attackers and MITM

The API key is a bearer credential: whoever receives it can move money. So it is only ever sent
over TLS to a pinned origin.

- `baseUrl` is validated against an exact host allowlist, not a suffix match — `endsWith(".paylod.dev")`
  would accept a subdomain an attacker controls.
- Plaintext HTTP is refused. Loopback is permitted only behind an explicit test-only opt-in, and
  never with an `mp_live_` key.
- URL shapes used to smuggle a different effective origin past a naive check are refused: userinfo
  (`https://paylod.dev@attacker.example/`), missing hosts, raw IP literals, unexpected ports, query
  strings and fragments.
- The origin is re-pinned and re-compared on **every dispatch**, not once at construction.

### 2. A malicious or compromised API response

A valid TLS connection to the right host does not make the bytes that come back true. A compromised
backend, a bug upstream, a partially-written row, a caching proxy or a stubbed endpoint all produce
responses that are well-formed and wrong.

- Every money-bearing response is validated against a stated schema before it is acted on.
- `POST /collect` must answer **202**, not merely any 2xx — a bare `200` is the shape a cache, a
  captive portal or a rewritten route produces, and it is not a dispatched charge.
- Whether a payment is PAID is decided by one total table over (claim, evidence) in `Semantics.kt`,
  with no default branch. A claim is never evidence for itself: `status: "success"` with no receipt
  and no result code proves nothing and is never reported as paid.
- Contradictory records resolve to INDETERMINATE — never to a failure, and in particular never to a
  **retryable** failure, because we cannot prove money did not move.
- `data.decoded` on a webhook is **recomputed from the canonical offline catalog** and the payload's
  own claim is ignored. A signed payload cannot advertise `retryable = true` for a result code that
  is not retryable.
- Amounts must be exact, positive, whole and in range. Fractional values are refused rather than
  truncated; out-of-range values are refused rather than wrapped.
- Responses are bounded in size and in JSON nesting depth **before** allocation and recursion, so a
  hostile body produces a typed, key-carrying error instead of an `OutOfMemoryError` or a
  `StackOverflowError` from a path that has already dispatched a charge.
- Duplicate JSON object names are fatal. A body whose meaning depends on which parser reads it is
  not a body we can act on.

### 3. Cross-origin redirects attempting to capture the bearer token

- The SDK's HTTP client is built with `Redirect.NEVER`. A 3xx is terminal.
- A response that was *reached by following* a redirect, or whose final URL is off-origin, is
  refused and reported as a **credential compromise** — the caller is told to rotate the key.
- That refusal is a `PaylodSecurityException`, which is **terminal and never retried**. This is
  load-bearing: the retry loop catches `PaylodConnectionException`, so raising a compromise
  detection as one meant the SDK responded to "your token may have leaked" by sending the
  credentialed request again, up to `maxRetries` more times.
- Prevention, not just detection: the credential never crosses a replaceable boundary. A custom
  transport is a gated test seam, requires an explicit opt-in, is refused outright for `mp_live_`
  keys, and receives a request spec with **no credential in it at all**.

### 4. A response for a DIFFERENT payment (wrong-record settlement)

Law **L1**: a status body whose `id` is not the id that was requested is never evaluated. It is a
hard, indeterminate error, checked before anything else about the record's contents.

This is the highest-value single check in the SDK. A cache keyed on the wrong thing, a proxy
collapsing concurrent requests, an off-by-one in a routing or authorization layer, or a crafted
response produces a body where every field is perfectly valid — and if that other payment happened
to be settled and paid, the caller ships goods for an order nobody paid for. No amount of
field-level shape checking finds it. The request knows which payment it asked about; the answer has
to say the same thing.

### 5. Webhook forgery and replay

- HMAC-SHA256 over `${t}.${rawBody}`, compared in constant time.
- The signature header is parsed **strictly**: exactly one `t` and exactly one `v1`. Two headers
  combined into one comma-joined value cannot smuggle a forged pair past a last-value-wins parse.
- `t` is validated lexically as plain decimal digits — no sign, no exponent, no hex.
- Replay protection is bounded on **both** sides. A non-positive tolerance is refused (it would
  accept a captured webhook of any age); so is one above one hour, because a window can be disabled
  by making it enormous just as effectively as by making it zero, and an enormous one has the
  advantage of looking like a valid positive number in a config file.
- Clock injection is not part of the public API. The anti-replay check is
  `abs(now - t) > tolerance`, so a caller-supplied clock can move the window anywhere.
- A valid signature is not a licence to act on a body. `parseAndVerify` additionally enforces the
  event schema, type/status agreement, and the same evidence rules a status read uses.

### 6. Double-charge through idempotency mishandling

- Idempotency keys are validated as printable US-ASCII, bounded in bytes, with control characters
  and invisible Unicode refused — anything that could make two requests the caller intended to share
  one key arrive carrying different bytes.
- The request body is serialised **once**, before the retry loop, so a mutable metadata map cannot
  be mutated under a fixed key.
- A `409` is retried only when it is explicitly "same key still in progress". Every other 409 is a
  real, terminal answer.
- **Every** failure after a collect acknowledgement carries the effective idempotency key and the
  payment id — including ones the SDK did not raise. A custom transport throwing `IOException`, an
  `onPoll` listener throwing `AssertionError`, a `LinkageError` from a classpath problem: all are
  wrapped and labelled. A genuine `VirtualMachineError` is rethrown unwrapped (allocating on the way
  out of an OOM is the wrong move) but the handles are attached to it as a suppressed exception, so
  they appear in the stack trace rather than being lost.
- Indeterminate outcomes are never `retryable`. `retryable` means **safe to charge again**, not
  "the user could try again".

### 7. Accidental credential disclosure

Not an attacker exfiltrating secrets — an ordinary application logging something and the secret
riding along. This is the disclosure route that actually happens.

- Exception messages, the public `body` field on API errors, and anything derived from a response
  pass through a redactor seeded with this client's own secrets. A server that echoes the request
  back (a validation error quoting headers, a gateway rendering the request on a 502) cannot carry
  the live token into a log sink.
- The API key's syntax is validated **before** it can be interpolated into an `Authorization`
  header. The JDK's own header validator embeds the entire offending field value in the
  `IllegalArgumentException` it raises — for `authorization`, that is the complete bearer token.
- `PaylodOptions` has no public getter for the API key, the webhook secret or the transport. An
  options object is a place to put a secret, not a place to read one back from. Its `toString`
  renders `[redacted]`.
- `HttpRequestSpec.toString` and the header map it exposes both redact sensitive header values, so
  a string template, a structured-logging field dump or a debugger does not print them.
- Third-party exceptions are never attached as a `cause` on a wrapped error: a JDK, TLS or proxy
  exception can embed a request line, headers, or a URL with credentials in it.

---

## Out of scope

### An adversary who can already execute arbitrary code in the same JVM

**This is out of scope, and it is out of scope for every in-process client library.**

An attacker running code in your process has already won, by a margin no library can affect. They
can read process memory directly, use reflection to reach any field regardless of its declared
visibility, attach a `java.lang.instrument` agent and rewrite bytecode, install a `SecurityManager`
or replace one, dump the heap, or simply call your own code with their own arguments. The API key
exists in memory because it has to be sent on every request; there is no arrangement of a client
library that changes that.

No in-process client library can defend against this, and **none claim to**. This is equally true of
the Stripe, Twilio and AWS SDK clients, all of which hold credentials in ordinary process memory for
exactly the same reason.

The correct defenses against this threat are not in a client library. They are: not running
untrusted code in your payment process, dependency review and pinning, least-privilege API keys,
key rotation, and server-side controls that limit what a leaked key can do.

#### Specifically: Kotlin `internal` is not a security boundary

The SDK uses `internal` on constructors, accessors and test seams — the fixed-clock webhook seam,
the options accessors, the validators. It is worth being exact about what that does and does not
provide, because it is easy to imply protection the language does not offer.

**Kotlin `internal` compiles to a `public` JVM method with a mangled name.** It is enforced by the
Kotlin compiler at compile time within a module. It is *not* enforced by the JVM at runtime. Any
code in the same process — Java code, Kotlin code in another module, or anything using reflection —
can call an `internal` member. The name mangling is a linkage detail, not an access control.

So `internal` here is an **API-hygiene boundary**: it keeps a parameter or an accessor off the
surface an ordinary application reaches through the documented API, so it cannot be passed by
accident, wired in by a DI container, bound from a config file, or reached for during a
"just make this test pass" edit. That is a real and useful property, and it is the only one being
claimed. It is not a barrier against in-process code, and nothing in this SDK's design depends on it
being one.

The same applies to `private` fields and to the redaction in `toString`: they prevent *accidental*
disclosure through the paths a normal application actually takes. They do not prevent deliberate
extraction by code running in the same process, and they are not intended to.

### Host compromise

If the machine is compromised, the attacker reads the environment variables, the secret store, the
memory and the disk. The SDK's controls are irrelevant at that point. Defend the host.

### Malicious dependencies already on the classpath

A hostile library in the same application can do everything described under "arbitrary code in the
same JVM", and can additionally shadow classes, install agents at startup, or replace the HTTP
stack beneath the SDK.

This SDK reduces its own contribution to that surface: it has **zero third-party runtime
dependencies** beyond the Kotlin standard library, HTTP rides on the JDK's own
`java.net.http.HttpClient`, and JSON is handled by a small internal reader rather than a
serialization framework. That is a smaller attack surface, not an absent one, and it says nothing
about the rest of your classpath. Audit and pin your dependencies.

### Other explicit non-goals

- **Denial of service against your own application.** Bounds exist here to keep the money state
  knowable (a hostile response becomes a typed error rather than an OOM that loses an idempotency
  key), not to guarantee availability.
- **Side-channel and timing attacks beyond signature comparison.** Webhook signature comparison is
  constant-time. Nothing else claims to be.
- **The security of the paylod backend.** This document covers the client.
