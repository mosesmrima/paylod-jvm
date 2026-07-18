package dev.paylod

import dev.paylod.internal.Json
import dev.paylod.internal.TimeSource
import java.util.Random

/** A recorded outbound call, so tests can assert what actually hit the wire. */
data class RecordedCall(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: Map<String, Any?>?,
    val timeoutMs: Long = 0,
)

/** One canned response the [StubTransport] will replay, in order. */
class Step(
    val status: Int = 200,
    val json: Any? = emptyMap<String, Any?>(),
    val headers: Map<String, String> = emptyMap(),
    val throwable: Throwable? = null,
)

/**
 * A stubbed [HttpTransport] that replays `steps` in order and records every call — the JVM analogue
 * of the Node SDK's `mockFetch`. No live network.
 */
class StubTransport(private val steps: List<Step>) : HttpTransport {
    val calls = mutableListOf<RecordedCall>()
    private var index = 0

    val count: Int get() = index

    @Suppress("UNCHECKED_CAST")
    override fun execute(request: HttpRequestSpec): HttpResponseSpec {
        val parsedBody = request.body?.let { Json.parse(it) as? Map<String, Any?> }
        calls.add(RecordedCall(request.url, request.method, request.headers, parsedBody, request.timeoutMs))

        val step = steps.getOrElse(minOf(index, steps.size - 1)) {
            throw IllegalStateException("StubTransport: no step configured")
        }
        index++
        step.throwable?.let { throw it }
        return HttpResponseSpec(step.status, step.headers, Json.write(step.json))
    }
}

/** A deterministic clock: `sleep` advances a virtual clock instead of blocking the suite. */
class FakeTimeSource(var now: Long = 0) : TimeSource {
    override fun nowMillis(): Long = now
    override fun sleep(ms: Long) {
        if (ms > 0) now += ms
    }
}

/** Build a client wired to a [StubTransport] and a [FakeTimeSource]. */
fun testClient(
    steps: List<Step>,
    apiKey: String = "mp_test_abc123",
    maxRetries: Int = 0,
    simulate: Boolean = false,
    baseUrl: String? = null,
    webhookSecret: String? = null,
    allowInsecureBaseUrl: Boolean = false,
): Pair<Paylod, StubTransport> {
    val transport = StubTransport(steps)
    val options = PaylodOptions.of(
        baseUrl = baseUrl,
        webhookSecret = webhookSecret,
        maxRetries = maxRetries,
        transport = transport,
        simulate = simulate,
        allowInsecureBaseUrl = allowInsecureBaseUrl,
    )
    val paylod = Paylod(apiKey, options, FakeTimeSource(), Random(1))
    return paylod to transport
}

/** The standard 202 collect ack body. */
val ACK: Map<String, Any?> = mapOf(
    "paymentId" to "pay_123",
    "status" to "pending",
    "checkoutRequestId" to "ws_CO_0001",
)

/** A status-read body with overridable fields. */
fun paymentJson(
    id: String = "pay_123",
    status: String = "pending",
    mpesaReceipt: String? = null,
    resultCode: Any? = null,
    resultDesc: String? = null,
): Map<String, Any?> = mapOf(
    "id" to id,
    "status" to status,
    "mpesaReceipt" to mpesaReceipt,
    "resultCode" to resultCode,
    "resultDesc" to resultDesc,
)
