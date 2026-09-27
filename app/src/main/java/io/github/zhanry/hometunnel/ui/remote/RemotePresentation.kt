package io.github.zhanry.hometunnel.ui.remote

import java.security.MessageDigest

enum class ConnectMode { APPROVAL, ONE_TIME, FIXED, UNATTENDED }

data class ModeSurface(val mode: ConnectMode, val enabled: Boolean, val blockedReason: String)

fun connectModes(nativeAvailable: Boolean, unattendedAdvertised: Boolean): List<ModeSurface> {
    val engine = if (nativeAvailable) "" else "native"
    return listOf(
        ModeSurface(ConnectMode.APPROVAL, nativeAvailable, engine),
        ModeSurface(ConnectMode.ONE_TIME, nativeAvailable, engine),
        ModeSurface(ConnectMode.FIXED, nativeAvailable, engine),
        ModeSurface(
            ConnectMode.UNATTENDED,
            nativeAvailable && unattendedAdvertised,
            when {
                !nativeAvailable -> "native"
                !unattendedAdvertised -> "unattended"
                else -> ""
            },
        ),
    )
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
    inSession && phase == "connecting" -> RemoteStage.DIRECT_UDP
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
