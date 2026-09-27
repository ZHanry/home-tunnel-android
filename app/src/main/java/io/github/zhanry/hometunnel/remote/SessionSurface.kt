package io.github.zhanry.hometunnel.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val ACCESS_MODES = setOf("local_approval", "one_time_password", "fixed_password", "unattended")
internal val CONNECTION_PHASES = setOf(
    "waiting_for_approval", "direct_connect", "active", "recovering", "ending", "ended",
)
internal val FAILURE_ACTIONS = setOf(
    "none", "check_udp_path", "retry_session", "request_permission", "reauthenticate", "enable_host",
    "request_grant", "wait_for_host", "wait_for_approval", "enable_unattended", "reduce_permissions",
    "new_session", "reenroll", "switch_display",
)
internal val NATIVE_BACKENDS = setOf(
    "capture", "input_keyboard", "input_pointer", "input_text", "system_audio", "microphone",
    "clipboard", "files", "secure_desktop",
)

internal data class SessionSurface(
    val phase: String? = null,
    val failureCode: String? = null,
    val failureAction: String? = null,
    val failureRetryable: Boolean? = null,
    val accessMode: String? = null,
    val display: RemoteDisplay? = null,
)

internal fun RemoteViewState.withSessionSurface(surface: SessionSurface): RemoteViewState = copy(
    connectionPhase = surface.phase ?: connectionPhase,
    failureCode = surface.failureCode ?: failureCode,
    failureAction = surface.failureAction ?: failureAction,
    accessMode = surface.accessMode ?: accessMode,
    display = when {
        surface.display == null -> display
        display == null -> surface.display
        surface.display.hasMetrics && !display.hasMetrics && surface.display.slot == display.slot -> surface.display
        else -> display
    },
)

internal fun readSessionSurface(snapshot: JsonObject): SessionSurface {
    val connection = snapshot["connection"]?.takeUnless { it is JsonNull }?.jsonObject
    val failure = snapshot["failure"]?.takeUnless { it is JsonNull }?.jsonObject
    val metrics = snapshot["display_metrics"]?.takeUnless { it is JsonNull }?.jsonObject
    return SessionSurface(
        phase = connection?.knownToken("phase", CONNECTION_PHASES),
        failureCode = failure?.text("code"),
        failureAction = failure?.knownToken("action", FAILURE_ACTIONS),
        failureRetryable = failure?.get("retryable")?.jsonPrimitive?.booleanOrNull,
        accessMode = snapshot.knownToken("access_mode", ACCESS_MODES),
        display = metrics?.let(::parseDisplay),
    )
}

/** Missing offered modes stay null so older servers keep the previous unattended flag. */
internal fun offeredAccessModes(endpoint: JsonObject): Set<String>? {
    val value = endpoint["offered_access_modes"] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonArray).orEmpty().mapNotNull { item ->
        item.jsonPrimitive.contentOrNull?.takeIf { it in ACCESS_MODES }
    }.toSet()
}

/** Missing native discovery is null and fails closed for file and audio authorization. */
internal fun nativeBackends(capabilities: JsonObject?): Map<String, String>? {
    val native = capabilities?.get("native")?.takeUnless { it is JsonNull }?.jsonObject ?: return null
    val backends = native["backends"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
    return NATIVE_BACKENDS.associateWith { name ->
        backends[name]?.jsonPrimitive?.contentOrNull ?: "unsupported"
    }
}

internal fun parseDisplay(display: JsonObject): RemoteDisplay {
    val width = bounded(display, "width_px", 1, 32768) ?: bounded(display, "width", 1, 32768)
        ?: error("RD_DISPLAY")
    val height = bounded(display, "height_px", 1, 32768) ?: bounded(display, "height", 1, 32768)
        ?: error("RD_DISPLAY")
    val slot = bounded(display, "slot", 0, 65535) ?: error("RD_DISPLAY")
    return RemoteDisplay(
        slot = slot,
        width = width,
        height = height,
        dpiX = bounded(display, "dpi_x", 1, 960),
        dpiY = bounded(display, "dpi_y", 1, 960),
        scalePercent = bounded(display, "scale_percent", 100, 500),
        originX = bounded(display, "origin_x", -100000, 100000),
        originY = bounded(display, "origin_y", -100000, 100000),
        id = display.text("id"),
        name = display.text("name"),
    )
}

private fun JsonObject.knownToken(name: String, allowed: Set<String>): String? =
    text(name)?.takeIf { it in allowed }

private fun JsonObject.text(name: String): String? =
    get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun bounded(display: JsonObject, name: String, minimum: Int, maximum: Int): Int? {
    val element = display[name] ?: return null
    if (element is JsonNull) return null
    val value = element.jsonPrimitive.intOrNull ?: return null
    return value.takeIf { it in minimum..maximum }
}
