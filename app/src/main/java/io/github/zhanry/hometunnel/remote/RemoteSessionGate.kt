package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P

/** Local fail-closed gate. Native independently verifies tickets, proofs and the UDP path. */
class RemoteSessionGate(private val elapsedMillis: () -> Long) {
    var epoch: Long = 0; private set
    var inputEpoch: Long = 0; private set
    var layoutEpoch: Long = 0; private set
    var permissions: Set<String> = emptySet(); private set
    private var leaseDeadline = 0L
    private var leaseSequence = 0L
    private var lastClock = 0L
    private var peerReady = false
    private var surfaceReady = false
    private var inputSynchronized = false
    private var foreground = false
    private var closed = true
    private val enabledFeatures = mutableSetOf<String>()

    fun authorized(epoch: Long, granted: Set<String>, remainingLeaseMs: Long, leaseSequence: Long) {
        require(epoch > this.epoch && epoch <= 0xffffffffL && granted.contains("view") && P.permissions.containsAll(granted))
        require(remainingLeaseMs in 1..900_000 && leaseSequence > 0)
        this.epoch = epoch; this.permissions = granted.toSet(); this.leaseSequence = leaseSequence
        lastClock = elapsedMillis(); leaseDeadline = Math.addExact(lastClock, remainingLeaseMs)
        peerReady = false; inputSynchronized = false; inputEpoch = 0; layoutEpoch = 0; closed = false
        enabledFeatures.clear()
    }
    fun renew(epoch: Long, sequence: Long, remainingMs: Long) {
        check(live() && epoch == this.epoch && sequence > leaseSequence)
        require(remainingMs in 1..900_000)
        leaseDeadline = Math.addExact(elapsedMillis(), remainingMs); leaseSequence = sequence
    }
    fun authenticatedDirectPath(epoch: Long, protocol: String, localType: String, remoteType: String) {
        check(live() && epoch == this.epoch)
        require(protocol == "udp" && localType in setOf("host", "srflx", "prflx") && remoteType in setOf("host", "srflx", "prflx")) { "RD_NO_DIRECT_PATH" }
        peerReady = true
    }
    fun acknowledgeInput(epoch: Long, inputEpoch: Long, layoutEpoch: Long) {
        check(live() && peerReady && foreground && surfaceReady && epoch == this.epoch)
        require(inputEpoch > this.inputEpoch && inputEpoch <= 0xffffffffL && layoutEpoch in 1..0xffffffffL)
        this.inputEpoch = inputEpoch; this.layoutEpoch = layoutEpoch; inputSynchronized = true
    }
    fun foreground(value: Boolean) {
        foreground = value
        if (!value) { inputSynchronized = false; enabledFeatures.clear() }
    }
    fun surface(value: Boolean) { surfaceReady = value; if (!value) inputSynchronized = false }
    fun feature(permission: String, enabled: Boolean) {
        require(permission in P.permissions)
        if (enabled) { check(canUse(permission)); enabledFeatures += permission } else enabledFeatures -= permission
    }
    fun featureEnabled(permission: String): Boolean = canUse(permission) && permission in enabledFeatures
    fun canUse(permission: String): Boolean = live() && foreground && peerReady && permission in permissions &&
        (!permission.startsWith("input.") || surfaceReady && inputSynchronized)
    fun live(): Boolean {
        val now = elapsedMillis()
        if (now < lastClock || now >= leaseDeadline) close()
        lastClock = now
        return !closed
    }
    fun pause() { peerReady = false; inputSynchronized = false; enabledFeatures.clear() }
    fun close() { closed = true; peerReady = false; inputSynchronized = false; enabledFeatures.clear(); permissions = emptySet() }
}
