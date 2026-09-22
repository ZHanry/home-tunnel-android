package io.github.zhanry.hometunnel.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object RemoteConnectionPolicy {
    fun stunUrls(capability: JsonObject): List<String> {
        require(capability["enabled"] == JsonPrimitive(true) && capability["udp_only"] == JsonPrimitive(true) &&
            capability["allow_turn"] == JsonPrimitive(false) && capability["allow_ice_tcp"] == JsonPrimitive(false) &&
            capability["signal_path"] == JsonPrimitive("/api/v1/rd/signal") &&
            capability.getValue("protocol").jsonObject["major"] == JsonPrimitive(1)) { "RD_PROTOCOL_MISMATCH" }
        val urls = (capability.getValue("stun_urls") as JsonArray).map { it.jsonPrimitive.also { value -> require(value.isString) }.content }
        require(urls.size <= 4 && urls.all(::validStun)) { "RD_PATH_REJECTED" }
        return urls
    }
    fun validStun(value: String): Boolean {
        val match = Regex("stun:([a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\]):([0-9]{1,5})").matchEntire(value) ?: return false
        val host = match.groupValues[1]
        if (match.groupValues[2].toIntOrNull() !in 1..65535) return false
        if (host.startsWith('[')) return host.count { it == ':' } >= 2
        return host.length <= 253 && host.split('.').all { it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') }
    }
}
