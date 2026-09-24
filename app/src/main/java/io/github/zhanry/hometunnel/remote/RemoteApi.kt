package io.github.zhanry.hometunnel.remote

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RemoteApiException(val code: String, val httpStatus: Int) : Exception(code)

internal class RemoteToken(val value: String, val expiresAt: Instant, var nonce: String) {
    override fun toString() = "RemoteToken([redacted])"
}

class RemoteApi(
    apiBaseUrl: String,
    private val parentRequest: suspend (String, String, JsonObject?) -> JsonObject,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    val baseUrl: HttpUrl = apiBaseUrl.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/api/v1/" && it.query == null && it.fragment == null)
    }
    private var token: RemoteToken? = null
    private var identity: RemoteIdentity? = null
    private var instance: String? = null
    private val tokenMutex = Mutex()
    private val activeCalls = mutableSetOf<Call>()
    @Volatile private var generation = 0L
    var endpointId: String? = null; private set

    suspend fun capabilities(): JsonObject = request("GET", "public/capabilities", authenticated = false)
    suspend fun serverKeys(): JsonObject = request("GET", "rd/server-keys", authenticated = false, responseMaximum = 256 * 1024)

    suspend fun reauthenticate(password: String, mfa: String) {
        require(password.isNotEmpty() && password.length <= 1024 && mfa.length <= 64)
        parentRequest("POST", "rd/reauth", buildJsonObject { put("password", password); if (mfa.isNotEmpty()) put("mfa_code", mfa) })
    }

    suspend fun enroll(identity: RemoteIdentity, serverInstance: String, name: String): JsonObject {
        val expectedGeneration = generation
        val jwk = identity.publicJwk
        val challenge = parentRequest("POST", "rd/enrollment-challenges", buildJsonObject {
            put("endpoint_kind", "android"); put("role", "controller"); put("public_jwk", jwk)
        })
        val proof = validateChallenge(challenge, "enrollment", serverInstance)
        require(proof["endpoint_kind"] == JsonPrimitive("android") && proof["role"] == JsonPrimitive("controller") &&
            proof["public_jwk"] == jwk && proof["linked_device_id"] == kotlinx.serialization.json.JsonNull) { "RD_CHALLENGE_CONTEXT" }
        val result = parentRequest("POST", "rd/endpoints", buildJsonObject {
            put("challenge_id", challenge.string("challenge_id"))
            put("signed_proof", RemoteCrypto.signJws(identity, "ht-rd-proof+jwt", proof))
            put("name", name.take(80)); put("platform", "android")
        })
        val endpoint = result.getValue("endpoint").jsonObject
        require(endpoint["jkt"] == JsonPrimitive(RemoteCrypto.thumbprint(jwk))) { "RD_ENDPOINT_IDENTITY" }
        synchronized(this) {
            check(generation == expectedGeneration) { "RD_ACCOUNT_CHANGED" }
            this.identity = identity; this.instance = serverInstance; endpointId = endpoint.string("id").also { UUID.fromString(it) }
            installToken(result)
        }
        return endpoint
    }

    suspend fun restore(identity: RemoteIdentity, endpointId: String, serverInstance: String) {
        val expectedGeneration = generation
        UUID.fromString(endpointId)
        val challenge = parentRequest("POST", "rd/token-challenges", buildJsonObject {
            put("endpoint_id", endpointId); put("purpose", "controller_refresh")
        })
        val payload = validateChallenge(challenge, "controller_refresh", serverInstance)
        require(payload["endpoint_id"] == JsonPrimitive(endpointId)) { "RD_CHALLENGE_CONTEXT" }
        val result = parentRequest("POST", "rd/tokens", buildJsonObject {
            put("challenge_id", challenge.string("challenge_id")); put("endpoint_id", endpointId)
            put("proof", RemoteCrypto.signJws(identity, "ht-rd-proof+jwt", payload))
        })
        synchronized(this) {
            check(generation == expectedGeneration) { "RD_ACCOUNT_CHANGED" }
            this.identity = identity; this.instance = serverInstance; this.endpointId = endpointId; installToken(result)
        }
    }
    private fun validateChallenge(challenge: JsonObject, purpose: String, instance: String): JsonObject {
        val deadline = Instant.parse(challenge.string("expires_at"))
        require(deadline.isAfter(Instant.now()) && deadline.isBefore(Instant.now().plusSeconds(35))) { "RD_CHALLENGE_EXPIRED" }
        val payload = challenge.getValue("proof_payload").jsonObject
        val expectedFields = setOf("purpose", "challenge_id", "nonce", "server_instance_id", "issued_at") +
            if (purpose == "enrollment") setOf("endpoint_kind", "role", "public_jwk", "linked_device_id") else setOf("endpoint_id")
        require(payload.keys == expectedFields) { "RD_CHALLENGE_CONTEXT" }
        require(payload["purpose"] == JsonPrimitive(purpose) && payload["server_instance_id"] == JsonPrimitive(instance) &&
            payload["challenge_id"] == challenge["challenge_id"] && payload["nonce"] == challenge["nonce"]) { "RD_CHALLENGE_CONTEXT" }
        require(RemoteCrypto.decode(challenge.string("nonce"), 32).size == 32) { "RD_CHALLENGE_NONCE" }
        UUID.fromString(challenge.string("challenge_id"))
        val issuedAt = Instant.parse(payload.string("issued_at"))
        require(issuedAt.isAfter(Instant.now().minusSeconds(35)) && !issuedAt.isAfter(Instant.now().plusSeconds(5)) && deadline.isAfter(issuedAt)) { "RD_CLOCK_SKEW" }
        return payload
    }
    private fun installToken(value: JsonObject) {
        val expiry = Instant.parse(value.string("expires_at"))
        require(expiry.isAfter(Instant.now()) && expiry.isBefore(Instant.now().plusSeconds(610))) { "RD_TOKEN_EXPIRY" }
        val nonce = value.string("dpop_nonce").also { require(it.length in 1..256) }
        token = RemoteToken(value.string("token").also { require(it.length in 16..4096) }, expiry, nonce)
    }
    suspend fun accountEndpoints(): JsonObject = parentRequest("GET", "rd/endpoints", null)
    suspend fun endpoints(): JsonObject = request("GET", "rd/endpoints")
    suspend fun grants(): JsonObject = request("GET", "rd/grants")
    suspend fun redeemAssist(deviceId: String, temporaryPassword: String): JsonObject {
        require(Regex("[0-9]{9}").matches(deviceId) && temporaryPassword.length in 1..128)
        return request("POST", "rd/assist-invites/redeem", buildJsonObject {
            put("device_id", deviceId); put("temporary_password", temporaryPassword)
        })
    }
    suspend fun redeemFixed(deviceId: String, password: String): JsonObject {
        require(Regex("[0-9]{9}").matches(deviceId) && password.length in 1..128)
        return request("POST", "rd/access/fixed/redeem", buildJsonObject {
            put("device_id", deviceId); put("password", password)
        })
    }
    suspend fun requestAccess(deviceId: String): JsonObject {
        require(Regex("[0-9]{9}").matches(deviceId))
        return request("POST", "rd/access/requests", buildJsonObject { put("device_id", deviceId) })
    }
    suspend fun accessRequest(id: String): JsonObject = request("GET", "rd/access/requests/${uuid(id)}")
    suspend fun pairing(host: String, permissions: Set<String>, requestId: String, nonce: String, assistInviteId: String? = null,
        mode: String = "one_session"): JsonObject {
        require(mode in setOf("one_session", "persistent") && (mode != "persistent" || assistInviteId == null))
        return request("POST", "rd/pairings", buildJsonObject {
            put("host_endpoint_id", uuid(host)); put("session_request_id", uuid(requestId))
            put("permissions", JsonArray(permissions.sorted().map(::JsonPrimitive)))
            put("mode", mode); put("nonce_controller", nonce)
            if (assistInviteId != null) put("assist_invite_id", uuid(assistInviteId))
        })
    }
    suspend fun pairing(id: String): JsonObject = request("GET", "rd/pairings/${uuid(id)}")
    suspend fun confirmPairing(id: String, proof: String): JsonObject = request("POST", "rd/pairings/${uuid(id)}/confirm", buildJsonObject { put("signed_proof", proof) })
    suspend fun rejectPairing(id: String): JsonObject = request("POST", "rd/pairings/${uuid(id)}/reject", buildJsonObject { })
    suspend fun createSession(host: String, grant: String, permissions: Set<String>, requestId: String, displayId: String): JsonObject = request("POST", "rd/sessions", buildJsonObject {
        put("host_endpoint_id", uuid(host)); put("grant_id", uuid(grant))
        put("permissions", JsonArray(permissions.sorted().map(::JsonPrimitive)))
        put("display_id", displayId.also { require(it.isNotBlank() && it.length <= 128) })
        put("protocol", buildJsonObject { put("major", 1); put("minor", 0) }); put("quality", "balanced")
    }, idempotency = uuid(requestId))
    suspend fun session(id: String): JsonObject = request("GET", "rd/sessions/${uuid(id)}")
    suspend fun closeSession(id: String): JsonObject = request("POST", "rd/sessions/${uuid(id)}/close", buildJsonObject { put("reason", "user_closed") })
    suspend fun reportReady(id: String, epoch: Long, version: Long): JsonObject = request("POST", "rd/sessions/${uuid(id)}/report", buildJsonObject {
        put("phase", "ready"); put("connection_epoch", epoch); put("expected_version", version); put("path_verified", true)
    })
    suspend fun signalTicket(purpose: String = "connect"): JsonObject {
        require(purpose in setOf("connect", "reauth"))
        if (purpose == "reauth") ensureToken(force = true)
        return request("POST", "rd/signal-tickets", buildJsonObject { put("purpose", purpose) })
    }

    private suspend fun ensureToken(force: Boolean = false): RemoteToken {
        val observed = requireNotNull(token) { "RD_AUTH_REQUIRED" }
        if (!force && observed.expiresAt.isAfter(Instant.now().plusSeconds(30))) return observed
        return tokenMutex.withLock {
            val current = requireNotNull(token) { "RD_AUTH_REQUIRED" }
            if (current !== observed && current.expiresAt.isAfter(Instant.now().plusSeconds(30))) return@withLock current
            restore(requireNotNull(identity), requireNotNull(endpointId), requireNotNull(instance))
            requireNotNull(token)
        }
    }

    private suspend fun request(method: String, path: String, body: JsonObject? = null, authenticated: Boolean = true, idempotency: String? = null, responseMaximum: Int = RemoteJson.MAX_BYTES): JsonObject = withContext(Dispatchers.IO) {
        val expectedGeneration = generation
        require(path.startsWith("rd/") || !authenticated && path == "public/capabilities")
        require(!path.contains("..") && !path.contains('?') && !path.contains('#'))
        val url = requireNotNull(baseUrl.resolve(path))
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("X-Request-Id", UUID.randomUUID().toString())
        val current = if (authenticated) ensureToken() else null
        if (current != null) {
            check(current.expiresAt.isAfter(Instant.now().plusSeconds(5))) { "RD_AUTH_EXPIRED" }
            builder.header("Authorization", "DPoP ${current.value}")
                .header("DPoP", RemoteCrypto.dpop(requireNotNull(identity), method, url.toString(), current.value, current.nonce))
        }
        idempotency?.let { builder.header("Idempotency-Key", it) }
        val bytes = body?.toString()?.toByteArray()
        require(bytes == null || bytes.size <= 16 * 1024)
        if (method == "GET") builder.get() else builder.method(method, (bytes ?: byteArrayOf()).toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        synchronized(this@RemoteApi) {
            check(expectedGeneration == generation) { "RD_ACCOUNT_CHANGED" }
            activeCalls += call
        }
        try { call.execute().use { response ->
            val output = ByteArrayOutputStream()
            response.body?.byteStream()?.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= responseMaximum) { "RD_RESPONSE_TOO_LARGE" }
                    output.write(buffer, 0, size)
                }
            }
            val data = if (output.size() == 0) buildJsonObject { } else RemoteJson.parse(output.toByteArray(), responseMaximum)
            check(expectedGeneration == generation) { "RD_ACCOUNT_CHANGED" }
            if (!response.isSuccessful) {
                val code = data["error_code"]?.jsonPrimitive?.content?.takeIf { Regex("[A-Z0-9_]{1,80}").matches(it) } ?: "RD_HTTP_${response.code}"
                throw RemoteApiException(code, response.code)
            }
            response.header("DPoP-Nonce")?.takeIf { it.length in 1..256 }?.let { current?.nonce = it }
            data
        } } finally { synchronized(this@RemoteApi) { activeCalls -= call } }
    }
    @Synchronized fun clear() {
        generation++; token = null; identity = null; instance = null; endpointId = null
        activeCalls.forEach { it.cancel() }; activeCalls.clear()
    }
    private fun uuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }
}

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
