package io.github.zhanry.hometunnel.remote

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RemoteKeysetTest {
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val instance = "4b586bc8-c22e-4c79-95f9-103ec6453ad9"
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun key(pair: KeyPair) = buildJsonObject {
        val jwk = RemoteCrypto.jwk(pair.public as ECPublicKey)
        put("kid", RemoteCrypto.thumbprint(jwk)); put("alg", "ES256"); put("public_jwk", jwk)
        put("not_before", now.minusSeconds(3600).toString()); put("not_after", now.plusSeconds(3600).toString())
    }
    private fun set(version: Int, keys: List<JsonObject>) = buildJsonObject {
        put("keyset_version", version); put("active_kid", keys.last().getValue("kid")); put("keys", JsonArray(keys))
    }
    private fun response(set: JsonObject, proofs: List<String> = emptyList(), restore: Int = 1) = buildJsonObject {
        put("server_instance_id", instance); put("restore_epoch", restore)
        set.forEach { (name, value) -> put(name, value) }
        put("rotation_proofs", JsonArray(proofs.map(::JsonPrimitive)))
    }
    private fun proof(signer: KeyPair, from: Int, target: JsonObject, issued: Instant = now): String {
        val kid = RemoteCrypto.thumbprint(RemoteCrypto.jwk(signer.public as ECPublicKey))
        val header = buildJsonObject { put("alg", "ES256"); put("typ", "ht-rd-keyset+jwt"); put("kid", kid) }
        val body = buildJsonObject {
            put("server_instance_id", instance); put("from_version", from); put("to_version", from + 1)
            put("from_kid", kid); put("issued_at", issued.toString()); put("keyset", target)
        }
        val input = "${RemoteCrypto.base64(header.toString().toByteArray())}.${RemoteCrypto.base64(body.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(input.toByteArray()); sign() }
        return "$input.${RemoteCrypto.base64(RemoteCrypto.derToRaw(signature))}"
    }
    @Test fun `pin advances through every authenticated rotation and records restore changes`() {
        val a = pair(); val b = pair(); val c = pair()
        val first = set(1, listOf(key(a))); val second = set(2, listOf(key(a), key(b))); val third = set(3, listOf(key(b), key(c)))
        val pinned = RemoteKeyset.advance(null, response(first), now)
        assertFalse(pinned.restoreChanged)
        val changed = RemoteKeyset.advance(pinned.anchor, response(third, listOf(proof(a, 1, second), proof(b, 2, third)), restore = 2), now)
        assertTrue(changed.restoreChanged)
        assertEquals(JsonPrimitive(3), changed.anchor["keyset_version"])
        assertEquals(changed.anchor, RemoteKeyset.advance(changed.anchor, response(third, restore = 2), now).anchor)
    }
    @Test fun `missing forged duplicate and expired rotation links cannot replace a pin`() {
        val a = pair(); val b = pair(); val attacker = pair()
        val first = set(1, listOf(key(a))); val second = set(2, listOf(key(b)))
        val pinned = RemoteKeyset.advance(null, response(first), now).anchor
        assertFails { RemoteKeyset.advance(pinned, response(second), now) }
        assertFails { RemoteKeyset.advance(pinned, response(second, listOf(proof(attacker, 1, second))), now) }
        assertFails { RemoteKeyset.advance(pinned, response(second, listOf(proof(a, 1, second), proof(a, 1, second))), now) }
        assertFails { RemoteKeyset.advance(pinned, response(second, listOf(proof(a, 1, second, now.minusSeconds(3601)))), now) }
        assertFails { RemoteKeyset.advance(pinned, response(second, listOf(proof(a, 1, second, now.plusSeconds(61)))), now) }
    }
    @Test fun `instance restore version and same-version mutations fail closed`() {
        val a = pair(); val b = pair()
        val current = set(2, listOf(key(a)))
        val pinned = RemoteKeyset.advance(null, response(current, restore = 2), now).anchor
        assertFails { RemoteKeyset.advance(pinned, response(current, restore = 1), now) }
        assertFails { RemoteKeyset.advance(pinned, response(set(1, listOf(key(a))), restore = 2), now) }
        assertFails { RemoteKeyset.advance(pinned, response(set(2, listOf(key(b))), restore = 2), now) }
        assertFails { RemoteKeyset.advance(pinned, JsonObject(response(current, restore = 2) + ("server_instance_id" to JsonPrimitive("87a954ca-dc9f-480e-96a9-0469f3205ca6"))), now) }
        assertFails { RemoteKeyset.advance(null, response(current), now.plusSeconds(3601)) }
    }
    @Test fun `public server vectors verify unchanged across Kotlin and the actual offline signer`() {
        val fixture = RemoteJson.parse(requireNotNull(javaClass.getResourceAsStream("/rd-keyset-vectors.json")).readBytes())
        val at = Instant.parse(fixture.string("now"))
        val pinned = RemoteKeyset.advance(null, fixture.getValue("initial").jsonObject, at).anchor
        val once = RemoteKeyset.advance(pinned, fixture.getValue("rotated").jsonObject, at).anchor
        val twice = RemoteKeyset.advance(once, fixture.getValue("rotated_twice").jsonObject, at).anchor
        assertEquals(twice, RemoteKeyset.advance(pinned, fixture.getValue("rotated_twice").jsonObject, at).anchor)
        assertEquals(JsonPrimitive(3), twice["keyset_version"])
        assertFails { RemoteKeyset.advance(twice, fixture.getValue("rotated").jsonObject, at) }
    }
}
