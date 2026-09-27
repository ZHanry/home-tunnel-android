package io.github.zhanry.hometunnel.remote

import android.content.Context
import android.content.ClipboardManager
import android.os.SystemClock
import android.net.Uri
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RemoteAccount(val profile: ServerProfile, val user: UserInfo)
data class RemoteEndpoint(val id: String, val name: String, val fingerprint: String, val displayId: String?, val available: Boolean,
    val ownerUserId: String? = null, val assistInviteId: String? = null, val unattendedEnabled: Boolean = false,
    val offeredAccessModes: Set<String>? = null, val nativeBackends: Map<String, String>? = null)
internal fun hostOffersUnattended(host: RemoteEndpoint): Boolean =
    host.offeredAccessModes?.contains("unattended") ?: host.unattendedEnabled
internal fun hostOffersMode(host: RemoteEndpoint, mode: String): Boolean {
    val offered = host.offeredAccessModes ?: return mode != "unattended" || host.unattendedEnabled
    return mode in offered
}
internal fun eligibleForTrustedBinding(host: RemoteEndpoint, ownerUserId: String?, permissions: Set<String>): Boolean =
    host.available && hostOffersUnattended(host) && host.assistInviteId == null && host.ownerUserId == ownerUserId &&
        ownerUserId != null && "view" in permissions && P.permissions.containsAll(permissions)
internal fun matchingPersistentGrant(grants: JsonObject, host: RemoteEndpoint, ownerUserId: String,
    controllerEndpointId: String, permissions: Set<String>, now: Instant): String? {
    if (host.assistInviteId != null || host.ownerUserId != ownerUserId || "view" !in permissions) return null
    val items = grants["items"] as? JsonArray ?: return null
    return items.firstNotNullOfOrNull { item ->
        val grant = item as? JsonObject ?: return@firstNotNullOfOrNull null
        if (grant["mode"] != JsonPrimitive("persistent") || grant["status"] != JsonPrimitive("active") ||
            grant["host_endpoint_id"] != JsonPrimitive(host.id) || grant["controller_endpoint_id"] != JsonPrimitive(controllerEndpointId) ||
            (grant["permissions"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()?.containsAll(permissions) != true ||
            !runCatching { Instant.parse(grant.string("expires_at")).isAfter(now) }.getOrDefault(false)) return@firstNotNullOfOrNull null
        runCatching { UUID.fromString(grant.string("id")).toString() }.getOrNull()
    }
}
data class RemotePairing(val id: String, val host: RemoteEndpoint, val requestId: String, val transcript: JsonObject, val code: String? = null)
data class RemoteDisplay(
    val slot: Int,
    val width: Int,
    val height: Int,
    val dpiX: Int? = null,
    val dpiY: Int? = null,
    val scalePercent: Int? = null,
    val originX: Int? = null,
    val originY: Int? = null,
    val id: String? = null,
    val name: String? = null,
) {
    val hasMetrics: Boolean
        get() = dpiX != null && dpiY != null && scalePercent != null && originX != null && originY != null
}
data class RemoteViewState(
    val loading: Boolean = false,
    val enabled: Boolean = false,
    val authenticated: Boolean = false,
    val native: NativeRemoteCapability = NativeRemoteCapability(false, "RD_NATIVE_NOT_INSTALLED"),
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val assistTarget: RemoteEndpoint? = null,
    val pairing: RemotePairing? = null,
    val pairingId: String? = null,
    val pairingCode: String? = null,
    val sessionId: String? = null,
    val hostName: String? = null,
    val phase: String = "idle",
    val error: String? = null,
    val fingerprint: String? = null,
    val inputEnabled: Boolean = false,
    val display: RemoteDisplay? = null,
    val displays: List<RemoteDisplay> = emptyList(),
    val textStatus: String? = null,
    val clipboardEnabled: Boolean = false,
    val connectionPhase: String? = null,
    val failureCode: String? = null,
    val failureAction: String? = null,
    val accessMode: String? = null,
    val audioEnabled: Boolean = false,
    val filesReceiveEnabled: Boolean = false,
    val pendingFeatures: Set<String> = emptySet(),
    val fileTransfers: List<RemoteFileItem> = emptyList(),
    val filePreparing: Boolean = false,
)

/** Application-scoped owner; Activity recreation never owns credentials or native lifetime. */
class RemoteController(
    context: Context,
    private val account: () -> RemoteAccount?,
    private val parentRequest: suspend (String, String, JsonObject?) -> JsonObject,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val applicationContext = context.applicationContext
    private val trust = RemoteTrustStore(applicationContext)
    private val clipboard by lazy(LazyThreadSafetyMode.NONE) {
        applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { syncLocalClipboard() }
    private val _state = MutableStateFlow(RemoteViewState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var connectionGeneration = 0L
    private var api: RemoteApi? = null
    private var identity: RemoteIdentity? = null
    private var serverInstance: String? = null
    private var trustedKeys: JsonObject? = null
    private var stunUrls: List<String> = emptyList()
    private var authorization: RemoteAuthorization? = null
    private var verifiedAuthorization: RemoteVerifiedAuthorization? = null
    private var continuation: RemoteSessionContinuation? = null
    private var sessionHost: RemoteEndpoint? = null
    private var snapshotVersion = 0L
    private var reconnectJob: Job? = null
    private var attachedSurface: Surface? = null
    private var accountKey: String? = null
    private var native: RemoteNativeSession? = null
    private var nativeLifetime = 0L
    private var signaling: RemoteSignaling? = null
    private var signalingGeneration = 0L
    private var operation: Job? = null
    private var pairingPoll: Job? = null
    private var cleanupJob: Job? = null
    private var activePairingId: String? = null
    private var accountTransitioning = false
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
    private var autoInputRequested = false
    private var autoClipboardRequested = false
    private var clipboardSequence = 0L
    private var clipboardTransfer: RemoteClipboardTransfer? = null
    private var clipboardListening = false
    private var fileTransfers: RemoteNativeFiles? = null
    private val featureRequests = mutableMapOf<String, Long>()
    private var featureRequestSequence = 0L

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
            val endpoints = activeApi.accountEndpoints()
            updateEndpoints(endpoints)
            if (!_state.value.authenticated && !trustUpdate.restoreChanged) {
                val keyIdentity = AndroidRemoteIdentity.open(requireNotNull(serverInstance), selected.user.id)
                val fingerprint = RemoteCrypto.thumbprint(keyIdentity.publicJwk)
                val existing = (endpoints.getValue("items") as JsonArray).map { it.jsonObject }.firstOrNull { it["jkt"] == JsonPrimitive(fingerprint) }
                if (existing != null) activateController(activeApi, selected, keyIdentity, requireNotNull(serverInstance), existing)
            }
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
        activateController(activeApi, selected, key, instance, existing)
    }

    private suspend fun activateController(activeApi: RemoteApi, selected: RemoteAccount, key: RemoteIdentity, instance: String, existing: JsonObject?) {
        if (existing == null) activeApi.enroll(key, instance, android.os.Build.MODEL)
        else {
            check(existing["status"] == JsonPrimitive("active")) { "RD_IDENTITY_REVOKED" }
            activeApi.restore(key, existing.string("id"), instance)
        }
        identity = key
        _state.value = _state.value.copy(authenticated = true, fingerprint = RemoteCrypto.thumbprint(key.publicJwk))
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

    fun assist(deviceId: String, temporaryPassword: String, permissions: Set<String>) = perform {
        check(_state.value.authenticated && _state.value.native.available) { "RD_AUTH_REQUIRED" }
        val target = requireNotNull(api).redeemAssist(deviceId, temporaryPassword)
        connectAssistedTarget(target, permissions, "one_time_password")
    }
    fun fixedPassword(deviceId: String, password: String, permissions: Set<String>) = perform {
        check(_state.value.authenticated && _state.value.native.available) { "RD_AUTH_REQUIRED" }
        val target = requireNotNull(api).redeemFixed(deviceId, password)
        connectAssistedTarget(target, permissions, "fixed_password")
    }
    fun requestAccess(deviceId: String, permissions: Set<String>) = perform {
        check(_state.value.authenticated && _state.value.native.available) { "RD_AUTH_REQUIRED" }
        val activeApi = requireNotNull(api)
        val expectedConnection = connectionGeneration
        val created = activeApi.requestAccess(deviceId)
        val requestId = UUID.fromString(created.string("id")).toString()
        val deadline = Instant.parse(created.string("expires_at"))
        var target: JsonObject? = null
        while (Instant.now().isBefore(deadline) && expectedConnection == connectionGeneration) {
            delay(1000)
            val status = activeApi.accessRequest(requestId)
            if (expectedConnection != connectionGeneration) return@perform
            when (status.string("state")) {
                "pending" -> Unit
                "approved" -> { target = status.getValue("target").jsonObject; break }
                "rejected" -> error("RD_ACCESS_REJECTED")
                else -> error("RD_ACCESS_EXPIRED")
            }
        }
        if (expectedConnection != connectionGeneration) return@perform
        connectAssistedTarget(target ?: error("RD_ACCESS_EXPIRED"), permissions, "local_approval")
    }
    private suspend fun connectAssistedTarget(target: JsonObject, permissions: Set<String>, mode: String) {
        val hostId = UUID.fromString(target.string("host_endpoint_id")).toString()
        val ownerId = UUID.fromString(target.string("host_owner_user_id")).toString()
        val inviteId = UUID.fromString(target.string("invite_id")).toString()
        val hostKey = target.getValue("host_public_jwk").jsonObject
        val fingerprint = RemoteCrypto.thumbprint(hostKey)
        require(target["host_jkt"] == JsonPrimitive(fingerprint)) { "RD_PEER_IDENTITY_MISMATCH" }
        val capabilities = target.getValue("capabilities").jsonObject
        val displayId = (capabilities["displays"] as? JsonArray)?.firstOrNull()?.jsonObject?.string("id")
        require(capabilities["status"] == JsonPrimitive("ready") && displayId != null && ownerId != account()?.user?.id) { "RD_HOST_UNAVAILABLE" }
        val host = RemoteEndpoint(hostId, target.string("host_name"), fingerprint, displayId, true, ownerId, inviteId,
            offeredAccessModes = offeredAccessModes(target), nativeBackends = nativeBackends(capabilities))
        check(hostOffersMode(host, mode)) { "RD_ACCESS_MODE_UNAVAILABLE" }
        _state.value = _state.value.copy(assistTarget = host)
        beginPair(host, permissions)
    }
    fun pair(host: RemoteEndpoint, permissions: Set<String>) = perform {
        check(hostOffersMode(host, "local_approval")) { "RD_ACCESS_MODE_UNAVAILABLE" }
        require("view" in permissions && P.permissions.containsAll(permissions))
        val expectedConnection = connectionGeneration
        if (_state.value.authenticated && _state.value.native.available && host.available) {
            val activeApi = requireNotNull(api)
            val controllerEndpointId = requireNotNull(activeApi.endpointId)
            val trusted = if (host.assistInviteId == null && host.ownerUserId == account()?.user?.id)
                matchingPersistentGrant(activeApi.grants(), host, requireNotNull(account()).user.id, controllerEndpointId, permissions, Instant.now())
            else null
            if (expectedConnection != connectionGeneration) return@perform
            if (trusted != null) {
                val requestId = UUID.randomUUID().toString()
                startGrantedSession(host, trusted, permissions, requestId, "persistent", expectedConnection)
                return@perform
            }
        }
        beginPair(host, permissions)
    }
    fun bindTrusted(host: RemoteEndpoint, permissions: Set<String>, password: String, mfa: String) = perform {
        check(_state.value.authenticated && _state.value.native.available) { "RD_AUTH_REQUIRED" }
        check(canBindTrusted(host, permissions)) { "RD_UNATTENDED_DENIED" }
        requireNotNull(api).reauthenticate(password, mfa)
        beginPair(host, permissions, "persistent")
    }
    fun canBindTrusted(host: RemoteEndpoint, permissions: Set<String>): Boolean =
        eligibleForTrustedBinding(host, account()?.user?.id, permissions)
    private suspend fun beginPair(host: RemoteEndpoint, permissions: Set<String>, mode: String = "one_session") {
        check(_state.value.authenticated && host.available) { "RD_HOST_UNAVAILABLE" }
        check(_state.value.native.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        require(_state.value.native.permissions.containsAll(permissions)) { "RD_SCOPE_DENIED" }
        require(host.ownerUserId != null && (host.ownerUserId == account()?.user?.id || host.assistInviteId != null)) { "RD_INVITE_REQUIRED" }
        require("view" in permissions && P.permissions.containsAll(permissions))
        if (mode == "persistent") check(canBindTrusted(host, permissions)) { "RD_UNATTENDED_DENIED" }
        else require(mode == "one_session")
        val requestId = UUID.randomUUID().toString()
        val nonce = RemoteCrypto.base64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val expectedConnection = connectionGeneration
        val activeApi = requireNotNull(api)
        val response = activeApi.pairing(host.id, permissions, requestId, nonce, host.assistInviteId, mode)
        if (expectedConnection != connectionGeneration) {
            runCatching { activeApi.rejectPairing(response.string("id")) }
            return
        }
        activePairingId = response.string("id")
        val transcript = response.getValue("transcript").jsonObject
        val expected = mapOf(
            "domain" to JsonPrimitive("ht-rd-pairing-v1"), "server_instance_id" to JsonPrimitive(requireNotNull(serverInstance)),
            "pairing_id" to response.getValue("id"), "host_endpoint_id" to JsonPrimitive(host.id),
            "controller_endpoint_id" to JsonPrimitive(requireNotNull(api?.endpointId)),
            "host_jkt" to JsonPrimitive(host.fingerprint), "controller_jkt" to JsonPrimitive(requireNotNull(_state.value.fingerprint)),
            "nonce_host" to JsonNull, "nonce_controller" to JsonPrimitive(nonce),
            "scope" to JsonArray(permissions.sorted().map(::JsonPrimitive)), "mode" to JsonPrimitive(mode),
            "session_request_id" to JsonPrimitive(requestId), "expires_at" to response.getValue("expires_at"),
        ) + if (host.assistInviteId != null) mapOf(
            "assist_invite_id" to JsonPrimitive(host.assistInviteId),
            "host_owner_user_id" to JsonPrimitive(host.ownerUserId),
            "controller_owner_user_id" to JsonPrimitive(requireNotNull(account()).user.id),
        ) else emptyMap()
        require(transcript == JsonObject(expected)) { "RD_PAIRING_CONTEXT" }
        val pending = RemotePairing(response.string("id"), host, requestId, transcript)
        _state.value = _state.value.copy(pairing = pending, pairingId = pending.id, pairingCode = null)
        pairingPoll?.cancel()
        val expectedGeneration = generation
        pairingPoll = scope.launch {
            while (generation == expectedGeneration && _state.value.pairing?.id == pending.id) {
                delay(1000)
                if (operation?.isActive == true) continue
                refreshPairing()
                operation?.join()
                if (generation != expectedGeneration || _state.value.error != null) break
                if (_state.value.pairing?.code != null) {
                    confirmPairing()
                    operation?.join()
                    break
                }
            }
        }
    }
    fun refreshPairing() = perform {
        val pending = requireNotNull(_state.value.pairing)
        val expectedConnection = connectionGeneration
        val response = requireNotNull(api).pairing(pending.id)
        if (expectedConnection != connectionGeneration || _state.value.pairing?.id != pending.id) return@perform
        check(response["state"] == JsonPrimitive("pending")) { "RD_PAIRING_EXPIRED" }
        val transcript = response.getValue("transcript").jsonObject
        require(transcript.filterKeys { it != "nonce_host" } == pending.transcript.filterKeys { it != "nonce_host" }) { "RD_PAIRING_CONTEXT" }
        require(Instant.parse(transcript.string("expires_at")).isAfter(Instant.now())) { "RD_PAIRING_EXPIRED" }
        if (transcript["nonce_host"] != JsonNull) {
            require(RemoteCrypto.decode(transcript.string("nonce_host"), 32).size == 32)
            val code = RemoteCrypto.sha256(RemoteJson.canonical(transcript).toByteArray()).take(16)
                .joinToString("") { "%02x".format(it) }.chunked(4).joinToString("-")
            _state.value = _state.value.copy(pairing = pending.copy(transcript = transcript, code = code), pairingCode = code)
        }
    }
    fun confirmPairing() = perform {
        val pending = requireNotNull(_state.value.pairing)
        val expectedConnection = connectionGeneration
        val activeApi = requireNotNull(api)
        check(pending.code != null && Instant.parse(pending.transcript.string("expires_at")).isAfter(Instant.now())) { "RD_PAIRING_EXPIRED" }
        val proof = RemoteCrypto.signJws(requireNotNull(identity), "ht-rd-pairing+jwt", pending.transcript)
        val pairing = activeApi.confirmPairing(pending.id, proof)
        if (expectedConnection != connectionGeneration) {
            runCatching { activeApi.rejectPairing(pending.id) }
            return@perform
        }
        check(pairing["state"] == JsonPrimitive("confirmed") && pairing["grant_id"] == JsonPrimitive(pending.id)) { "RD_PAIRING_CONTEXT" }
        val mode = pending.transcript.string("mode")
        if (mode == "persistent") activePairingId = null
        _state.value = _state.value.copy(pairing = null)
        val permissions = (pending.transcript.getValue("scope") as JsonArray).map { it.jsonPrimitive.content }.toSet()
        startGrantedSession(pending.host, pending.id, permissions, pending.requestId, mode, expectedConnection)
    }
    private suspend fun startGrantedSession(host: RemoteEndpoint, grantId: String, permissions: Set<String>, requestId: String,
        grantMode: String, expectedConnection: Long) {
        check(_state.value.native.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        stopLocal()
        try {
            initializeNative(host)
            val nativeGeneration = nativeLifetime
            val activeApi = requireNotNull(api)
            val created = activeApi.createSession(host.id, grantId, permissions, requestId, requireNotNull(host.displayId))
            val session = created["session_id"]?.jsonPrimitive?.content ?: created.string("id")
            if (expectedConnection != connectionGeneration) {
                runCatching { activeApi.closeSession(session) }
                if (grantMode == "one_session") runCatching { activeApi.rejectPairing(grantId) }
                if (nativeLifetime == nativeGeneration) stopLocal()
                return
            }
            val selected = requireNotNull(account())
            require(created["owner_user_id"] == JsonPrimitive(host.ownerUserId) &&
                created["controller_owner_user_id"] == JsonPrimitive(selected.user.id)) { "RD_AUTHORIZATION_CONTEXT" }
            val keys = requireNotNull(trustedKeys)
            val binding = RemoteAuthorizationBinding(
                selected.profile.publicBaseUrl.trimEnd('/'), keys.string("server_instance_id"), keys.number("restore_epoch"),
                session, requestId, created.number("connection_epoch", 0xffffffffL), requireNotNull(host.ownerUserId),
                requireNotNull(activeApi.endpointId), host.id, requireNotNull(_state.value.fingerprint), host.fingerprint,
                permissions, grantId, grantMode,
            )
            continuation = RemoteSessionContinuation(binding, requireNotNull(host.displayId))
            sessionHost = host
            authorization = RemoteAuthorization(binding, keys)
            _state.value = _state.value.copy(sessionId = session, hostName = host.name, phase = "pending_approval")
            // Re-read after binding to recover an authorization event that raced the HTTP response.
            val latest = activeApi.session(session)
            if (expectedConnection != connectionGeneration) {
                if (nativeLifetime == nativeGeneration) stopLocal()
                return
            }
            applySessionSnapshot(latest)
            connectionDeadline(60_000, "RD_CONNECT_TIMEOUT")
        } catch (error: Exception) { stopLocal(); throw error }
    }
    private fun initializeNative(host: RemoteEndpoint) {
        clipboardTransfer = RemoteClipboardTransfer(send = { type, payload ->
            val permission = if (type == P.CLIPBOARD_OFFER || type == P.CLIPBOARD_CHUNK) "clipboard.write" else "clipboard.read"
            check(gate.featureEnabled(permission)) { "RD_CLIPBOARD_DISABLED" }
            val next = clipboardSequence + 1
            requireNotNull(native).submit(RemoteWire.frame(type, gate.epoch, 0, next, payload))
            clipboardSequence = next
        }, receiveText = { text ->
            check(foreground && gate.featureEnabled("clipboard.read")) { "RD_CLIPBOARD_DISABLED" }
            RemoteClipboard(applicationContext) { gate.featureEnabled(it) }.writeText(text, foreground)
        })
        val lifetime = ++nativeLifetime
        native = RemoteNativeSession { type, _, _, payload ->
            scope.launch {
                if (nativeLifetime == lifetime) try { nativeEvent(type, payload) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (nativeLifetime == lifetime) failSession("RD_NATIVE_PROTOCOL_FAILED") }
            }
        }
        val sessionNative = requireNotNull(native)
        check(sessionNative.capability.available) { "RD_MEDIA_BACKEND_UNAVAILABLE" }
        fileTransfers = RemoteNativeFiles(applicationContext, scope, { native },
            { nativeLifetime == lifetime && foreground && gate.live() }, gate::featureEnabled,
            { items, preparing -> if (nativeLifetime == lifetime) _state.value = _state.value.copy(fileTransfers = items, filePreparing = preparing) },
            { error -> if (nativeLifetime == lifetime) _state.value = _state.value.copy(error = error) })
        gate.bindLocalCapabilities(sessionNative.capability.permissions)
        gate.bindHostDiscovery(host.nativeBackends)
    }
    fun selectDisplay(id: String) {
        check(foreground && gate.live() && gate.connectionEstablished && _state.value.phase == "active") { "RD_CONTROL_NOT_READY" }
        require(_state.value.displays.any { it.id == id }) { "RD_DISPLAY" }
        if (_state.value.display?.id == id) return
        reconnectSession("display_changed", id)
    }
    fun retryConnection() = reconnectSession("network_changed")
    private fun reconnectSession(reason: String, displayId: String? = null) {
        check(!accountTransitioning && _state.value.authenticated && foreground && gate.live() && reconnectJob?.isActive != true) { "RD_RECONNECT_UNAVAILABLE" }
        val previous = requireNotNull(continuation) { "RD_SESSION_CONTEXT" }
        val next = previous.next(reason, displayId)
        val host = requireNotNull(sessionHost)
        val activeApi = requireNotNull(api)
        val displays = _state.value.displays
        val expectedConnection = connectionGeneration
        runCatching { releaseControl() }
        // Dispose input, file handles, audio and clipboard before the HTTP call. No old authority survives.
        stopLocal()
        continuation = next; sessionHost = host
        try {
            initializeNative(host)
            authorization = RemoteAuthorization(next.binding, requireNotNull(trustedKeys))
            _state.value = _state.value.copy(sessionId = next.binding.sessionId, hostName = host.name, displays = displays,
                phase = if (displayId == null) "reconnecting" else "switching_display", connectionPhase = "recovering", error = null)
            val lifetime = nativeLifetime
            connectionDeadline(28_000, "RD_RECONNECT_TIMEOUT")
            reconnectJob = scope.launch {
                try {
                    val snapshot = activeApi.reconnectSession(next.binding.sessionId, previous.binding.connectionEpoch, reason, displayId)
                    if (lifetime != nativeLifetime || expectedConnection != connectionGeneration) return@launch
                    applySessionSnapshot(snapshot)
                    if (lifetime != nativeLifetime) return@launch
                    val latest = activeApi.session(next.binding.sessionId)
                    if (lifetime == nativeLifetime && expectedConnection == connectionGeneration) applySessionSnapshot(latest)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (lifetime == nativeLifetime) failSession((error as? RemoteApiException)?.code ?: "RD_RECONNECT_FAILED")
                }
            }
        } catch (error: Exception) { failSession("RD_RECONNECT_FAILED"); throw error }
    }
    private fun connectionDeadline(timeout: Long, code: String) {
        val lifetime = nativeLifetime
        scope.launch {
            delay(timeout)
            if (lifetime == nativeLifetime && _state.value.sessionId != null && !gate.connectionEstablished) failSession(code)
        }
    }
    private fun failSession(code: String) {
        closeSession()
        _state.value = _state.value.copy(error = code, failureCode = code, failureAction = "retry_session")
    }
    fun cancelPairing() = closeSession()
    private suspend fun nativeEvent(type: Int, bytes: ByteArray) {
        if (type == 1 || type == 3) {
            val reason = runCatching { RemoteJson.parse(bytes).string("error_code") }.getOrNull()
            if (reason in setOf("RD_NO_DIRECT_PATH", "RD_MEDIA_FAILED") && foreground && gate.live() &&
                continuation?.networkReconnects?.let { it < 3 } == true && reconnectJob?.isActive != true) {
                reconnectSession(if (reason == "RD_NO_DIRECT_PATH") "ice_failed" else "media_failed")
            } else failSession(reason?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) } ?: "RD_SESSION_CLOSED")
            return
        }
        if (type == 2) { gate.pause(); _state.value = _state.value.copy(phase = "paused", inputEnabled = false); return }
        // Clipboard event 9 contains a binary protocol frame, not JSON.
        if (type == 9) {
            clipboardTransfer?.receive(bytes)
            if (bytes.size >= 4 && (bytes[3].toInt() and 0xff) == P.CLIPBOARD_ACK) syncLocalClipboard()
            return
        }
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
                if (_state.value.phase == "paused" && foreground && gate.firstFramePresented) _state.value = _state.value.copy(phase = "active")
            }
            7 -> {
                val epoch = payload.number("epoch")
                val body = payload.getValue("payload").jsonObject
                when (payload.number("type").toInt()) {
                    P.DISPLAY_LAYOUT -> {
                        val (displays, display) = parseDisplayLayout(body)
                        require(display.id == continuation?.displayId) { "RD_DISPLAY" }
                        gate.displayLayout(epoch, body.number("layout_epoch"))
                        autoInputRequested = false
                        _state.value = _state.value.copy(inputEnabled = false, display = display, displays = displays)
                        if (foreground && gate.firstFramePresented) runCatching { requestControl() }.onSuccess { autoInputRequested = true }
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
                            _state.value = _state.value.copy(textStatus = if (body.nonNegativeNumber("status", 1) == 0L) "confirmed" else "failed")
                        }
                    }
                    P.FEATURE_STATE -> {
                        require(epoch == gate.epoch)
                        val permission = body.string("permission")
                        require(permission in setOf("clipboard.read", "clipboard.write", "audio.system", "files.send", "files.receive")) { "RD_SCOPE_DENIED" }
                        val enabled = body.getValue("enabled").jsonPrimitive.booleanOrNull == true && gate.canUse(permission)
                        gate.feature(permission, enabled)
                        featureRequests.remove(permission)
                        if (permission.startsWith("clipboard.")) clipboardTransfer?.feature(permission, enabled)
                        _state.value = _state.value.copy(clipboardEnabled = gate.featureEnabled("clipboard.read") && gate.featureEnabled("clipboard.write"),
                            audioEnabled = gate.featureEnabled("audio.system"), filesReceiveEnabled = gate.featureEnabled("files.receive"),
                            pendingFeatures = _state.value.pendingFeatures - permission,
                            error = body["error_code"]?.jsonPrimitive?.contentOrNull)
                        if (permission == "clipboard.write") {
                            if (enabled) startClipboardSync() else stopClipboardSync()
                        }
                    }
                    else -> error("RD_PROTOCOL_MISMATCH")
                }
            }
            8 -> {
                require(gate.live() && payload.number("epoch") == gate.epoch && payload.number("frames_presented") > 0) { "RD_STATE_CONFLICT" }
                if (!gate.presentedFrame(payload.number("epoch"), payload.number("surface_generation"), payload.number("frames_presented"))) return
                _state.value = _state.value.copy(phase = if (foreground) "active" else "paused")
                if (foreground && !autoInputRequested) {
                    runCatching { requestControl() }.onSuccess { autoInputRequested = true }
                }
                requestClipboardAutomatically()
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
            10 -> fileTransfers?.event(payload)
            else -> error("RD_NATIVE_EVENT_INVALID")
        }
    }
    private fun signalEvent(event: JsonObject) {
        val type = event.string("type")
        if (type != "auth.expired" && event["session_id"] != JsonPrimitive(_state.value.sessionId)) return
        when (type) {
            "session.revoked", "auth.expired" -> { stopLocal(); _state.value = _state.value.copy(error = "RD_AUTH_REVOKED") }
            "session.authorized", "session.state", "session.lease_updated", "session.reconnect_requested" -> {
                try { applySessionSnapshot(event.getValue("payload").jsonObject) }
                catch (_: Exception) { failSession("RD_PROTOCOL_MISMATCH") }
            }
            "peer.offer", "peer.answer", "peer.candidates", "peer.candidates_done" -> {
                // The shared native engine owns all ticket, peer identity and path proof checks.
                try {
                    val expectedEpoch = continuation?.binding?.connectionEpoch ?: return
                    val eventEpoch = event.number("connection_epoch", 0xffffffffL)
                    if (eventEpoch < expectedEpoch) return
                    require(eventEpoch == expectedEpoch) { "RD_SESSION_EPOCH_MISMATCH" }
                    native?.signal(event.toString().toByteArray())
                    if (type == "peer.answer") answerJws = event.string("payload_jws")
                }
                catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_PROTOCOL_MISMATCH") }
            }
        }
    }
    private fun applySessionSnapshot(snapshot: JsonObject) {
        val current = requireNotNull(continuation) { "RD_SESSION_CONTEXT" }
        if (!current.currentSnapshot(snapshot)) return
        require(snapshot["controller_owner_user_id"] == JsonPrimitive(account()?.user?.id)) { "RD_AUTHORIZATION_CONTEXT" }
        val version = snapshot.number("state_version")
        if (version <= snapshotVersion) return
        rememberSessionSurface(snapshot)
        when (snapshot.string("state")) {
            "closing", "closed", "failed", "expired" -> {
                val reason = snapshot["close_reason"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) } ?: "RD_SESSION_CLOSED"
                stopLocal(); _state.value = _state.value.copy(error = reason)
                return
            }
            "authorized", "connecting", "active" -> authorizeSession(snapshot)
            "pending_approval", "reconnecting" -> check(verifiedAuthorization == null) { "RD_STATE_CONFLICT" }
            else -> error("RD_STATE_CONFLICT")
        }
        snapshotVersion = version
    }
    private fun authorizeSession(snapshot: JsonObject) {
        val verifier = requireNotNull(authorization) { "RD_SESSION_AUTH_MISSING" }
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
        // The SurfaceView can survive an epoch change or Activity recomposition.
        setSurface(attachedSurface?.takeIf { it.isValid })
        if (foreground) native?.resume() else native?.pause()
        _state.value = _state.value.copy(phase = "connecting").withSessionSurface(readSessionSurface(snapshot))
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
        val lifetime = nativeLifetime
        scope.launch {
            delay(remaining + 1)
            if (generation == expected && nativeLifetime == lifetime && _state.value.sessionId == session && !gate.live()) {
                stopLocal(); _state.value = _state.value.copy(error = "RD_LEASE_EXPIRED")
            }
        }
    }
    private fun updateEndpoints(value: JsonObject) {
        val items = (value.getValue("items") as JsonArray).map { it.jsonObject }
        _state.value = _state.value.copy(endpoints = items.filter { it["role"] != JsonPrimitive("controller") }.map {
            val capabilities = it["capabilities"]?.jsonObject
            val displayId = (capabilities?.get("displays") as? JsonArray)?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            RemoteEndpoint(it.string("id"), it.string("name"), it.string("jkt"), displayId,
                it["status"] == JsonPrimitive("active") && it["online"]?.jsonPrimitive?.booleanOrNull == true &&
                    it["local_enabled"]?.jsonPrimitive?.booleanOrNull == true && capabilities?.get("status") == JsonPrimitive("ready") && displayId != null,
                it.string("owner_user_id"), unattendedEnabled = capabilities?.get("unattended_enabled")?.jsonPrimitive?.booleanOrNull == true,
                offeredAccessModes = offeredAccessModes(it), nativeBackends = nativeBackends(capabilities))
        })
    }
    fun setSurface(surface: Surface?) {
        attachedSurface = surface
        val surfaceGeneration = gate.surface(surface != null)
        autoInputRequested = false
        if (surface == null) { gate.feature("audio.system", false); _state.value = _state.value.copy(audioEnabled = false) }
        _state.value = _state.value.copy(inputEnabled = false,
            phase = if (_state.value.phase == "active") "connecting" else _state.value.phase)
        try { native?.surface(surface, surfaceGeneration) }
        catch (_: Exception) { stopLocal(); _state.value = _state.value.copy(error = "RD_SURFACE_FAILED") }
    }
    fun onForeground() {
        foreground = true; gate.foreground(true)
        try { native?.resume() }
        catch (_: RuntimeException) { stopLocal(); _state.value = _state.value.copy(error = "RD_SESSION_EXPIRED") }
        requestClipboardAutomatically()
    }
    fun onBackground() {
        foreground = false; autoInputRequested = false; autoClipboardRequested = false
        gate.foreground(false); clipboardTransfer?.reset(); stopClipboardSync()
        _state.value = _state.value.copy(clipboardEnabled = false, audioEnabled = false, filesReceiveEnabled = false, pendingFeatures = emptySet())
        native?.pause()
        featureRequests.clear()
        fileTransfers?.pause()
    }
    suspend fun onAccountChanged() {
        accountTransitioning = true
        val previousApi = api
        val previousOperation = operation
        val previousCleanup = cleanupJob
        val sessionId = _state.value.sessionId
        val pairingId = activePairingId ?: _state.value.pairing?.id
        generation++; connectionGeneration++; signalingGeneration++
        pairingPoll?.cancel(); pairingPoll = null
        stopLocal(); signaling?.close(); signaling = null
        _state.value = RemoteViewState()
        try {
            withTimeoutOrNull(25_000) {
                previousOperation?.join()
                previousCleanup?.join()
                if (sessionId != null) try { previousApi?.closeSession(sessionId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                if (pairingId != null) try { previousApi?.rejectPairing(pairingId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        } finally {
            scope.coroutineContext.cancelChildren(); operation = null; cleanupJob = null
            previousApi?.clear(); api = null; activePairingId = null
            identity = null; serverInstance = null; trustedKeys = null; accountKey = null; stunUrls = emptyList()
            accountTransitioning = false
        }
    }
    fun closeSession() {
        connectionGeneration++
        pairingPoll?.cancel(); pairingPoll = null
        val activeApi = api
        val pairingId = activePairingId ?: _state.value.pairing?.id
        activePairingId = null
        val sessionId = _state.value.sessionId
        stopLocal()
        _state.value = _state.value.copy(pairing = null, pairingId = null, pairingCode = null, loading = false)
        if (pairingId != null || sessionId != null) cleanupJob = scope.launch(Dispatchers.IO) {
            if (sessionId != null) runCatching { activeApi?.closeSession(sessionId) }
            if (pairingId != null) runCatching { activeApi?.rejectPairing(pairingId) }
        }
    }
    private fun stopLocal() {
        reconnectJob?.cancel(); reconnectJob = null
        nativeLifetime++; gate.close(); native?.close(); native = null; sequence = 0; controlSequence = 0; motionChannelSequence = 0; motionSequence = 0
        fileTransfers?.close(); fileTransfers = null
        featureRequests.clear()
        autoInputRequested = false; autoClipboardRequested = false; clipboardSequence = 0
        stopClipboardSync(); clipboardTransfer?.reset(); clipboardTransfer = null
        peerSequence = 0; offerJws = null; answerJws = null; pendingText = null
        gate = RemoteSessionGate(SystemClock::elapsedRealtime)
        authorization = null; verifiedAuthorization = null; verifiedTicket = null
        continuation = null; sessionHost = null; snapshotVersion = 0
        _state.value = _state.value.copy(sessionId = null, hostName = null, phase = "idle", inputEnabled = false, display = null, displays = emptyList(), textStatus = null, clipboardEnabled = false,
            connectionPhase = null, failureCode = null, failureAction = null, accessMode = null,
            audioEnabled = false, filesReceiveEnabled = false, pendingFeatures = emptySet(), fileTransfers = emptyList(), filePreparing = false)
    }
    private fun clearAuthentication() {
        pairingPoll?.cancel(); pairingPoll = null
        stopLocal(); signalingGeneration++; signaling?.close(); signaling = null
        api?.clear(); identity = null
        _state.value = _state.value.copy(authenticated = false, fingerprint = null, pairing = null, pairingId = null, pairingCode = null, assistTarget = null)
    }
    fun canUse(permission: String): Boolean = foreground && gate.canUse(permission)
    fun requestControl() {
        if (gate.inputPending || _state.value.inputEnabled && gate.permissions.any { it.startsWith("input.") && gate.canUse(it) }) return
        val payload = gate.requestInput()
        _state.value = _state.value.copy(inputEnabled = false)
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
    fun filesTransportReady(): Boolean = native?.capability?.let { it.available && it.permissions.any { permission -> permission.startsWith("files.") } } == true
    fun audioTransportReady(): Boolean = native?.capability?.let { it.available && "audio.system" in it.permissions } == true
    fun beginFileSend(files: List<RemoteSelectedFile>) {
        check(files.isNotEmpty() && canUse("files.send") && filesTransportReady()) { "RD_FILE_TRANSPORT_UNAVAILABLE" }
        val lifetime = nativeLifetime
        val transfers = requireNotNull(fileTransfers)
        scope.launch {
            try {
                if (!gate.featureEnabled("files.send")) {
                    requestFeature("files.send", true)
                    check(withTimeoutOrNull(5000) {
                        while (nativeLifetime == lifetime && foreground && gate.live() && !gate.featureEnabled("files.send")) delay(50)
                        nativeLifetime == lifetime && gate.featureEnabled("files.send")
                    } == true) { "RD_FILE_TRANSPORT_UNAVAILABLE" }
                }
                check(nativeLifetime == lifetime) { "RD_FILE_CANCELLED" }
                transfers.send(files)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (nativeLifetime == lifetime) _state.value = _state.value.copy(error = error.message?.takeIf { it.startsWith("RD_") } ?: "RD_FILE_READ_FAILED") }
        }
    }
    fun acceptFile(id: String) { requireNotNull(fileTransfers).accept(id) }
    fun cancelFile(id: String) { fileTransfers?.cancel(id) }
    fun saveFile(session: String, id: String, destination: Uri) {
        check(_state.value.sessionId == session) { "RD_FILE_SESSION_CHANGED" }
        requireNotNull(fileTransfers).export(id, destination)
    }
    fun requestFeature(permission: String, enabled: Boolean) {
        check(permission in setOf("clipboard.read", "clipboard.write", "audio.system", "files.send", "files.receive")) { "RD_FEATURE_UNSUPPORTED" }
        if (enabled) check(canUse(permission)) { "RD_CONTROL_NOT_READY" }
        if (permission == "audio.system" || permission.startsWith("files.")) {
            val request = ++featureRequestSequence
            featureRequests[permission] = request
            if (permission == "audio.system") requireNotNull(native).systemAudio(enabled)
            else requireNotNull(native).filesEnabled(if (permission == "files.send") 1 else 2, enabled)
            if (!enabled) gate.feature(permission, false)
            _state.value = _state.value.copy(audioEnabled = gate.featureEnabled("audio.system"),
                filesReceiveEnabled = gate.featureEnabled("files.receive"), pendingFeatures = _state.value.pendingFeatures + permission, error = null)
            val lifetime = nativeLifetime
            scope.launch {
                delay(5000)
                if (nativeLifetime == lifetime && featureRequests[permission] == request && permission in _state.value.pendingFeatures) {
                    featureRequests.remove(permission)
                    if (permission == "audio.system") runCatching { native?.systemAudio(false) }
                    else runCatching { native?.filesEnabled(if (permission == "files.send") 1 else 2, false) }
                    gate.feature(permission, false)
                    _state.value = _state.value.copy(pendingFeatures = _state.value.pendingFeatures - permission,
                        audioEnabled = gate.featureEnabled("audio.system"), filesReceiveEnabled = gate.featureEnabled("files.receive"), error = "RD_FEATURE_TIMEOUT")
                }
            }
            return
        }
        val body = buildJsonObject {
            put("permission", JsonPrimitive(permission))
            put("enabled", JsonPrimitive(enabled))
        }.toString().toByteArray()
        requireNotNull(native).submit(RemoteWire.frame(P.FEATURE_REQUEST, gate.epoch, 0, ++controlSequence, body))
        // A request never turns on the permission gate; that requires a verified peer acknowledgement.
    }
    private fun requestClipboardAutomatically() {
        if (!foreground || autoClipboardRequested || !gate.firstFramePresented || !gate.live()) return
        val permissions = listOf("clipboard.read", "clipboard.write").filter(gate::canUse)
        if (permissions.isEmpty()) return
        runCatching { permissions.forEach { requestFeature(it, true) } }.onSuccess { autoClipboardRequested = true }
    }
    private fun startClipboardSync() {
        if (clipboardListening || !foreground || !gate.featureEnabled("clipboard.write")) return
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        clipboardListening = true
        syncLocalClipboard()
    }
    private fun stopClipboardSync() {
        if (!clipboardListening) return
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        clipboardListening = false
    }
    private fun syncLocalClipboard() {
        if (!foreground || !gate.featureEnabled("clipboard.write")) return
        val text = runCatching { RemoteClipboard(applicationContext) { gate.featureEnabled(it) }.readText(foreground) }.getOrNull()
        if (text != null) runCatching { clipboardTransfer?.offer(text) }
    }
    private fun rememberSessionSurface(snapshot: JsonObject) {
        _state.value = _state.value.withSessionSurface(readSessionSurface(snapshot))
    }
    private fun send(permission: String, type: Int, payload: ByteArray) {
        check(canUse(permission)) { "RD_CONTROL_NOT_READY" }
        val next = if (type == P.POINTER_ABS) ++motionChannelSequence else ++sequence
        requireNotNull(native).submit(RemoteWire.frame(type, gate.epoch, gate.inputEpoch, next, payload))
    }
    private fun perform(block: suspend () -> Unit) {
        if (accountTransitioning || operation?.isActive == true) return
        val expected = generation
        val expectedConnection = connectionGeneration
        operation = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == expected && connectionGeneration == expectedConnection) {
                    val code = (error as? RemoteApiException)?.code ?: error.message?.takeIf { Regex("RD_[A-Z0-9_]{1,72}").matches(it) } ?: "RD_OPERATION_FAILED"
                    _state.value = _state.value.copy(error = code)
                }
            } finally { if (generation == expected) _state.value = _state.value.copy(loading = false) }
        }
    }
}
