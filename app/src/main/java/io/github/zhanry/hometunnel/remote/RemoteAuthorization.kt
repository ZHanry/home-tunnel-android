package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values captured from the local account, selected host, pairing and session request. */
data class RemoteAuthorizationBinding(
    val issuer: String,
    val serverInstance: String,
    val restoreEpoch: Long,
    val sessionId: String,
    val sessionRequestId: String,
    val connectionEpoch: Long,
    val ownerUserId: String,
    val controllerEndpointId: String,
    val hostEndpointId: String,
    val controllerJkt: String,
    val hostJkt: String,
    val permissions: Set<String>,
    val grantId: String,
    val grantMode: String = "one_session",
) {
    init {
        val uri = java.net.URI(issuer)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        listOf(serverInstance, sessionId, sessionRequestId, ownerUserId, controllerEndpointId, hostEndpointId, grantId).forEach(::remoteUuid)
        require(restoreEpoch in 1..Int.MAX_VALUE.toLong() && connectionEpoch in 1..0xffffffffL)
        require(listOf(controllerJkt, hostJkt).all { RemoteCrypto.decode(it, 32).size == 32 })
        require("view" in permissions && P.permissions.containsAll(permissions))
        require(grantMode in setOf("one_session", "persistent"))
    }
}

data class RemoteVerifiedAuthorization(val ticket: JsonObject, val lease: JsonObject, val grant: JsonObject, val remainingLeaseMs: Long)

/** Kotlin and the native engine independently verify authority before opening any local gate. */
class RemoteAuthorization(private val binding: RemoteAuthorizationBinding, private val trustedKeys: JsonObject) {
    private val identityFields = mapOf(
        "iss" to JsonPrimitive(binding.issuer), "server_instance_id" to JsonPrimitive(binding.serverInstance),
        "restore_epoch" to JsonPrimitive(binding.restoreEpoch), "session_id" to JsonPrimitive(binding.sessionId),
        "session_request_id" to JsonPrimitive(binding.sessionRequestId), "connection_epoch" to JsonPrimitive(binding.connectionEpoch),
        "owner_user_id" to JsonPrimitive(binding.ownerUserId), "controller_endpoint_id" to JsonPrimitive(binding.controllerEndpointId),
        "host_endpoint_id" to JsonPrimitive(binding.hostEndpointId), "controller_jkt" to JsonPrimitive(binding.controllerJkt),
        "host_jkt" to JsonPrimitive(binding.hostJkt), "grant_id" to JsonPrimitive(binding.grantId),
    )

    fun authorize(snapshot: JsonObject, now: Instant = Instant.now()): RemoteVerifiedAuthorization {
        require(snapshot["session_id"] == JsonPrimitive(binding.sessionId)) { "RD_SESSION_ID_MISMATCH" }
        require(snapshot["session_request_id"] == JsonPrimitive(binding.sessionRequestId)) { "RD_SESSION_REQUEST_MISMATCH" }
        require(snapshot["connection_epoch"] == JsonPrimitive(binding.connectionEpoch)) { "RD_SESSION_EPOCH_MISMATCH" }
        require(snapshot["host_endpoint_id"] == JsonPrimitive(binding.hostEndpointId)) { "RD_SESSION_HOST_MISMATCH" }
        require(snapshot["controller_endpoint_id"] == JsonPrimitive(binding.controllerEndpointId)) { "RD_SESSION_CONTROLLER_MISMATCH" }
        require(permissions(snapshot, "permissions") == binding.permissions) { "RD_SESSION_SCOPE_MISMATCH" }
        val hostKey = snapshot.getValue("host_public_jwk").jsonObject
        require(RemoteCrypto.thumbprint(hostKey) == binding.hostJkt &&
            RemoteCrypto.thumbprint(snapshot.getValue("controller_public_jwk").jsonObject) == binding.controllerJkt) { "RD_PEER_IDENTITY_MISMATCH" }
        val ticket = ticket(snapshot.string("ticket_jws"), now)
        val grant = grant(snapshot.string("grant_jws"), hostKey, ticket, now)
        val lease = lease(snapshot.string("lease_jws"), ticket, now)
        if (grant["expires_at"] != JsonNull) require(Instant.parse(grant.string("expires_at")).epochSecond >= lease.number("exp")) { "RD_GRANT_EXPIRED" }
        return RemoteVerifiedAuthorization(ticket, lease, grant, remainingLeaseMs(lease, now))
    }

    /** Full independently verifiable native context; a caller-supplied trusted boolean is never accepted. */
    fun nativeContext(snapshot: JsonObject, now: Instant = Instant.now(), stunUrls: List<String> = emptyList()): ByteArray {
        val verified = authorize(snapshot, now)
        require(stunUrls.size <= 4 && stunUrls.all(RemoteConnectionPolicy::validStun)) { "RD_PATH_REJECTED" }
        val nativeKeys = JsonObject(trustedKeys.filterKeys { it != "rotation_proofs" } + ("rotation_proofs" to JsonArray(emptyList())))
        val context = buildJsonObject {
            put("stun_urls", JsonArray(stunUrls.map(::JsonPrimitive)))
            put("session_id", binding.sessionId); put("connection_epoch", binding.connectionEpoch)
            put("session_request_id", binding.sessionRequestId); put("grant_id", binding.grantId)
            put("origin", binding.issuer); put("owner_user_id", binding.ownerUserId)
            put("host_endpoint_id", binding.hostEndpointId); put("controller_endpoint_id", binding.controllerEndpointId)
            put("restore_epoch", binding.restoreEpoch)
            put("grant_version", verified.ticket.getValue("grant_version"))
            put("user_token_version", verified.ticket.getValue("user_token_version"))
            put("local_permissions", JsonArray(binding.permissions.sorted().map(::JsonPrimitive)))
            put("local_grant_revoked", false)
            put("host_public_jwk", snapshot.getValue("host_public_jwk"))
            put("controller_public_jwk", snapshot.getValue("controller_public_jwk"))
            put("initial_trust_pin", nativeKeys); put("server_keyset", nativeKeys)
            for (name in listOf("ticket_jws", "lease_jws", "grant_jws")) put(name, snapshot.getValue(name))
        }.toString().toByteArray()
        require(context.size in 1..64 * 1024) { "RD_AUTHORIZATION_TOO_LARGE" }
        return context
    }

    fun ticket(compact: String, now: Instant = Instant.now()): JsonObject = serverClaims(compact, false, now)

    fun lease(compact: String, ticket: JsonObject, now: Instant = Instant.now()): JsonObject {
        val claims = serverClaims(compact, true, now)
        require(listOf("grant_id", "grant_version", "user_token_version").all { claims[it] == ticket[it] }) { "RD_GRANT_CHANGED" }
        return claims
    }

    fun grant(compact: String, hostKey: JsonObject, ticket: JsonObject, now: Instant = Instant.now()): JsonObject {
        require(RemoteCrypto.thumbprint(hostKey) == binding.hostJkt) { "RD_PEER_IDENTITY_MISMATCH" }
        val claims = RemoteCrypto.verifyJws(compact, "ht-rd-grant+jwt", hostKey)
        require(claims.keys == setOf("id", "server_instance_id", "owner_user_id", "host_endpoint_id", "controller_endpoint_id", "host_jkt", "controller_jkt", "scope", "mode", "one_session_request_id", "grant_version", "expires_at")) { "RD_GRANT_FORMAT" }
        require(listOf("server_instance_id", "owner_user_id", "host_endpoint_id", "controller_endpoint_id", "host_jkt", "controller_jkt").all { claims[it] == identityFields[it] } &&
            claims["id"] == JsonPrimitive(binding.grantId) && claims["grant_version"] == ticket["grant_version"] &&
            claims["mode"] == JsonPrimitive(binding.grantMode) &&
            permissions(claims, "scope").containsAll(binding.permissions)) { "RD_GRANT_CHANGED" }
        when (claims.string("mode")) {
            "one_session" -> require(claims["one_session_request_id"] == JsonPrimitive(binding.sessionRequestId)) { "RD_GRANT_CHANGED" }
            "persistent" -> require(claims["one_session_request_id"] == JsonNull) { "RD_GRANT_CHANGED" }
            else -> error("RD_GRANT_CHANGED")
        }
        if (claims["expires_at"] != JsonNull) {
            val expiry = Instant.parse(claims.string("expires_at"))
            require(expiry.isAfter(now) && expiry.epochSecond >= ticket.number("exp")) { "RD_GRANT_EXPIRED" }
        }
        return claims
    }

    private fun serverClaims(compact: String, lease: Boolean, now: Instant): JsonObject {
        require(compact.length <= RemoteJson.MAX_BYTES && compact.count { it == '.' } == 2) { "RD_JWS_FORMAT" }
        val header = RemoteJson.parse(RemoteCrypto.decode(compact.substringBefore('.'), 2048), 2048)
        val kid = header.string("kid")
        require(trustedKeys["server_instance_id"] == JsonPrimitive(binding.serverInstance) && trustedKeys["restore_epoch"] == JsonPrimitive(binding.restoreEpoch)) { "RD_SERVER_TRUST_CHANGED" }
        val key = (trustedKeys.getValue("keys") as JsonArray).map { it.jsonObject }.single { it["kid"] == JsonPrimitive(kid) }
        require(key["alg"] == JsonPrimitive("ES256") && RemoteCrypto.thumbprint(key.getValue("public_jwk").jsonObject) == kid) { "RD_JWS_KID" }
        val claims = RemoteCrypto.verifyJws(compact, if (lease) "ht-rd-lease+jwt" else "ht-rd-ticket+jwt", key.getValue("public_jwk").jsonObject, kid)
        val required = identityFields.keys + setOf("aud", "jti", "permissions", "grant_version", "user_token_version", "iat", "nbf", "exp") + if (lease) setOf("lease_seq") else emptySet()
        require(claims.keys == required && identityFields.all { (name, value) -> claims[name] == value } &&
            claims["aud"] == JsonPrimitive(if (lease) "ht-rd-use" else "ht-rd-start") && permissions(claims, "permissions") == binding.permissions) { "RD_AUTHORIZATION_CONTEXT" }
        remoteUuid(claims.string("jti"))
        claims.number("grant_version", Int.MAX_VALUE.toLong()); claims.number("user_token_version", Int.MAX_VALUE.toLong())
        if (lease) claims.number("lease_seq")
        val issued = claims.number("iat"); val before = claims.number("nbf"); val expiry = claims.number("exp")
        val ttl = if (lease) 900 else 60
        require(issued <= now.epochSecond + 30 && before == issued && expiry > now.epochSecond && expiry > issued && expiry - issued <= ttl) { "RD_AUTHORIZATION_EXPIRED" }
        val keyStart = Instant.parse(key.string("not_before")); val keyEnd = Instant.parse(key.string("not_after"))
        require(!now.isBefore(keyStart) && !Instant.ofEpochSecond(issued).isBefore(keyStart) && now.isBefore(keyEnd) && !Instant.ofEpochSecond(expiry).isAfter(keyEnd)) { "RD_KEYSET_EXPIRED" }
        return claims
    }

    companion object {
        fun remainingLeaseMs(lease: JsonObject, now: Instant): Long = minOf(
            Math.subtractExact(Math.multiplyExact(lease.number("exp"), 1000), now.toEpochMilli()),
            Math.multiplyExact(lease.number("exp") - lease.number("iat"), 1000),
        ).also { require(it in 1..900_000) { "RD_LEASE_EXPIRED" } }

        private fun permissions(value: JsonObject, field: String): Set<String> {
            val list = (value.getValue(field) as JsonArray).map { item ->
                item.jsonPrimitive.also { require(it.isString) }.content
            }
            return list.toSet().also { require(it.size == list.size && "view" in it && P.permissions.containsAll(it)) { "RD_SCOPE_DENIED" } }
        }
    }
}

internal fun remoteUuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) { "RD_UUID" } }
internal fun JsonObject.number(field: String, maximum: Long = 9_007_199_254_740_991): Long {
    val value = getValue(field).jsonPrimitive
    require(!value.isString)
    return requireNotNull(value.longOrNull).also { require(it in 1..maximum) { "RD_NUMBER" } }
}
internal fun JsonObject.nonNegativeNumber(field: String, maximum: Long): Long {
    val value = getValue(field).jsonPrimitive
    require(!value.isString)
    return requireNotNull(value.longOrNull).also { require(it in 0..maximum) { "RD_NUMBER" } }
}
