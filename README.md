# paylod for the JVM

The official **Kotlin/Java** client for the **paylod API** — M-Pesa collections without the Daraja
boilerplate. One dependency-free JAR, usable from any JVM app: Spring Boot, Ktor, Android backends,
plain `main()`.

**No backend to run. No per-transaction fees. No custody of your money.** paylod hosts the Daraja
callback, refreshes the OAuth token, decodes the result code, and POSTs you a signed webhook when the
money lands — but the money moves through **your** Safaricom shortcode, on **your** Daraja
credentials. paylod never touches the funds. You bring your own Daraja creds; paylod does the plumbing.

> **Free early access.** paylod is free while in early access — mint a key and build.

---

## Install

Coordinates: **`dev.paylod:paylod`**. Requires **JVM 17+**. Zero runtime dependencies.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.paylod:paylod:0.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.paylod:paylod:0.1.0'
}
```

### Maven

```xml
<dependency>
  <groupId>dev.paylod</groupId>
  <artifactId>paylod</artifactId>
  <version>0.1.0</version>
</dependency>
```

> **Server-side only — this is not client-safe.** Your `PAYLOD_API_KEY` can move money. Never ship it
> in an Android/iOS app or any client bundle. Call this SDK from a server or a backend service.

---

## Quickstart

One call rings the phone, waits for the PIN, and hands you something you can **render**. There is no
result-code table in your app.

### Kotlin

```kotlin
import dev.paylod.Paylod

val paylod = Paylod(System.getenv("PAYLOD_API_KEY"))

// One key per payment ATTEMPT — a double-clicked Pay button cannot then charge twice.
val outcome = paylod.collectAndWait(
    phone = "0712345678",
    amount = 100,                 // whole KES
    idempotencyKey = attempt.id,
)

if (outcome.paid) fulfil(outcome.receipt)   // money moved
else              toast(outcome.message)    // already decoded, already human
```

### Java

```java
import dev.paylod.Paylod;
import dev.paylod.PaymentOutcome;

Paylod paylod = new Paylod(System.getenv("PAYLOD_API_KEY"));

// (phone, amount, accountReference, description, idempotencyKey) — overloads for the common case.
PaymentOutcome outcome =
    paylod.collectAndWait("0712345678", 100, null, null, attempt.id());

if (outcome.getPaid()) {
    fulfil(outcome.getReceipt());   // money moved
} else {
    toast(outcome.getMessage());    // already decoded, already human
}
```

That's the whole integration. `collectAndWait` sends the STK prompt, polls with a sane jittered
backoff (1s -> 5s), and returns a `PaymentOutcome`.

> **Pass an `idempotencyKey`, and mint one per payment attempt.** Duplicates of that attempt — a
> double-clicked button, a refreshed tab, a redelivered job — collapse into **one** prompt and **one**
> charge. Do **not** key on the order or the product: that replays an old payment. A retry after a
> wrong PIN is a new charge and needs a **new** key. Omit the key and every call is a new charge (and
> the SDK warns once).

---

## The outcome: one renderable shape

```kotlin
data class PaymentOutcome(
    val status: OutcomeStatus,   // SUCCEEDED | PENDING | CANCELLED | FAILED
    val message: String,         // customer-facing, already decoded. RENDER THIS.
    val retryable: Boolean,      // SAFE TO CHARGE AGAIN. Gate your retry button on this.
    val paid: Boolean,           // the one branch a backend needs: if (paid) fulfil()
    val receipt: String?,        // M-Pesa confirmation code; non-null exactly when paid
    val code: String?,           // developer detail — never needed to render the happy path
    val detail: DecodedError?,
    val payment: Payment,
)
```

Two invariants worth internalising:

1. **`retryable` means SAFE TO CHARGE AGAIN** — not "the user may press a button". A `PENDING`
   payment is never retryable: codes `4999` / `500.001.1001` mean the prompt is live and the customer
   hasn't typed their PIN yet. Retrying pushes a second prompt and can double-charge them.
2. **A wrong PIN is an answer, not an exception.** Cancellations, wrong PINs and low balances come
   back as data (`status = FAILED`, with a `message`), not as thrown errors. Only genuinely
   exceptional things throw — see below.

### What throws

| Throws | When |
|---|---|
| `PaylodInvalidRequestException` | Bad amount/phone. A bug in your code. |
| `PaylodConfigException` | No API key. A bug in your deploy. |
| `PaylodApiException` | Non-2xx from paylod (`status`, `isAuthError`, `isRateLimited`, `isIdempotencyConflict`, and the three 409 discriminators). |
| `PaylodConnectionException` | The network failed after retries. |
| `PaylodTimeoutException` | Still `PENDING` at the deadline — deliberately **not** a failed payment. Leave the order pending; the webhook settles it. |

---

## Test your checkout without a phone

Your failure paths are where payment bugs live. `paylod.simulate` removes the handset — and nothing
else: a real sandbox payment row, real Daraja result codes, a real signed webhook. Sandbox
(`mp_test_`) keys only, enforced locally.

```kotlin
val paylod = Paylod(System.getenv("PAYLOD_TEST_KEY"))   // mp_test_...

val outcome = paylod.simulate.pay(SimOutcomeId.WRONG_PIN)
outcome.status     // OutcomeStatus.FAILED
outcome.message    // "That M-Pesa PIN was incorrect. Please try again and enter the right PIN."
outcome.retryable  // true — no money moved, so a fresh charge is safe
```

| outcome | status | Result code |
| --- | --- | --- |
| `APPROVE` | `SUCCEEDED` | `0` |
| `WRONG_PIN` | `FAILED` | `2001` |
| `INSUFFICIENT_FUNDS` | `FAILED` | `1` |
| `USER_CANCELLED` | `CANCELLED` | `1032` |
| `TIMEOUT` | `FAILED` | `1037` |

To test *your* `collect()` path unchanged, build the client with `simulate = true`:

```kotlin
val paylod = Paylod("mp_test_...", PaylodOptions.of(simulate = true))
val ack = paylod.collect("0712345678", 250, idempotencyKey = "attempt-1")  // your code, verbatim
paylod.simulate.outcome(ack.paymentId, SimOutcomeId.USER_CANCELLED)
```

---

## Webhooks

paylod POSTs a signed JSON body to your endpoint when a payment settles:

```
POST /your/webhook
x-webhook-signature: t=1700000000,v1=<hex hmac-sha256>
```

The signature is `HMAC-SHA256(secret, "${t}.${rawBody}")`. Verify it against the **raw** bytes — a
re-serialised body will not match.

### Kotlin

```kotlin
val paylod = Paylod(System.getenv("PAYLOD_API_KEY"),
    PaylodOptions.of(webhookSecret = System.getenv("PAYLOD_WEBHOOK_SECRET")))

// Boolean convenience:
if (!paylod.verifyWebhook(rawBody, request.getHeader("x-webhook-signature"))) {
    return respond(400)
}

// …or get the typed event (throws PaylodSignatureVerificationException on any failure):
val event = paylod.parseWebhook(rawBody, request.getHeader("x-webhook-signature"))
if (event.type == WebhookEventType.PAYMENT_SUCCESS) fulfil(event.data.paymentId)
```

### Java

```java
Paylod paylod = new Paylod(System.getenv("PAYLOD_API_KEY"),
    PaylodOptions.builder()
        .webhookSecret(System.getenv("PAYLOD_WEBHOOK_SECRET"))
        .build());

String sig = request.getHeader("x-webhook-signature");
if (!paylod.verifyWebhook(rawBody, sig)) {
    return respond(400);
}
WebhookEvent event = paylod.parseWebhook(rawBody, sig);
if (event.getType() == WebhookEventType.PAYMENT_SUCCESS) {
    fulfil(event.getData().getPaymentId());
}
```

**Deliveries can repeat.** Key your fulfilment on `data.paymentId` and make it idempotent.

---

## Offline error decoding

```kotlin
paylod.decodeError(1032)
// DecodedError(code=1032, title="Payment cancelled by the customer",
//   category=CUSTOMER, retryable=true,
//   customerMessage="Payment cancelled — you can try again whenever you're ready.")
```

No network, no API key needed at call time. The strings are byte-identical to what paylod puts in
`event.data.decoded`, so your UI reads the same whether it came from a poll or a webhook. You rarely
need this — `check()`, `wait()` and `collectAndWait()` already hand back a decoded `PaymentOutcome`.

---

## Configuration

The base URL is baked in — identical for every customer, so there is nothing to configure. Escape
hatches (you probably won't need them) live on `PaylodOptions`:

| Option | Default |
|---|---|
| `apiKey` | `PAYLOD_API_KEY` env var |
| `baseUrl` | `PAYLOD_BASE_URL` env var, else `https://paylod.dev/functions/v1` |
| `webhookSecret` | `PAYLOD_WEBHOOK_SECRET` env var |
| `timeoutMs` | `30000` |
| `maxRetries` | `2` (transient failures only — network, 5xx, 429) |
| `transport` | JDK `HttpClient` (inject an `HttpTransport` for tests/proxies) |
| `simulate` | `false` (requires a `mp_test_` key) |

Kotlin uses named arguments (`PaylodOptions.of(timeoutMs = 10_000)`); Java uses the builder
(`PaylodOptions.builder().timeoutMs(10_000).build()`).

---

## Threading

The v1 API is synchronous — every call blocks the calling thread. Run collections off your request
thread (a worker pool, or a coroutine's `Dispatchers.IO`) if latency matters. Coroutine /
`CompletableFuture` variants can be layered on later without changing this surface.

---

## License

MIT — see [LICENSE](./LICENSE).
