package io.github.zhanry.hometunnel.remote

import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RemoteSessionContinuationTest {
    private val vectors = RemoteJson.parse(requireNotNull(javaClass.getResourceAsStream("/remote-authorization-vectors.json")).readBytes())
    private val expected = vectors.getValue("expected_binding").jsonObject
    private val binding = RemoteAuthorizationBinding(
        expected.string("iss"), expected.string("server_instance_id"), expected.number("restore_epoch"), expected.string("session_id"),
        expected.string("session_request_id"), expected.number("connection_epoch"), expected.string("owner_user_id"), expected.string("controller_endpoint_id"),
        expected.string("host_endpoint_id"), expected.string("controller_jkt"), expected.string("host_jkt"),
        (expected.getValue("permissions") as JsonArray).map { it.jsonPrimitive.content }.toSet(), expected.string("grant_id"),
    )
    private val original = RemoteSessionContinuation(binding, "display-1")
    private fun snapshot(context: RemoteSessionContinuation) = JsonObject(expected.filterKeys { it in setOf(
        "session_id", "session_request_id", "owner_user_id", "controller_endpoint_id", "host_endpoint_id", "permissions",
    ) } + mapOf("display_id" to JsonPrimitive(context.displayId), "connection_epoch" to JsonPrimitive(context.binding.connectionEpoch)))

    @Test fun `monitor switches keep exact identities scope grant and original request`() {
        val next = original.next("display_changed", "display-2")
        assertEquals(binding.copy(connectionEpoch = binding.connectionEpoch + 1), next.binding)
        assertEquals("display-2", next.displayId)
        assertEquals(0, next.networkReconnects)
        assertTrue(next.currentSnapshot(snapshot(next)))
        assertFalse(next.currentSnapshot(snapshot(original)))
        for ((field, value) in listOf(
            "session_request_id" to JsonPrimitive("c0000000-0000-4000-8000-000000000009"),
            "host_endpoint_id" to JsonPrimitive(binding.controllerEndpointId), "owner_user_id" to JsonPrimitive(binding.controllerEndpointId),
            "display_id" to JsonPrimitive("display-3"), "connection_epoch" to JsonPrimitive(next.binding.connectionEpoch + 1),
            "permissions" to JsonArray(listOf(JsonPrimitive("view"))),
        )) assertFails(field) { next.currentSnapshot(JsonObject(snapshot(next) + (field to value))) }
    }
    @Test fun `network recovery is bounded without charging display switches`() {
        var current = original
        repeat(3) { current = current.next("ice_failed") }
        assertFails { current.next("network_changed") }
        val switched = current.next("display_changed", "display-2")
        assertEquals(3, switched.networkReconnects)
        assertFails { switched.next("media_failed") }
        assertFails { original.next("display_changed") }
        assertFails { original.next("display_changed", "display-1") }
        assertFails { original.next("media_failed", "display-2") }
        assertFails { original.next("display_changed", " ") }
        assertFails { original.copy(binding = binding.copy(connectionEpoch = 0xffffffffL)).next("ice_failed") }
    }
    @Test fun `valid old epoch ticket cannot authorize the replacement native engine`() {
        val ticket = vectors.getValue("valid").jsonObject.getValue("ticket").jsonObject.getValue("jws_parts") as JsonArray
        assertFails {
            RemoteAuthorization(original.next("ice_failed").binding, vectors.getValue("keyset").jsonObject)
                .ticket(ticket.joinToString(".") { it.jsonPrimitive.content }, java.time.Instant.ofEpochSecond(vectors.number("reference_time_unix")))
        }
    }
    private fun display(id: String, slot: Int) = RemoteJson.parse("""{"id":"$id","name":"Monitor","slot":$slot,"width":1920,"height":1080}""".toByteArray())
    private fun layout(items: List<JsonObject>, active: String = "two") = JsonObject(mapOf(
        "displays" to JsonArray(items), "active_display" to JsonPrimitive(active),
    ))
    @Test fun `full layout validates sparse unsigned slots unique ids and active monitor`() {
        val (items, active) = parseDisplayLayout(layout(listOf(display("one", 4), display("two", 65535))))
        assertEquals(2, items.size); assertEquals(65535, active.slot)
        val wire = ByteBuffer.wrap(RemoteWire.pointerPayload(2, active.slot, 300, 400, 1))
        assertEquals(65535, wire.getShort(4).toInt() and 0xffff)
        assertFails { parseDisplayLayout(layout(listOf(display("two", 1), display("two", 2)))) }
        assertFails { parseDisplayLayout(layout(listOf(display("one", 2), display("two", 2)))) }
        assertFails { parseDisplayLayout(layout(listOf(display("one", 2)))) }
        assertFails { parseDisplayLayout(layout((1..17).map { display("id-$it", it) }, "id-1")) }
        assertFails { RemoteWire.pointerPayload(2, 65536, 300, 400, 1) }
    }
    @Test fun `new epoch cannot reuse input synchronization or an old rendered surface`() {
        val gate = RemoteSessionGate { 100L }
        gate.authorized(1, setOf("view", "input.pointer"), 60000, 1)
        gate.foreground(true); gate.authenticatedDirectPath(1, "udp", "host", "host")
        val oldSurface = gate.surface(true)
        assertTrue(gate.presentedFrame(1, oldSurface, 1))
        gate.displayLayout(1, 1)
        val request = gate.requestInput().string("request_id")
        gate.controlGranted(1, request, 1)
        assertTrue(gate.acknowledgeInput(1, request, 1, 1))
        gate.authorized(2, setOf("view", "input.pointer"), 60000, 1)
        gate.surface(true)
        assertFalse(gate.presentedFrame(1, oldSurface, 2))
        assertFalse(gate.canUse("input.pointer"))
        assertFalse(gate.acknowledgeInput(1, request, 1, 1))
    }
}
