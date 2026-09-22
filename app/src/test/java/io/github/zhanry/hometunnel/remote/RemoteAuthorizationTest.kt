package io.github.zhanry.hometunnel.remote

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RemoteAuthorizationTest {
    private val vectors = RemoteJson.parse(requireNotNull(javaClass.getResourceAsStream("/remote-authorization-vectors.json")).readBytes())
    private val expected = vectors.getValue("expected_binding").jsonObject
    private val valid = vectors.getValue("valid").jsonObject
    private val keys = vectors.getValue("keyset").jsonObject
    private val now = Instant.ofEpochSecond(vectors.number("reference_time_unix"))
    private val binding = RemoteAuthorizationBinding(
        expected.string("iss"), expected.string("server_instance_id"), expected.number("restore_epoch"), expected.string("session_id"),
        expected.string("session_request_id"), expected.number("connection_epoch"), expected.string("owner_user_id"), expected.string("controller_endpoint_id"),
        expected.string("host_endpoint_id"), expected.string("controller_jkt"), expected.string("host_jkt"),
        (expected.getValue("permissions") as JsonArray).map { it.jsonPrimitive.content }.toSet(), expected.string("grant_id"),
    )
    private fun compact(value: JsonObject): String = (value.getValue("jws_parts") as JsonArray).joinToString(".") { it.jsonPrimitive.content }
    private fun signed(kind: String): String = compact(valid.getValue(kind).jsonObject)
    private fun claims(kind: String): JsonObject = valid.getValue(kind).jsonObject.getValue("claims").jsonObject
    private fun snapshot(): JsonObject = JsonObject(expected.filterKeys { it in setOf("session_id", "session_request_id", "connection_epoch", "controller_endpoint_id", "host_endpoint_id", "permissions") } + mapOf(
        "ticket_jws" to JsonPrimitive(signed("ticket")), "lease_jws" to JsonPrimitive(signed("lease")), "grant_jws" to JsonPrimitive(signed("grant")),
        "host_public_jwk" to vectors.getValue("identities").jsonObject.getValue("host").jsonObject.getValue("public_jwk"),
        "controller_public_jwk" to vectors.getValue("identities").jsonObject.getValue("controller").jsonObject.getValue("public_jwk"),
    ))
    @Test fun `public shared ticket lease and host grant jointly authorize the exact local request`() {
        val result = RemoteAuthorization(binding, keys).authorize(snapshot(), now)
        assertEquals(claims("ticket"), result.ticket); assertEquals(claims("lease"), result.lease); assertEquals(claims("grant"), result.grant)
        assertEquals(900_000L, result.remainingLeaseMs)
        assertTrue(RemoteKeyset.advance(null, keys, now).anchor.isNotEmpty())
    }
    @Test fun `native context retains signed authority and local pins instead of an authorization boolean`() {
        val context = RemoteJson.parse(RemoteAuthorization(binding, keys).nativeContext(snapshot(), now))
        for (kind in listOf("ticket", "lease", "grant")) assertEquals(signed(kind), context.string("${kind}_jws"))
        assertEquals(keys, context.getValue("initial_trust_pin")); assertEquals(keys, context.getValue("server_keyset"))
        assertEquals(binding.issuer, context.string("origin"))
        assertEquals(binding.sessionRequestId, context.string("session_request_id"))
        assertEquals(claims("ticket").getValue("grant_version"), context.getValue("grant_version"))
        assertTrue("authorized" !in context && "verified" !in context)
        val corrupted = JsonObject(snapshot() + ("grant_jws" to JsonPrimitive(signed("ticket"))))
        assertFails { RemoteAuthorization(binding, keys).nativeContext(corrupted, now) }
    }
    @Test fun `validly signed shared negative vectors cannot cross authorization boundaries`() {
        val verifier = RemoteAuthorization(binding, keys)
        val cases = vectors.getValue("rejected") as JsonArray
        assertTrue(cases.size >= 8)
        cases.forEach { item ->
            val vector = item.jsonObject
            assertFails(vector.string("name")) {
                if (vector.string("kind") == "ticket") verifier.ticket(compact(vector), now)
                else verifier.lease(compact(vector), claims("ticket"), now)
            }
        }
    }
    @Test fun `server signed tickets still require local account endpoint request and permission binding`() {
        val changed = listOf(
            binding.copy(issuer = "https://other.example.test"), binding.copy(serverInstance = "a0000000-0000-4000-8000-000000000002"),
            binding.copy(restoreEpoch = 3), binding.copy(sessionId = "b0000000-0000-4000-8000-000000000002"),
            binding.copy(sessionRequestId = "c0000000-0000-4000-8000-000000000002"), binding.copy(connectionEpoch = 4),
            binding.copy(ownerUserId = "d0000000-0000-4000-8000-000000000002"), binding.copy(controllerEndpointId = "e0000000-0000-4000-8000-000000000002"),
            binding.copy(hostEndpointId = "f0000000-0000-4000-8000-000000000002"), binding.copy(controllerJkt = binding.hostJkt), binding.copy(hostJkt = binding.controllerJkt),
            binding.copy(grantId = "a1000000-0000-4000-8000-000000000002"), binding.copy(permissions = setOf("view")),
        )
        changed.forEach { assertFails { RemoteAuthorization(it, keys).ticket(signed("ticket"), now) } }
        val snapshot = snapshot()
        assertFails { RemoteAuthorization(binding, keys).authorize(JsonObject(snapshot + ("session_request_id" to JsonPrimitive("c0000000-0000-4000-8000-000000000002"))), now) }
        assertFails { RemoteAuthorization(binding, keys).authorize(JsonObject(snapshot + ("host_public_jwk" to snapshot.getValue("controller_public_jwk"))), now) }
    }
    @Test fun `authorization enforces expiry clock skew signer intervals and renewal version binding`() {
        val verifier = RemoteAuthorization(binding, keys)
        assertFails { verifier.ticket(signed("ticket"), now.plusSeconds(60)) }
        assertFails { verifier.ticket(signed("ticket"), now.minusSeconds(31)) }
        assertFails { verifier.lease(signed("lease"), JsonObject(claims("ticket") + ("user_token_version" to JsonPrimitive(6))), now) }
        val key = (keys.getValue("keys") as JsonArray).single().jsonObject
        for (change in listOf("not_before" to now.plusSeconds(1), "not_after" to now.plusSeconds(59))) {
            val changed = JsonObject(key + (change.first to JsonPrimitive(change.second.toString())))
            assertFails { RemoteAuthorization(binding, JsonObject(keys + ("keys" to JsonArray(listOf(changed))))).ticket(signed("ticket"), now) }
        }
        val notYetValid = JsonObject(key + ("not_before" to JsonPrimitive(now.minusSeconds(10).toString())))
        assertFails { RemoteAuthorization(binding, JsonObject(keys + ("keys" to JsonArray(listOf(notYetValid))))).ticket(signed("ticket"), now.minusSeconds(20)) }
        val pieces = signed("ticket").split('.').toMutableList()
        pieces[2] = RemoteCrypto.base64(RemoteCrypto.decode(pieces[2]).also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertFails { verifier.ticket(pieces.joinToString("."), now) }
    }
    @Test fun `host signed grants cannot substitute another request account scope or expiration`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val host = object : RemoteIdentity {
            override val publicJwk = RemoteCrypto.jwk(pair.public as ECPublicKey)
            override fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
        }
        val verifier = RemoteAuthorization(binding.copy(hostJkt = RemoteCrypto.thumbprint(host.publicJwk)), keys)
        val good = JsonObject(claims("grant") + ("host_jkt" to JsonPrimitive(RemoteCrypto.thumbprint(host.publicJwk))))
        fun verify(value: JsonObject) = verifier.grant(RemoteCrypto.signJws(host, "ht-rd-grant+jwt", value), host.publicJwk, claims("ticket"), now)
        assertEquals(good, verify(good))
        val changes = listOf(
            "one_session_request_id" to JsonPrimitive("c0000000-0000-4000-8000-000000000002"), "one_session_request_id" to JsonNull,
            "owner_user_id" to JsonPrimitive("d0000000-0000-4000-8000-000000000002"), "scope" to JsonArray(listOf(JsonPrimitive("view"))),
            "grant_version" to JsonPrimitive(3), "controller_jkt" to JsonPrimitive(binding.hostJkt), "mode" to JsonPrimitive("unknown"),
            "expires_at" to JsonPrimitive(now.toString()), "expires_at" to JsonPrimitive(now.plusSeconds(59).toString()), "expires_at" to JsonPrimitive("not-a-date"),
        )
        changes.forEach { assertFails(it.first) { verify(JsonObject(good + it)) } }
        assertFails { verify(JsonObject(good + ("mode" to JsonPrimitive("persistent")))) }
        val persistent = JsonObject(good + mapOf("mode" to JsonPrimitive("persistent"), "one_session_request_id" to JsonNull, "expires_at" to JsonNull))
        assertFails { verify(persistent) }
        val persistentVerifier = RemoteAuthorization(binding.copy(hostJkt = RemoteCrypto.thumbprint(host.publicJwk), grantMode = "persistent"), keys)
        assertEquals(persistent, persistentVerifier.grant(RemoteCrypto.signJws(host, "ht-rd-grant+jwt", persistent), host.publicJwk, claims("ticket"), now))
    }
}
