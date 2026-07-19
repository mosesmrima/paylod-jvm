# paylod for the JVM

The official **Kotlin/Java** client for the **paylod API**. The client does M-Pesa collections without
the Daraja boilerplate. The JAR has **no third-party runtime dependencies**. The JAR needs only the
Kotlin standard library. You can use the JAR from any JVM application: Spring Boot, Ktor, an Android
backend, or a plain `main()`.

**You run no backend. You pay no per-transaction fee. paylod holds none of your money.** paylod hosts
the Daraja callback. paylod refreshes the access token. paylod decodes the result code. paylod POSTs a
signed webhook to your server when a payment settles. The money moves through **your** Safaricom
shortcode, on **your** Daraja credentials. paylod never touches the funds. You supply your own Daraja
credentials.

> **Free early access.** paylod is free during early access. Mint a key and start.

---

## Install

The coordinates are **`dev.paylod:paylod`**. The SDK requires **JVM 17+**. The SDK has no third-party
runtime dependencies. The SDK needs only the Kotlin standard library.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.paylod:paylod:0.5.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.paylod:paylod:0.5.0'
}
```

### Maven

```xml
<dependency>
  <groupId>dev.paylod</groupId>
  <artifactId>paylod</artifactId>
  <version>0.5.0</version>
</dependency>
```

> **Call this SDK from a server only.** Your `PAYLOD_API_KEY` can move money. Never ship the key in a
> browser bundle, a mobile application, or any other client.

---

## Quickstart

One call sends the STK push, waits for the PIN, and returns a value that your application can
**render**. Your application needs no result code table.

> **Pass an idempotency key on every collect call.** Mint one key for each payment attempt. Duplicates
> of that attempt collapse into one STK push and one charge. A double-clicked Pay button, a refreshed
> tab, and a redelivered job are duplicates of one attempt. Do not use the order id or the product id
> as the key. A retry after a wrong PIN is a new attempt, and it needs a new key.
>
> The idempotency key is required. The SDK refuses a collect call without one, before the call leaves
> your process. The opt-out is `unsafeGeneratedIdempotencyKey = true`. Do not use this option in
> production. The SDK then mints a throwaway key, and it warns on every call. A throwaway key protects
> nothing, and the call can charge a customer twice.

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

That code is the complete integration. `collectAndWait` sends the STK push. `collectAndWait` then
polls the payment status. The poll interval increases from 1s to 5s, with jitter. `collectAndWait`
returns a `PaymentOutcome`.

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

Read these two invariants:

1. `retryable` means SAFE TO CHARGE AGAIN. It does not mean that the customer may press a button.
   `retryable = false` means that paylod cannot prove that no debit occurred. Result code `4999` and
   result code `500.001.1001` mean that the STK push is live on the handset. The customer did not enter the PIN yet. The payment is IN FLIGHT, not failed. A pending payment is never retryable.
   A second collect call sends a second STK push, and it can charge the customer twice.
2. A wrong PIN is an answer, not an exception. A cancellation, a wrong PIN, and a low balance come
   back as data, with `status` and a `message`. They do not raise an error. Only exceptional
   conditions raise an exception. The next table lists them.

### What throws

| Throws | When |
|---|---|
| `PaylodInvalidRequestException` | The amount or the phone number is not valid. Your code has a defect. |
| `PaylodConfigException` | The API key is absent. Your deployment has a defect. |
| `PaylodApiException` | paylod returned a non-2xx status. The exception carries `status`, `isAuthError`, `isRateLimited`, `isIdempotencyConflict`, and the three 409 discriminators. |
| `PaylodConnectionException` | The network failed after all retries. |
| `PaylodTimeoutException` | The payment is still `PENDING` at the deadline. A timeout is not a failed payment. The outcome is INDETERMINATE. The customer can still enter the PIN, and the payment can still succeed. Leave the order pending. The webhook settles the order. |

---

## Test your checkout without a phone

Most payment defects occur on the failure paths. The simulator removes the handset, and it changes
nothing else. You get a real sandbox payment record, real Daraja result codes, and a real signed
webhook. Every simulator call refuses an `mp_live_` key locally, before the call leaves your process.

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

To test your own `collect()` path without a change, build the client with `simulate = true`:

```kotlin
val paylod = Paylod("mp_test_...", PaylodOptions.of(simulate = true))
val ack = paylod.collect("0712345678", 250, idempotencyKey = "attempt-1")  // your code, verbatim
paylod.simulate.outcome(ack.paymentId, SimOutcomeId.USER_CANCELLED)
```

---

## Webhooks

paylod POSTs a signed JSON body to your endpoint when a payment settles.

```
POST /your/webhook
x-webhook-signature: t=1700000000,v1=<hex hmac-sha256>
```

The signature is `HMAC-SHA256(secret, "${t}.${rawBody}")`.

> **Verify the signature against the raw bytes.** A re-serialised body does not reproduce the same
> bytes, and the signature check then fails.
>
> paylod can deliver the same webhook more than once. Key your fulfilment on the signed
> `data.paymentId`, and make the fulfilment idempotent. Do not use the `x-webhook-id` header for this
> check. The header is unsigned, so an attacker can replay a body under a new header value.

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

---

## Offline error decoding

```kotlin
paylod.decodeError(1032)
// DecodedError(code=1032, title="Payment cancelled by the customer",
//   category=CUSTOMER, retryable=true,
//   customerMessage="Payment cancelled — you can try again whenever you're ready.")
```

`decodeError` needs no network and no API key. The strings are byte-identical to the strings in
`event.data.decoded`. Your interface therefore shows the same text for a poll and for a webhook. You
rarely need `decodeError`. `check()`, `wait()` and `collectAndWait()` already return a decoded
`PaymentOutcome`.

---

## Configuration

The SDK contains the base URL. The base URL is the same for every customer, so you configure nothing.
`PaylodOptions` holds the optional settings. Most applications need none of them.

| Option | Default |
|---|---|
| `apiKey` | The `PAYLOD_API_KEY` environment variable. The option is write-only. The options object has no getter, so no code can read the credential back. |
| `baseUrl` | The `PAYLOD_BASE_URL` environment variable, or else `https://paylod.dev/functions/v1`. The value must start with `https://`. The SDK refuses a plaintext origin, so the SDK never sends your key in the clear. |
| `webhookSecret` | The `PAYLOD_WEBHOOK_SECRET` environment variable. The option is write-only, the same as `apiKey`. |
| `timeoutMs` | `30000` |
| `maxRetries` | `2`. The SDK retries only transient failures: a network failure, a 5xx status, a 429 status, and the explicit in-progress `409`. |
| `transport` | The default is the SDK's own JDK `HttpClient` dispatch. A custom `HttpTransport` is a test-only seam. It requires `allowCustomTransport = true`. The SDK refuses it for an `mp_live_` key. It receives no credential. The SDK adds the `Authorization` header after that boundary, from a private field. |
| `allowCustomTransport` | `false`. A custom `HttpTransport` requires this explicit opt-in. |
| `simulate` | `false`. This option requires an `mp_test_` key. |
| `allowInsecureBaseUrl` | `false`. This option is a test-only escape hatch. It permits a loopback `http://` origin (`localhost` or `127.0.0.1`). The SDK refuses it with an `mp_live_` key. |

Kotlin uses named arguments, as in `PaylodOptions.of(timeoutMs = 10_000)`. Java uses the builder, as
in `PaylodOptions.builder().timeoutMs(10_000).build()`.

---

## Threading

The v1 API is synchronous. Every call blocks the calling thread. If latency is important, run each
collect call off your request thread. Use a worker pool or the `Dispatchers.IO` coroutine dispatcher.
paylod can add coroutine variants and `CompletableFuture` variants later. Those variants need no
change to this API surface.

---

## License

MIT — see [LICENSE](./LICENSE).
