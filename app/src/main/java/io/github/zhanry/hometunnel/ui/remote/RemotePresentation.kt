package io.github.zhanry.hometunnel.ui.remote

import java.security.MessageDigest

enum class ConnectMode(val wire: String) {
    APPROVAL("local_approval"),
    ONE_TIME("one_time_password"),
    FIXED("fixed_password"),
    UNATTENDED("unattended"),
}

data class ModeSurface(val mode: ConnectMode, val enabled: Boolean, val blockedReason: String)

fun connectModes(
    nativeAvailable: Boolean,
    unattendedAdvertised: Boolean,
    offeredAccessModes: Set<String>? = null,
): List<ModeSurface> {
    val engine = if (nativeAvailable) "" else "native"
    fun offered(mode: ConnectMode): Boolean = when {
        offeredAccessModes != null -> mode.wire in offeredAccessModes
        mode == ConnectMode.UNATTENDED -> unattendedAdvertised
        else -> true
    }
    return ConnectMode.entries.map { mode ->
        val allowed = nativeAvailable && offered(mode)
        ModeSurface(
            mode,
            allowed,
            when {
                !nativeAvailable -> engine
                !offered(mode) && mode == ConnectMode.UNATTENDED -> "unattended"
                !offered(mode) -> "offered"
                else -> ""
            },
        )
    }
}

fun displayCaption(display: io.github.zhanry.hometunnel.remote.RemoteDisplay): String {
    val size = "${display.width} × ${display.height}"
    if (!display.hasMetrics) return size
    return "$size · ${display.dpiX}×${display.dpiY} DPI · ${display.scalePercent}% · ${display.originX},${display.originY}"
}

fun failureActionKey(action: String?): String = when (action) {
    null, "none" -> "none"
    "check_udp_path", "retry_session", "request_permission", "reauthenticate", "enable_host",
    "request_grant", "wait_for_host", "wait_for_approval", "enable_unattended", "reduce_permissions",
    "new_session", "reenroll", "switch_display" -> action
    else -> "unknown"
}

enum class RemoteStage {
    LOADING,
    UNAVAILABLE,
    READY,
    AUTHENTICATION,
    APPROVAL,
    DIRECT_UDP,
    CONNECTED,
    PAUSED,
    FAILED,
}

fun remoteStage(
    loading: Boolean,
    enabled: Boolean,
    authenticated: Boolean,
    phase: String,
    error: String?,
    pairing: Boolean,
    inSession: Boolean,
): RemoteStage = when {
    pairing || phase == "pending_approval" -> RemoteStage.APPROVAL
    inSession && phase == "active" -> RemoteStage.CONNECTED
    inSession && phase == "paused" -> RemoteStage.PAUSED
    inSession && phase in setOf("connecting", "reconnecting", "switching_display") -> RemoteStage.DIRECT_UDP
    inSession && error != null -> RemoteStage.FAILED
    loading && !inSession -> RemoteStage.LOADING
    !enabled && !loading -> RemoteStage.UNAVAILABLE
    !inSession && !error.isNullOrBlank() -> RemoteStage.FAILED
    !authenticated && enabled -> RemoteStage.AUTHENTICATION
    phase == "connecting" -> RemoteStage.DIRECT_UDP
    else -> RemoteStage.READY
}

enum class SessionControl { DISPLAY, POINTER, KEYBOARD, UNICODE, CLIPBOARD, FILES, SYSTEM_AUDIO, MICROPHONE }

/** Controls the packaged session has both granted and marked usable. Display metadata is separate. */
fun visibleSessionControls(authorized: Set<String>, hasDisplay: Boolean): Set<SessionControl> = buildSet {
    if (hasDisplay) add(SessionControl.DISPLAY)
    if ("input.pointer" in authorized) add(SessionControl.POINTER)
    if ("input.keyboard" in authorized) add(SessionControl.KEYBOARD)
    if ("input.text" in authorized) add(SessionControl.UNICODE)
    if ("clipboard.read" in authorized || "clipboard.write" in authorized) add(SessionControl.CLIPBOARD)
    if ("files.send" in authorized || "files.receive" in authorized) add(SessionControl.FILES)
    if ("audio.system" in authorized) add(SessionControl.SYSTEM_AUDIO)
    if ("audio.microphone" in authorized) add(SessionControl.MICROPHONE)
}

fun nineDigitCode(value: String): String? = value.trim().takeIf { Regex("^[0-9]{9}$").matches(it) }

/** Keeps only host device codes. Passwords and other text are dropped. */
fun rememberRecent(existing: List<String>, code: String): List<String> {
    val kept = existing.mapNotNull(::nineDigitCode).distinct()
    val valid = nineDigitCode(code) ?: return kept.take(8)
    return (listOf(valid) + kept.filter { it != valid }).distinct().take(8)
}

fun recentPreferenceKey(accountKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(accountKey.toByteArray(Charsets.UTF_8))
    return "recent_codes_" + digest.take(8).joinToString("") { "%02x".format(it) }
}
