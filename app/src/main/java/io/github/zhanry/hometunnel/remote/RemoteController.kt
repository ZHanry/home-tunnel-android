package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.security.SecureRandom
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

data class RemoteAccount(val profile: ServerProfile, val user: UserInfo)
data class RemoteEndpoint(val id: String, val name: String, val fingerprint: String, val available: Boolean)
data class RemotePairing(val id: String, val host: RemoteEndpoint, val requestId: String, val transcript: JsonObject, val code: String? = null)
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
    private var accountKey: String? = null
    private var native: RemoteNativeSession? = null
    private var signaling: RemoteSignaling? = null
    private var signalingGeneration = 0L
    private var operation: Job? = null
    private val gate = RemoteSessionGate(SystemClock::elapsedRealtime)
    private var sequence = 0L
    private var controlSequence = 0L
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
        val probe = RemoteNativeSession { _, _, _, _ -> }
        val nativeCapability = probe.capability
        probe.close()
        _state.value = _state.value.copy(enabled = enabled, native = nativeCapability)
        if (enabled) {
            val keys = activeApi.serverKeys()
            val trustUpdate = try { trust.pinServer(selected.profile.publicBaseUrl, selected.user.id, keys) }
            catch (error: Exception) { clearAuthentication(); throw error }
            if (trustUpdate.restoreChanged) clearAuthentication()
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
        requireNotNull(api).confirmPairing(pending.id, proof)
        _state.value = _state.value.copy(pairing = null)
        // Starting is explicitly blocked until the installed native artifact reports a real backend.
        check(_state.value.native.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        native?.close()
        native = RemoteNativeSession { type, _, _, _ ->
            when (type) {
                1, 3 -> stopLocal()
                2 -> { gate.pause(); _state.value = _state.value.copy(phase = "paused") }
            }
        }
        check(requireNotNull(native).capability.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        val permissions = (pending.transcript.getValue("scope") as JsonArray).map { it.jsonPrimitive.content }.toSet()
        val created = try { requireNotNull(api).createSession(pending.host.id, pending.id, permissions, pending.requestId) }
        catch (error: Exception) { stopLocal(); throw error }
        val session = created["session_id"]?.jsonPrimitive?.content ?: created.string("id")
        _state.value = _state.value.copy(sessionId = session, phase = "pending_approval")
        val expectedGeneration = generation
        scope.launch {
            delay(60_000)
            if (generation == expectedGeneration && _state.value.sessionId == session && _state.value.phase != "active") {
                closeSession(); _state.value = _state.value.copy(error = "RD_CONNECT_TIMEOUT")
            }
        }
    }
    fun cancelPairing() = perform {
        _state.value.pairing?.let { requireNotNull(api).rejectPairing(it.id) }
        _state.value = _state.value.copy(pairing = null)
    }
    private fun signalEvent(event: JsonObject) {
        when (event.string("type")) {
            "session.revoked", "auth.expired" -> { stopLocal(); _state.value = _state.value.copy(error = "RD_AUTH_REVOKED") }
            "session.authorized" -> {
                try { native?.start(event.getValue("payload").jsonObject.string("ticket_jws")) }
                catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
            }
            "peer.offer", "peer.answer", "peer.candidates", "peer.candidates_done", "session.lease_updated" -> {
                // The shared native engine owns all ticket, peer identity and path proof checks.
                try { native?.signal(event.toString().toByteArray()) }
                catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
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
        try { native?.surface(surface) } catch (_: Exception) { stopLocal() }
    }
    fun onForeground() { foreground = true; gate.foreground(true) }
    fun onBackground() { foreground = false; gate.foreground(false); native?.pause() }
    fun onAccountChanged() {
        generation++; signalingGeneration++; scope.coroutineContext.cancelChildren(); operation = null
        stopLocal(); signaling?.close(); signaling = null; api?.clear(); api = null
        identity = null; serverInstance = null; accountKey = null
        _state.value = RemoteViewState()
    }
    fun closeSession() {
        val id = _state.value.sessionId
        stopLocal()
        if (id != null) perform { api?.closeSession(id) }
    }
    private fun stopLocal() {
        gate.close(); native?.close(); native = null; sequence = 0; controlSequence = 0
        _state.value = _state.value.copy(sessionId = null, phase = "idle")
    }
    private fun clearAuthentication() {
        stopLocal(); signalingGeneration++; signaling?.close(); signaling = null
        api?.clear(); identity = null
        _state.value = _state.value.copy(authenticated = false, fingerprint = null, pairing = null)
    }
    fun canUse(permission: String): Boolean = foreground && gate.canUse(permission)
    fun submitText(text: String) { send("input.text", P.TEXT_COMMIT, RemoteWire.textPayload(text)) }
    fun key(usage: Int, down: Boolean, repeat: Boolean = false) { send("input.keyboard", P.KEY, RemoteWire.keyPayload(usage, down, repeat)) }
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
        requireNotNull(native).submit(RemoteWire.frame(type, gate.epoch, gate.inputEpoch, ++sequence, payload))
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
