package io.github.zhanry.hometunnel.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** A reconnect advances only the epoch; the locally pinned identities and grant cannot change. */
internal data class RemoteSessionContinuation(
    val binding: RemoteAuthorizationBinding,
    val displayId: String,
    val networkReconnects: Int = 0,
) {
    init {
        require(displayId.isNotBlank() && displayId.length <= 128) { "RD_DISPLAY" }
        require(networkReconnects in 0..3) { "RD_RECONNECT_LIMIT" }
    }

    fun next(reason: String, display: String? = null): RemoteSessionContinuation {
        require(reason in setOf("network_changed", "ice_failed", "media_failed", "display_changed")) { "RD_RECONNECT_REASON" }
        require(binding.connectionEpoch < 0xffffffffL) { "RD_RECONNECT_LIMIT" }
        if (reason == "display_changed") require(display != null && display != displayId) { "RD_DISPLAY" }
        else {
            require(display == null) { "RD_DISPLAY" }
            require(networkReconnects < 3) { "RD_RECONNECT_LIMIT" }
        }
        return copy(binding = binding.copy(connectionEpoch = binding.connectionEpoch + 1),
            displayId = display ?: displayId, networkReconnects = networkReconnects + if (display == null) 1 else 0)
    }

    /** Old snapshots may race the reconnect response; future epochs are never adopted from the server. */
    fun currentSnapshot(snapshot: JsonObject): Boolean {
        if (snapshot.number("connection_epoch", 0xffffffffL) < binding.connectionEpoch) return false
        val expected = mapOf(
            "session_id" to JsonPrimitive(binding.sessionId), "session_request_id" to JsonPrimitive(binding.sessionRequestId),
            "connection_epoch" to JsonPrimitive(binding.connectionEpoch), "owner_user_id" to JsonPrimitive(binding.ownerUserId),
            "host_endpoint_id" to JsonPrimitive(binding.hostEndpointId), "controller_endpoint_id" to JsonPrimitive(binding.controllerEndpointId),
            "display_id" to JsonPrimitive(displayId),
        )
        require(expected.all { (name, value) -> snapshot[name] == value }) { "RD_SESSION_CONTEXT" }
        val permissions = (snapshot.getValue("permissions") as JsonArray).map { it.jsonPrimitive.content }
        require(permissions.size == permissions.toSet().size && permissions.toSet() == binding.permissions) { "RD_SESSION_SCOPE_MISMATCH" }
        return true
    }
}

/** Full layout comes from the independently verified native peer, not endpoint discovery. */
internal fun parseDisplayLayout(body: JsonObject): Pair<List<RemoteDisplay>, RemoteDisplay> {
    val displays = (body.getValue("displays") as JsonArray).map { parseDisplay(it as JsonObject) }
    require(displays.size in 1..16 && displays.map { it.slot }.toSet().size == displays.size &&
        displays.all { it.id != null && it.id.isNotBlank() && it.id.length <= 128 } &&
        displays.map { it.id }.toSet().size == displays.size) { "RD_DISPLAY" }
    return displays to displays.single { it.id == body.string("active_display") }
}
