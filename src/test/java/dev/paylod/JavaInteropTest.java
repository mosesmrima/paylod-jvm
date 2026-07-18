package dev.paylod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the SDK is ergonomic from plain Java (no Kotlin default args, no Kotlin stdlib on the call
 * site). Every construct a Java caller reaches for — builders, telescoping overloads, enums,
 * property getters, a lambda transport — is exercised here.
 */
class JavaInteropTest {

    private static final String KEY = "mp_test_abc123";

    private static final String ACK_JSON =
        "{\"paymentId\":\"pay_123\",\"status\":\"pending\",\"checkoutRequestId\":\"ws_CO_0001\"}";
    private static final String STATUS_SUCCESS_JSON =
        "{\"id\":\"pay_123\",\"status\":\"success\",\"mpesaReceipt\":\"SFF6XYZ123\",\"resultCode\":0,\"resultDesc\":\"ok\"}";
    private static final String SIM_SETTLED_JSON =
        "{\"paymentId\":\"pay_sim_1\",\"status\":\"success\",\"resultCode\":0,\"resultDesc\":\"ok\","
            + "\"mpesaReceipt\":\"SFF6XYZ123\",\"webhookQueued\":true}";

    /** A tiny URL-dispatched stub — HttpTransport is a functional interface, so this is one lambda. */
    private HttpTransport stub() {
        return request -> {
            Map<String, String> headers = Collections.emptyMap();
            String url = request.getUrl();
            if (url.contains("/simulate/outcome")) {
                return new HttpResponseSpec(200, headers, SIM_SETTLED_JSON);
            }
            if (url.contains("/status/")) {
                return new HttpResponseSpec(200, headers, STATUS_SUCCESS_JSON);
            }
            if (url.endsWith("/collect")) {
                return new HttpResponseSpec(202, headers, ACK_JSON);
            }
            throw new IllegalStateException("unexpected call to " + url);
        };
    }

    private Paylod client() {
        PaylodOptions options = PaylodOptions.builder()
            .transport(stub())
            .allowCustomTransport(true)
            .maxRetries(0)
            .build();
        return new Paylod(KEY, options);
    }

    @Test
    void collectWithTelescopingOverload() {
        Paylod paylod = client();
        // Java sees the @JvmOverloads-generated overloads — no params object required.
        CollectAck ack = paylod.collect("0712345678", 100, "order-42", "Coffee", "attempt-1");
        assertEquals("pay_123", ack.getPaymentId());
        assertEquals(PaymentStatus.PENDING, ack.getStatus());
        assertEquals("attempt-1", ack.getIdempotencyKey());
    }

    @Test
    void collectWithBuilder() {
        Paylod paylod = client();
        CollectParams params = CollectParams.builder("0712345678", 250)
            .accountReference("INV-9")
            .description("Order #9")
            .idempotencyKey("attempt-2")
            .build();
        CollectAck ack = paylod.collect(params);
        assertEquals("pay_123", ack.getPaymentId());
    }

    @Test
    void collectAndWaitReturnsARenderableOutcome() {
        Paylod paylod = client();
        // First status read is already terminal, so no polling/sleep happens.
        PaymentOutcome outcome = paylod.collectAndWait("0712345678", 100, null, null, "attempt-3");
        assertTrue(outcome.getPaid());
        assertEquals(OutcomeStatus.SUCCEEDED, outcome.getStatus());
        assertEquals("SFF6XYZ123", outcome.getReceipt());
        assertFalse(outcome.getRetryable());
    }

    @Test
    void decodeErrorOffline() {
        Paylod paylod = client();
        DecodedError decoded = paylod.decodeError(1032);
        assertEquals("1032", decoded.getCode());
        assertEquals(DarajaCategory.CUSTOMER, decoded.getCategory());
        assertTrue(decoded.getRetryable());
        assertEquals(
            "Payment cancelled — you can try again whenever you're ready.",
            decoded.getCustomerMessage());
    }

    @Test
    void verifyWebhookBoolean() {
        Paylod paylod = client();
        String secret = "whsec_test";
        // A COMPLETE event: the verifier now enforces the full schema, the type/status agreement,
        // and the evidence that a success actually settled. A bare `{paymentId}` is refused.
        String body = "{\"type\":\"payment.success\",\"created\":1700000000,"
            + "\"data\":{\"paymentId\":\"pay_9\",\"status\":\"success\",\"amount\":100,"
            + "\"mpesaReceipt\":\"SFF6XYZ123\",\"resultCode\":0}}";
        // Signed with the REAL current clock. There is deliberately no `nowSec` parameter on the
        // public API any more — clock injection is an internal test seam, and `internal` is not
        // callable from Java — so this fixture is made fresh rather than pinned. That is exactly the
        // posture a production Java caller is in, which is what an interop test should exercise.
        long signedAt = System.currentTimeMillis() / 1000;
        String header = Webhooks.sign(body, secret, signedAt);

        assertTrue(paylod.verifyWebhook(body, header, secret, 300L));
        assertFalse(paylod.verifyWebhook(body, header, "wrong_secret", 300L));
        // A disabled tolerance is refused from Java too — in BOTH directions.
        assertFalse(paylod.verifyWebhook(body, header, secret, 0L));
        assertFalse(paylod.verifyWebhook(body, header, secret, Webhooks.MAX_TOLERANCE_SEC + 1));

        WebhookEvent event = paylod.parseWebhook(body, header, secret, 300L);
        assertEquals("pay_9", event.getData().getPaymentId());
        assertEquals(WebhookEventType.PAYMENT_SUCCESS, event.getType());
    }

    @Test
    void simulatorFromJava() {
        Paylod paylod = client();
        SimulatedOutcome outcome = paylod.simulate.outcome("pay_sim_1", SimOutcomeId.APPROVE);
        assertEquals(OutcomeStatus.SUCCEEDED, outcome.getStatus());
        assertTrue(outcome.getPaid());
        assertTrue(outcome.webhookQueued);
    }

    @Test
    void enumConstantsAreVisible() {
        assertEquals(5, SimOutcomeId.ALL.size());
        assertEquals("x-webhook-signature", Webhooks.SIGNATURE_HEADER);
    }
}
