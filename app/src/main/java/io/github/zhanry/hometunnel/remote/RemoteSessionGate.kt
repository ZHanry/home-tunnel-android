package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private var surfaceAttached = false
    private var surfaceGeneration = 0L
    val firstFramePresented: Boolean get() = surfaceReady
    var connectionEstablished = false; private set
    private var inputSynchronized = false
    private var inputRequestId: String? = null
    private var inputRequestDeadline = 0L
    val inputPending: Boolean get() = inputRequestId != null && elapsedMillis() < inputRequestDeadline
    private var inputStateSent = false
    private var foreground = false
    private var closed = true
    private val enabledFeatures = mutableSetOf<String>()

    fun authorized(epoch: Long, granted: Set<String>, remainingLeaseMs: Long, leaseSequence: Long) {
        require(epoch > this.epoch && epoch <= 0xffffffffL && granted.contains("view") && P.permissions.containsAll(granted))
        require(remainingLeaseMs in 1..900_000 && leaseSequence > 0)
        this.epoch = epoch; this.permissions = granted.toSet(); this.leaseSequence = leaseSequence
        lastClock = elapsedMillis(); leaseDeadline = Math.addExact(lastClock, remainingLeaseMs)
        peerReady = false; surfaceReady = false; connectionEstablished = false; releaseInput(); inputEpoch = 0; layoutEpoch = 0; closed = false
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
    fun displayLayout(epoch: Long, layoutEpoch: Long) {
        check(live() && epoch == this.epoch)
        require(layoutEpoch > this.layoutEpoch && layoutEpoch <= 0xffffffffL)
        releaseInput(); this.layoutEpoch = layoutEpoch
    }
    /** Payload for CONTROL_REQUEST. A request alone never enables input. */
    fun requestInput(): JsonObject {
        check(live() && peerReady && foreground && surfaceReady && layoutEpoch > 0 && permissions.any { it.startsWith("input.") })
        val requestId = UUID.randomUUID().toString()
        releaseInput(); inputRequestId = requestId
        inputRequestDeadline = Math.addExact(elapsedMillis(), 5000)
        return buildJsonObject {
            put("request_id", requestId)
            put("requested_input_permissions", JsonArray(permissions.filter { it.startsWith("input.") }.sorted().map(::JsonPrimitive)))
        }
    }
    /** Correlated CONTROL_GRANTED returns the empty INPUT_STATE payload for resynchronization. */
    fun controlGranted(epoch: Long, requestId: String, newInputEpoch: Long): JsonObject? {
        if (!pendingInput(epoch, requestId) || inputStateSent || newInputEpoch <= inputEpoch || newInputEpoch > 0xffffffffL) {
            releaseInput(); return null
        }
        inputEpoch = newInputEpoch; inputStateSent = true
        return buildJsonObject {
            put("request_id", requestId); put("generation", inputEpoch)
            put("keys", JsonArray(emptyList())); put("buttons", 0); put("motion_sequence", 0)
        }
    }
    /** Delayed INPUT_SYNC_ACKs leave video alive and input disabled. */
    fun acknowledgeInput(epoch: Long, requestId: String, inputEpoch: Long, layoutEpoch: Long): Boolean {
        if (!pendingInput(epoch, requestId) || !inputStateSent || inputSynchronized || inputEpoch != this.inputEpoch || layoutEpoch != this.layoutEpoch) {
            releaseInput(); return false
        }
        inputSynchronized = true
        return true
    }
    private fun pendingInput(epoch: Long, requestId: String): Boolean = live() && peerReady && foreground && surfaceReady &&
        epoch == this.epoch && inputRequestId != null && inputRequestId == requestId && elapsedMillis() < inputRequestDeadline
    fun releaseInput() { inputSynchronized = false; inputStateSent = false; inputRequestId = null; inputRequestDeadline = 0 }
    fun foreground(value: Boolean) {
        foreground = value
        if (!value) { releaseInput(); enabledFeatures.clear() }
    }
    fun surface(value: Boolean): Long {
        surfaceGeneration = Math.addExact(surfaceGeneration, 1)
        surfaceAttached = value; surfaceReady = false; releaseInput()
        return surfaceGeneration
    }
    fun presentedFrame(epoch: Long, generation: Long, frames: Long): Boolean {
        if (!live() || epoch != this.epoch || generation != surfaceGeneration || !surfaceAttached || frames <= 0 || surfaceReady) return false
        surfaceReady = true; connectionEstablished = true
        return true
    }
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
    fun pause() { peerReady = false; releaseInput(); enabledFeatures.clear() }
    fun close() { closed = true; peerReady = false; releaseInput(); enabledFeatures.clear(); permissions = emptySet() }
}
