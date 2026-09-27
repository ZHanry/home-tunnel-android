package io.github.zhanry.hometunnel.ui.nav

import io.github.zhanry.hometunnel.model.ManagedDevice

internal enum class ShellTab { REMOTE, DEVICES, TUNNELS, ACCOUNT }

internal data class ShellLocation(val tab: Int, val remote: Boolean = false, val updates: Boolean = false)

internal fun ShellLocation.systemBack(): ShellLocation = when {
    remote -> copy(remote = false)
    updates -> copy(updates = false)
    tab == 4 -> copy(tab = ShellTab.ACCOUNT.ordinal)
    else -> this
}

internal fun canOpenAdmin(isAdmin: Boolean): Boolean = isAdmin

internal fun shellTab(index: Int): ShellTab = when (index) {
    1 -> ShellTab.DEVICES
    2 -> ShellTab.TUNNELS
    3, 4 -> ShellTab.ACCOUNT
    else -> ShellTab.REMOTE
}

/** Favorites first, then hosts that can accept a session, then name. */
internal fun remoteHomeOrder(devices: List<ManagedDevice>): List<ManagedDevice> =
    devices.sortedWith(
        compareByDescending<ManagedDevice> { it.favorite }
            .thenByDescending { it.online && it.status == "active" }
            .thenBy { it.name.lowercase() },
    )
