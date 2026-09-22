package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteAccount(val profile: ServerProfile, val user: UserInfo)
data class RemoteEndpoint(val id: String, val name: String, val fingerprint: String, val available: Boolean)
data class RemotePairing(val id: String, val host: RemoteEndpoint, val requestId: String, val transcript: JsonObject, val code: String? = null)
data class RemoteDisplay(val slot: Int, val width: Int, val height: Int)
data class RemoteViewState(
    val loading: Boolean = false,
    val enabled: Boolean = false,
    val authenticated: Boolean = false,
    val native: NativeRemoteCapability = NativeRemoteCapability(false, "RD_NATIVE_NOT_INSTALLED"),
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val pairing: RemotePairing? = null,
    val sessionId: String? = null,
    val phase: String = "idle",
    val error: String? = null,
    val fingerprint: String? = null,
    val inputEnabled: Boolean = false,
    val display: RemoteDisplay? = null,
    val textStatus: String? = null,
)

/** Application-scoped owner; Activity recreation never owns credentials or native lifetime. */
class RemoteController(
    context: Context,
    private val account: () -> RemoteAccount?,
    private val parentRequest: suspend (String, String, JsonObject?) -> JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trust = RemoteTrustStore(context)
    private val _state = MutableStateFlow(RemoteViewState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var api: RemoteApi? = null
    private var identity: RemoteIdentity? = null
    private var serverInstance: String? = null
    private var trustedKeys: JsonObject? = null
    private var stunUrls: List<String> = emptyList()
    private var authorization: RemoteAuthorization? = null
    private var verifiedAuthorization: RemoteVerifiedAuthorization? = null
    private var accountKey: String? = null
    private var native: RemoteNativeSession? = null
    private var nativeLifetime = 0L
    private var signaling: RemoteSignaling? = null
    private var signalingGeneration = 0L
    private var operation: Job? = null
    private var gate = RemoteSessionGate(SystemClock::elapsedRealtime)
    private var sequence = 0L
    private var controlSequence = 0L
    private var motionChannelSequence = 0L
    private var motionSequence = 0L
    private var peerSequence = 0L
    private var offerJws: String? = null
    private var answerJws: String? = null
    private var pendingText: String? = null
    private var foreground = true

    fun refresh() = perform {
        val selected = requireNotNull(account()) { "RD_ACCOUNT_REQUIRED" }
        val key = "${selected.profile.apiBaseUrl}\n${selected.user.id}"
        if (accountKey != key) {
            clearAuthentication(); serverInstance = null
            api = RemoteApi(selected.profile.apiBaseUrl, parentRequest); accountKey = key
            _state.value = RemoteViewState(loading = true)
        }
        val activeApi = requireNotNull(api)
        val capability = activeApi.capabilities()["remote_desktop"]?.jsonObject
        val enabled = capability?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        stunUrls = if (enabled) RemoteConnectionPolicy.stunUrls(requireNotNull(capability)) else emptyList()
        val probe = RemoteNativeSession { _, _, _, _ -> }
        val nativeCapability = probe.capability
        probe.close()
        _state.value = _state.value.copy(enabled = enabled, native = nativeCapability)
        if (enabled) {
            val keys = activeApi.serverKeys()
            val trustUpdate = try { trust.pinServer(selected.profile.publicBaseUrl, selected.user.id, keys) }
            catch (error: Exception) { clearAuthentication(); throw error }
            if (trustUpdate.restoreChanged) clearAuthentication()
            trustedKeys = trustUpdate.anchor
            serverInstance = keys.string("server_instance_id")
            updateEndpoints(activeApi.accountEndpoints())
        } else clearAuthentication()
    }

    fun authenticate(password: String, mfa: String) = perform {
        check(_state.value.enabled) { "RD_DISABLED" }
        val selected = requireNotNull(account()) { "RD_ACCOUNT_REQUIRED" }
        val activeApi = requireNotNull(api)
        val instance = requireNotNull(serverInstance)
        activeApi.reauthenticate(password, mfa)
        val key = AndroidRemoteIdentity.open(instance, selected.user.id)
        val fingerprint = RemoteCrypto.thumbprint(key.publicJwk)
        val endpoints = activeApi.accountEndpoints().getValue("items") as JsonArray
        val existing = endpoints.map { it.jsonObject }.firstOrNull { it["jkt"] == JsonPrimitive(fingerprint) }
        if (existing == null) activeApi.enroll(key, instance, android.os.Build.MODEL)
        else {
            check(existing["status"] == JsonPrimitive("active")) { "RD_IDENTITY_REVOKED" }
            activeApi.restore(key, existing.string("id"), instance)
        }
        identity = key
        _state.value = _state.value.copy(authenticated = true, fingerprint = fingerprint)
        updateEndpoints(activeApi.endpoints())
        val expectedAccount = generation
        val expectedSocket = ++signalingGeneration
        val socket = RemoteSignaling(activeApi.baseUrl, requireNotNull(activeApi.endpointId), key,
            renewTicket = { activeApi.signalTicket("reauth") },
            onEvent = { event -> scope.launch {
                if (generation == expectedAccount && signalingGeneration == expectedSocket) signalEvent(event)
            } },
            onError = { error -> scope.launch {
                if (generation == expectedAccount && signalingGeneration == expectedSocket) {
                    stopLocal(); _state.value = _state.value.copy(authenticated = false, error = error)
                }
            } })
        signaling?.close(); signaling = socket
        socket.connect(activeApi.signalTicket())
    }

    fun pair(host: RemoteEndpoint, permissions: Set<String>) = perform {
        check(_state.value.authenticated && host.available) { "RD_HOST_UNAVAILABLE" }
        require("view" in permissions && P.permissions.containsAll(permissions))
        val requestId = UUID.randomUUID().toString()
        val nonce = RemoteCrypto.base64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val response = requireNotNull(api).pairing(host.id, permissions, requestId, nonce)
        val transcript = response.getValue("transcript").jsonObject
        val expected = mapOf(
            "domain" to JsonPrimitive("ht-rd-pairing-v1"), "server_instance_id" to JsonPrimitive(requireNotNull(serverInstance)),
            "pairing_id" to response.getValue("id"), "host_endpoint_id" to JsonPrimitive(host.id),
            "controller_endpoint_id" to JsonPrimitive(requireNotNull(api?.endpointId)),
            "host_jkt" to JsonPrimitive(host.fingerprint), "controller_jkt" to JsonPrimitive(requireNotNull(_state.value.fingerprint)),
            "nonce_host" to JsonNull, "nonce_controller" to JsonPrimitive(nonce),
            "scope" to JsonArray(permissions.sorted().map(::JsonPrimitive)), "mode" to JsonPrimitive("one_session"),
            "session_request_id" to JsonPrimitive(requestId), "expires_at" to response.getValue("expires_at"),
        )
        require(transcript == JsonObject(expected)) { "RD_PAIRING_CONTEXT" }
        _state.value = _state.value.copy(pairing = RemotePairing(response.string("id"), host, requestId, transcript))
    }
    fun refreshPairing() = perform {
        val pending = requireNotNull(_state.value.pairing)
        val response = requireNotNull(api).pairing(pending.id)
        check(response["state"] == JsonPrimitive("pending")) { "RD_PAIRING_EXPIRED" }
        val transcript = response.getValue("transcript").jsonObject
        require(transcript.filterKeys { it != "nonce_host" } == pending.transcript.filterKeys { it != "nonce_host" }) { "RD_PAIRING_CONTEXT" }
        require(Instant.parse(transcript.string("expires_at")).isAfter(Instant.now())) { "RD_PAIRING_EXPIRED" }
        if (transcript["nonce_host"] != JsonNull) {
            require(RemoteCrypto.decode(transcript.string("nonce_host"), 32).size == 32)
            val code = RemoteCrypto.sha256(RemoteJson.canonical(transcript).toByteArray()).take(16)
                .joinToString("") { "%02x".format(it) }.chunked(4).joinToString("-")
            _state.value = _state.value.copy(pairing = pending.copy(transcript = transcript, code = code))
        }
    }
    fun confirmPairing() = perform {
        val pending = requireNotNull(_state.value.pairing)
        check(pending.code != null && Instant.parse(pending.transcript.string("expires_at")).isAfter(Instant.now())) { "RD_PAIRING_EXPIRED" }
        val proof = RemoteCrypto.signJws(requireNotNull(identity), "ht-rd-pairing+jwt", pending.transcript)
        val pairing = requireNotNull(api).confirmPairing(pending.id, proof)
        check(pairing["state"] == JsonPrimitive("confirmed") && pairing["grant_id"] == JsonPrimitive(pending.id)) { "RD_PAIRING_CONTEXT" }
        _state.value = _state.value.copy(pairing = null)
        // Starting is explicitly blocked until the installed native artifact reports a real backend.
        check(_state.value.native.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        stopLocal()
        try {
            val nativeGeneration = ++nativeLifetime
            native = RemoteNativeSession { type, _, _, payload ->
                scope.launch {
                    if (nativeLifetime == nativeGeneration) try { nativeEvent(type, payload) }
                    catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_NATIVE_PROTOCOL_FAILED") }
                }
            }
            check(requireNotNull(native).capability.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
            val permissions = (pending.transcript.getValue("scope") as JsonArray).map { it.jsonPrimitive.content }.toSet()
            val created = requireNotNull(api).createSession(pending.host.id, pending.id, permissions, pending.requestId)
            val session = created["session_id"]?.jsonPrimitive?.content ?: created.string("id")
            val selected = requireNotNull(account())
            val keys = requireNotNull(trustedKeys)
            authorization = RemoteAuthorization(RemoteAuthorizationBinding(
                selected.profile.publicBaseUrl.trimEnd('/'), keys.string("server_instance_id"), keys.number("restore_epoch"),
                session, pending.requestId, created.number("connection_epoch", 0xffffffffL), selected.user.id,
                requireNotNull(api?.endpointId), pending.host.id, requireNotNull(_state.value.fingerprint), pending.host.fingerprint,
                permissions, pending.id,
            ), keys)
            _state.value = _state.value.copy(sessionId = session, phase = "pending_approval")
            // Re-read after binding to recover an authorization event that raced the HTTP response.
            val latest = requireNotNull(api).session(session)
            if (latest["state"] == JsonPrimitive("authorized")) authorizeSession(latest)
            val expectedGeneration = generation
            scope.launch {
                delay(60_000)
                if (generation == expectedGeneration && _state.value.sessionId == session && _state.value.phase != "active") {
                    closeSession(); _state.value = _state.value.copy(error = "RD_CONNECT_TIMEOUT")
                }
            }
        } catch (error: Exception) { stopLocal(); throw error }
    }
    fun cancelPairing() = perform {
        _state.value.pairing?.let { requireNotNull(api).rejectPairing(it.id) }
        _state.value = _state.value.copy(pairing = null)
    }
    private suspend fun nativeEvent(type: Int, bytes: ByteArray) {
        if (type == 1 || type == 3) {
            val reason = runCatching { RemoteJson.parse(bytes).string("error_code") }.getOrNull()
            stopLocal(); _state.value = _state.value.copy(error = reason?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) })
            return
        }
        if (type == 2) { gate.pause(); _state.value = _state.value.copy(phase = "paused", inputEnabled = false); return }
        val payload = RemoteJson.parse(bytes)
        val activeNative = requireNotNull(native)
        when (type) {
            4 -> {
                val signalType = payload.string("signal_type")
                val envelope = RemoteNativeHandshake.peerEnvelope(requireNotNull(identity), requireNotNull(verifiedAuthorization).ticket,
                    ++peerSequence, signalType, payload.getValue("payload").jsonObject)
                if (signalType == "peer.offer") {
                    require(offerJws == null) { "RD_STATE_CONFLICT" }
                    activeNative.signal(buildJsonObject { put("type", "local.signed_offer"); put("envelope", envelope) }.toString().toByteArray())
                    offerJws = envelope.string("payload_jws")
                }
                requireNotNull(signaling).send(envelope)
            }
            5 -> {
                val signature = RemoteNativeHandshake.proof(requireNotNull(identity), requireNotNull(_state.value.sessionId), gate.epoch,
                    requireNotNull(verifiedTicket).content, requireNotNull(offerJws), requireNotNull(answerJws), RemoteCrypto.decode(payload.string("transcript"), 256))
                activeNative.signal(buildJsonObject {
                    put("type", "local.proof"); put("request_id", payload.getValue("request_id")); put("signature", RemoteCrypto.base64(signature))
                }.toString().toByteArray())
            }
            6 -> {
                gate.authenticatedDirectPath(payload.number("epoch"), payload.string("protocol"), payload.string("local_candidate_type"), payload.string("remote_candidate_type"))
                if (_state.value.phase == "paused" && foreground) _state.value = _state.value.copy(phase = "active")
            }
            7 -> {
                val epoch = payload.number("epoch")
                val body = payload.getValue("payload").jsonObject
                when (payload.number("type").toInt()) {
                    P.DISPLAY_LAYOUT -> {
                        gate.displayLayout(epoch, body.number("layout_epoch"))
                        val display = (body.getValue("displays") as JsonArray).map { it.jsonObject }.single { it["id"] == body["active_display"] }
                        _state.value = _state.value.copy(inputEnabled = false, display = RemoteDisplay(
                            display.number("slot", 15).toInt(), display.number("width_px", 32768).toInt(), display.number("height_px", 32768).toInt()))
                    }
                    P.CONTROL_GRANTED -> {
                        val inputState = gate.controlGranted(epoch, body.string("request_id"), body.number("new_input_epoch"))
                        if (inputState == null) releaseControl()
                        else activeNative.submit(RemoteWire.frame(P.INPUT_STATE, gate.epoch, 0, ++controlSequence, inputState.toString().toByteArray()))
                    }
                    P.INPUT_SYNC_ACK -> {
                        val accepted = gate.acknowledgeInput(epoch, body.string("request_id"), body.number("input_epoch"), body.number("layout_epoch"))
                        _state.value = _state.value.copy(inputEnabled = accepted)
                        if (!accepted) releaseControl()
                    }
                    P.CONTROL_RELEASED, P.RELEASE_ALL -> { gate.releaseInput(); _state.value = _state.value.copy(inputEnabled = false) }
                    P.TEXT_ACK -> {
                        require(epoch == gate.epoch)
                        if (pendingText == body.string("submission_id")) {
                            pendingText = null
                            _state.value = _state.value.copy(textStatus = if (body.number("status", 1) == 0L) "confirmed" else "failed")
                        }
                    }
                    else -> error("RD_PROTOCOL_MISMATCH")
                }
            }
            8 -> {
                require(gate.live() && payload.number("epoch") == gate.epoch && payload.number("frames_presented") > 0) { "RD_STATE_CONFLICT" }
                _state.value = _state.value.copy(phase = if (foreground) "active" else "paused")
                val session = requireNotNull(_state.value.sessionId)
                val lifetime = nativeLifetime
                val activeApi = requireNotNull(api)
                for (attempt in 0..3) {
                    val snapshot = activeApi.session(session)
                    if (lifetime != nativeLifetime || !gate.live()) return
                    require(snapshot.number("connection_epoch") == gate.epoch) { "RD_STATE_CONFLICT" }
                    if (snapshot["state"] == JsonPrimitive("active")) break
                    require(snapshot["state"] in setOf(JsonPrimitive("authorized"), JsonPrimitive("connecting"))) { "RD_STATE_CONFLICT" }
                    try { activeApi.reportReady(session, gate.epoch, snapshot.number("state_version")); break }
                    catch (error: RemoteApiException) {
                        if (error.code != "RD_STATE_CONFLICT" || attempt == 3) throw error
                        delay(100)
                    }
                }
            }
            else -> error("RD_NATIVE_EVENT_INVALID")
        }
    }
    private fun signalEvent(event: JsonObject) {
        val type = event.string("type")
        if (type != "auth.expired" && event["session_id"] != JsonPrimitive(_state.value.sessionId)) return
        when (type) {
            "session.revoked", "auth.expired" -> { stopLocal(); _state.value = _state.value.copy(error = "RD_AUTH_REVOKED") }
            "session.authorized" -> {
                try { authorizeSession(event.getValue("payload").jsonObject) }
                catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
            }
            "session.state", "session.lease_updated" -> {
                try {
                    val payload = event.getValue("payload").jsonObject
                    if (payload["state"] in setOf(JsonPrimitive("closing"), JsonPrimitive("closed"), JsonPrimitive("failed"), JsonPrimitive("expired"))) stopLocal()
                    else if (verifiedAuthorization != null) renewLease(payload.string("lease_jws"))
                } catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
            }
            "peer.offer", "peer.answer", "peer.candidates", "peer.candidates_done" -> {
                // The shared native engine owns all ticket, peer identity and path proof checks.
                try {
                    native?.signal(event.toString().toByteArray())
                    if (type == "peer.answer") answerJws = event.string("payload_jws")
                }
                catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
            }
        }
    }
    private fun authorizeSession(snapshot: JsonObject) {
        val verifier = requireNotNull(authorization) { "RD_SESSION_CONTEXT" }
        if (verifiedAuthorization != null) {
            require(snapshot["ticket_jws"] == verifiedTicket) { "RD_SESSION_CONTEXT" }
            renewLease(snapshot.string("lease_jws")); return
        }
        val verified = verifier.authorize(snapshot)
        gate.authorized(verified.ticket.number("connection_epoch"),
            (verified.ticket.getValue("permissions") as JsonArray).map { it.jsonPrimitive.content }.toSet(),
            verified.remainingLeaseMs, verified.lease.number("lease_seq"))
        gate.foreground(foreground)
        verifiedAuthorization = verified; verifiedTicket = snapshot["ticket_jws"] as JsonPrimitive
        requireNotNull(native).start(verifier.nativeContext(snapshot, stunUrls = stunUrls))
        if (foreground) native?.resume() else native?.pause()
        _state.value = _state.value.copy(phase = "connecting")
        enforceLeaseDeadline(verified.remainingLeaseMs)
    }
    private var verifiedTicket: JsonPrimitive? = null
    private fun renewLease(compact: String) {
        val current = requireNotNull(verifiedAuthorization)
        val now = Instant.now()
        val lease = requireNotNull(authorization).lease(compact, current.ticket, now)
        if (current.grant["expires_at"] != JsonNull) require(Instant.parse(current.grant.string("expires_at")).epochSecond >= lease.number("exp")) { "RD_GRANT_EXPIRED" }
        if (lease == current.lease) return
        val remaining = RemoteAuthorization.remainingLeaseMs(lease, now)
        gate.renew(lease.number("connection_epoch"), lease.number("lease_seq"), remaining)
        verifiedAuthorization = current.copy(lease = lease, remainingLeaseMs = remaining)
        native?.signal(buildJsonObject {
            put("v", JsonPrimitive(1)); put("session_id", JsonPrimitive(_state.value.sessionId))
            put("connection_epoch", lease.getValue("connection_epoch"))
            put("type", JsonPrimitive("session.lease_updated"))
            put("payload", buildJsonObject {
                put("lease_jws", JsonPrimitive(compact)); put("server_keyset", requireNotNull(trustedKeys))
            })
        }.toString().toByteArray())
        enforceLeaseDeadline(remaining)
    }
    private fun enforceLeaseDeadline(remaining: Long) {
        val session = _state.value.sessionId
        val expected = generation
        scope.launch {
            delay(remaining + 1)
            if (generation == expected && _state.value.sessionId == session && !gate.live()) {
                stopLocal(); _state.value = _state.value.copy(error = "RD_LEASE_EXPIRED")
            }
        }
    }
    private fun updateEndpoints(value: JsonObject) {
        val items = (value.getValue("items") as JsonArray).map { it.jsonObject }
        _state.value = _state.value.copy(endpoints = items.filter { it["role"] != JsonPrimitive("controller") }.map {
            RemoteEndpoint(it.string("id"), it.string("name"), it.string("jkt"),
                it["status"] == JsonPrimitive("active") && it["local_enabled"]?.jsonPrimitive?.booleanOrNull == true)
        })
    }
    fun setSurface(surface: Surface?) {
        gate.surface(surface != null)
        if (surface == null) _state.value = _state.value.copy(inputEnabled = false)
        try { native?.surface(surface) } catch (_: Exception) { stopLocal() }
    }
    fun onForeground() {
        foreground = true; gate.foreground(true)
        try { native?.resume() }
        catch (_: RuntimeException) { stopLocal(); _state.value = _state.value.copy(error = "RD_SESSION_EXPIRED") }
    }
    fun onBackground() { foreground = false; gate.foreground(false); native?.pause() }
    fun onAccountChanged() {
        generation++; signalingGeneration++; scope.coroutineContext.cancelChildren(); operation = null
        stopLocal(); signaling?.close(); signaling = null; api?.clear(); api = null
        identity = null; serverInstance = null; trustedKeys = null; accountKey = null; stunUrls = emptyList()
        _state.value = RemoteViewState()
    }
    fun closeSession() {
        val id = _state.value.sessionId
        stopLocal()
        if (id != null) perform { api?.closeSession(id) }
    }
    private fun stopLocal() {
        nativeLifetime++; gate.close(); native?.close(); native = null; sequence = 0; controlSequence = 0; motionChannelSequence = 0; motionSequence = 0
        peerSequence = 0; offerJws = null; answerJws = null; pendingText = null
        gate = RemoteSessionGate(SystemClock::elapsedRealtime)
        authorization = null; verifiedAuthorization = null; verifiedTicket = null
        _state.value = _state.value.copy(sessionId = null, phase = "idle", inputEnabled = false, display = null, textStatus = null)
    }
    private fun clearAuthentication() {
        stopLocal(); signalingGeneration++; signaling?.close(); signaling = null
        api?.clear(); identity = null
        _state.value = _state.value.copy(authenticated = false, fingerprint = null, pairing = null)
    }
    fun canUse(permission: String): Boolean = foreground && gate.canUse(permission)
    fun requestControl() {
        val payload = gate.requestInput()
        requireNotNull(native).submit(RemoteWire.frame(P.CONTROL_REQUEST, gate.epoch, 0, ++controlSequence, payload.toString().toByteArray()))
    }
    fun releaseControl() {
        gate.releaseInput(); _state.value = _state.value.copy(inputEnabled = false)
        if (_state.value.sessionId != null && gate.live()) native?.submit(RemoteWire.frame(P.RELEASE_ALL, gate.epoch, 0, ++controlSequence, "{\"reason\":\"user_released\"}".toByteArray()))
    }
    fun submitText(text: String) {
        check(pendingText == null) { "RD_TEXT_PENDING" }
        val id = UUID.randomUUID()
        val submission = RemoteCrypto.base64(ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array())
        send("input.text", P.TEXT_COMMIT, RemoteWire.textPayload(text, id))
        pendingText = submission; _state.value = _state.value.copy(textStatus = "pending")
        val lifetime = nativeLifetime
        scope.launch {
            delay(5000)
            if (lifetime == nativeLifetime && pendingText == submission) {
                pendingText = null; _state.value = _state.value.copy(textStatus = "unconfirmed")
            }
        }
    }
    fun key(usage: Int, down: Boolean, repeat: Boolean = false) { send("input.keyboard", P.KEY, RemoteWire.keyPayload(usage, down, repeat)) }
    fun pointer(x: Float, y: Float, width: Int, height: Int, buttonDown: Boolean? = null): Boolean {
        if (!canUse("input.pointer")) return false
        val display = _state.value.display ?: return false
        val point = RemoteWire.point(x, y, width, height, display.width, display.height) ?: return false
        if (buttonDown == null) send("input.pointer", P.POINTER_ABS,
            RemoteWire.pointerPayload(gate.layoutEpoch, display.slot, point.first, point.second, ++motionSequence))
        else send("input.pointer", P.BUTTON,
            RemoteWire.buttonPayload(gate.layoutEpoch, display.slot, point.first, point.second, 1, buttonDown, ++motionSequence))
        return true
    }
    fun requestFeature(permission: String, enabled: Boolean) {
        check(canUse(permission)) { "RD_CONTROL_NOT_READY" }
        val body = buildJsonObject {
            put("permission", JsonPrimitive(permission))
            put("enabled", JsonPrimitive(enabled))
        }.toString().toByteArray()
        requireNotNull(native).submit(RemoteWire.frame(P.FEATURE_REQUEST, gate.epoch, 0, ++controlSequence, body))
        // A request never turns on the permission gate; that requires a verified peer acknowledgement.
    }
    private fun send(permission: String, type: Int, payload: ByteArray) {
        check(canUse(permission)) { "RD_CONTROL_NOT_READY" }
        val next = if (type == P.POINTER_ABS) ++motionChannelSequence else ++sequence
        requireNotNull(native).submit(RemoteWire.frame(type, gate.epoch, gate.inputEpoch, next, payload))
    }
    private fun perform(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        val expected = generation
        operation = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == expected) {
                    val code = (error as? RemoteApiException)?.code ?: error.message?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) } ?: "RD_OPERATION_FAILED"
                    _state.value = _state.value.copy(error = code)
                }
            } finally { if (generation == expected) _state.value = _state.value.copy(loading = false) }
        }
    }
}
