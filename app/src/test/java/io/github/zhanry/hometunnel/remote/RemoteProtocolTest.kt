package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RemoteProtocolTest {
    private fun identity(): RemoteIdentity {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return object : RemoteIdentity {
            override val publicJwk = RemoteCrypto.jwk(key.public as ECPublicKey)
            override fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }
    }
    @Test fun `strict parser rejects duplicate escaped names and invalid wire data`() {
        for (text in listOf("{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"x\":1e999}", "{\"x\":9223372036854775808}", "{\"x\":NaN}", "{\"x\":\"\\ud800\"}", "{\"x\":1,}", "{} {}")) {
            assertFails(text) { RemoteJson.parse(text.toByteArray()) }
        }
        assertFails { RemoteJson.parse(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc0.toByte(), 0xaf.toByte(), 34, 125)) }
        assertFails { RemoteJson.parse("{}".toByteArray(), 1) }
        assertEquals(JsonPrimitive("汉字😀"), RemoteJson.parse("{\"text\":\"汉字😀\"}".toByteArray())["text"])
    }
    @Test fun `ES256 signatures use raw JOSE encoding and reject tamper or alternate key`() {
        val signer = identity()
        val payload = buildJsonObject { put("purpose", "enrollment"); put("nonce", "example") }
        val signed = RemoteCrypto.signJws(signer, "ht-rd-proof+jwt", payload)
        assertEquals(64, RemoteCrypto.decode(signed.split('.')[2]).size)
        assertEquals(payload, RemoteCrypto.verifyJws(signed, "ht-rd-proof+jwt", signer.publicJwk))
        assertFails { RemoteCrypto.verifyJws(signed, "wrong-type", signer.publicJwk) }
        assertFails { RemoteCrypto.verifyJws(signed, "ht-rd-proof+jwt", identity().publicJwk) }
        val parts = signed.split('.').toMutableList()
        parts[1] = RemoteCrypto.base64("{}".toByteArray())
        assertFails { RemoteCrypto.verifyJws(parts.joinToString("."), "ht-rd-proof+jwt", signer.publicJwk) }
        assertFails { RemoteCrypto.rawToDer(ByteArray(64)) }
        assertFails { RemoteCrypto.derToRaw(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 0)) }
        val raw = RemoteCrypto.decode(signed.split('.')[2])
        assertContentEquals(raw, RemoteCrypto.derToRaw(RemoteCrypto.rawToDer(raw)))
    }
    @Test fun `JWS rejects critical extensions and duplicate headers before using signature`() {
        val signer = identity()
        val header = RemoteCrypto.base64("{\"alg\":\"ES256\",\"typ\":\"x\",\"jku\":\"https://untrusted.example\"}".toByteArray())
        assertFails { RemoteCrypto.verifyJws("$header.e30.${RemoteCrypto.base64(ByteArray(64) { 1 })}", "x", signer.publicJwk) }
        assertFails { RemoteCrypto.publicKey(JsonObject(signer.publicJwk + ("d" to JsonPrimitive("secret")))) }
        assertFails { RemoteCrypto.publicKey(buildJsonObject { put("kty", "EC"); put("crv", "P-256"); put("x", RemoteCrypto.base64(ByteArray(32))); put("y", RemoteCrypto.base64(ByteArray(32))) }) }
    }
    @Test fun `DPoP binds token method origin path and a fresh proof identifier`() {
        val signer = identity()
        val first = RemoteCrypto.dpop(signer, "POST", "https://home.example/api/v1/rd/sessions?x=1", "a-token", "a-nonce", Instant.ofEpochSecond(1234))
        val second = RemoteCrypto.dpop(signer, "POST", "https://home.example/api/v1/rd/sessions?x=1", "a-token", "a-nonce", Instant.ofEpochSecond(1234))
        val claims = RemoteJson.parse(RemoteCrypto.decode(first.split('.')[1]))
        assertEquals(JsonPrimitive("https://home.example/api/v1/rd/sessions"), claims["htu"])
        assertEquals(JsonPrimitive("POST"), claims["htm"])
        assertEquals(JsonPrimitive(RemoteCrypto.base64(RemoteCrypto.sha256("a-token".toByteArray()))), claims["ath"])
        assertNotEquals(claims["jti"], RemoteJson.parse(RemoteCrypto.decode(second.split('.')[1]))["jti"])
        assertFails { RemoteCrypto.dpop(signer, "POST", "http://home.example/", "token", "nonce") }
    }
    @Test fun `proof transcript and key message match the shared server vectors`() {
        val vectors = RemoteJson.parse(requireNotNull(javaClass.getResourceAsStream("/remote-test-vectors.json")).readBytes())
        val proof = vectors.getValue("proof").jsonObject
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val transcript = RemoteCrypto.transcript(UUID.fromString(proof.string("session_id")), 3,
            hex(proof.string("controller_nonce")), hex(proof.string("host_nonce")), hex(proof.string("offer_jws_sha256")),
            hex(proof.string("answer_jws_sha256")), hex(proof.string("ticket_sha256")))
        assertContentEquals(hex(proof.string("transcript_hex")), transcript)
        assertContentEquals(hex(proof.string("sha256")), RemoteCrypto.sha256(transcript))
        val signature = hex(proof.string("signature_raw64_hex"))
        assertTrue(RemoteCrypto.verifyTranscript(proof.getValue("public_jwk").jsonObject, transcript, signature))
        val changed = transcript.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(RemoteCrypto.verifyTranscript(proof.getValue("public_jwk").jsonObject, changed, signature))
        assertContentEquals(hex(vectors.getValue("key_down_a").jsonObject.string("wire_hex")), RemoteWire.frame(P.KEY, 3, 7, 1, RemoteWire.keyPayload(4, true)))
    }
    @Test fun `text and coordinate encoding reject truncation letterboxes and sequence wrap`() {
        assertEquals(20 + "中文😀".toByteArray().size, RemoteWire.textPayload("中文😀").size)
        assertFails { RemoteWire.textPayload("\uD800") }
        assertFails { RemoteWire.textPayload("x".repeat(4097)) }
        assertFails { RemoteWire.frame(P.KEY, 1, 1, P.SEQUENCE_RECONNECT_AT, RemoteWire.keyPayload(4, true)) }
        assertNull(RemoteWire.point(20f, 5f, 200, 200, 200, 100))
        assertNull(RemoteWire.point(Float.NaN, 50f, 200, 200, 200, 100))
        assertEquals(0 to 0, RemoteWire.point(0f, 50f, 200, 200, 200, 100))
    }
    @Test fun `Keystore identity partitions cannot collide through delimiter injection`() {
        assertNotEquals(AndroidRemoteIdentity.keyAlias("server-a", "user-a"), AndroidRemoteIdentity.keyAlias("server-b", "user-a"))
        assertNotEquals(AndroidRemoteIdentity.keyAlias("server-a", "user-a"), AndroidRemoteIdentity.keyAlias("server-a", "user-b"))
        assertNotEquals(AndroidRemoteIdentity.keyAlias("a\nb", "c"), AndroidRemoteIdentity.keyAlias("a", "b\nc"))
    }
    @Test fun `a lease and verified UDP path still require foreground surface and input resync`() {
        var now = 100L
        val gate = RemoteSessionGate { now }
        gate.foreground(true)
        gate.authorized(1, setOf("view", "input.keyboard", "audio.microphone"), 900_000, 1)
        assertFalse(gate.canUse("input.keyboard"))
        assertFails { gate.authenticatedDirectPath(1, "udp", "relay", "host") }
        gate.authenticatedDirectPath(1, "udp", "host", "srflx")
        assertFalse(gate.canUse("input.keyboard"))
        gate.presentedFrame(1, gate.surface(true), 1); gate.displayLayout(1, 1)
        val first = gate.requestInput().string("request_id")
        assertEquals(first, requireNotNull(gate.controlGranted(1, first, 1)).string("request_id"))
        assertTrue(gate.acknowledgeInput(1, first, 1, 1))
        assertTrue(gate.canUse("input.keyboard"))
        assertFalse(gate.canUse("files.send"))
        gate.feature("audio.microphone", true)
        gate.foreground(false)
        assertFalse(gate.featureEnabled("audio.microphone")); assertFalse(gate.canUse("input.keyboard"))
        gate.foreground(true)
        assertFalse(gate.canUse("input.keyboard")); assertFalse(gate.featureEnabled("audio.microphone"))
        val second = gate.requestInput().string("request_id")
        gate.controlGranted(1, second, 2)
        assertTrue(gate.acknowledgeInput(1, second, 2, 1))
        assertTrue(gate.canUse("input.keyboard"))
        now = 900_100
        assertFalse(gate.live()); assertFalse(gate.canUse("view"))
        assertFails { gate.renew(1, 2, 900_000) }
    }
    @Test fun `clock rollback stale renewal and old connection epochs cannot extend control`() {
        var now = 1000L
        val gate = RemoteSessionGate { now }
        gate.authorized(2, setOf("view"), 1000, 3)
        assertFails { gate.renew(2, 3, 900_000) }
        assertFails { gate.authenticatedDirectPath(1, "udp", "host", "host") }
        now = 999
        assertFalse(gate.live())
    }
    @Test fun `input grants and synchronization acknowledgements bind a fresh request and never kill video`() {
        var now = 100L
        val gate = RemoteSessionGate { now }
        gate.foreground(true)
        gate.authorized(3, setOf("view", "input.keyboard"), 900_000, 1)
        gate.presentedFrame(3, gate.surface(true), 1)
        gate.authenticatedDirectPath(3, "udp", "host", "host"); gate.displayLayout(3, 7)
        val first = gate.requestInput().string("request_id")
        assertFalse(gate.acknowledgeInput(3, first, 1, 7)) // No CONTROL_GRANTED / INPUT_STATE.
        assertTrue(gate.canUse("view")); assertFalse(gate.canUse("input.keyboard"))
        var second = gate.requestInput().string("request_id")
        assertNull(gate.controlGranted(3, first, 1)) // Old request cannot grant the new one.
        assertFalse(gate.canUse("input.keyboard")); assertTrue(gate.canUse("view"))
        second = gate.requestInput().string("request_id")
        assertEquals(2L, requireNotNull(gate.controlGranted(3, second, 2)).number("generation"))
        assertFalse(gate.canUse("input.keyboard"))
        gate.releaseInput()
        assertFalse(gate.acknowledgeInput(3, second, 2, 7))
        assertTrue(gate.canUse("view"))
        val third = gate.requestInput().string("request_id")
        gate.controlGranted(3, third, 3); now += 5000
        assertFalse(gate.acknowledgeInput(3, third, 3, 7))
        val fourth = gate.requestInput().string("request_id")
        gate.controlGranted(3, fourth, 4)
        gate.foreground(false); gate.foreground(true)
        assertFalse(gate.acknowledgeInput(3, fourth, 4, 7)); assertTrue(gate.canUse("view"))
        val fifth = gate.requestInput().string("request_id")
        gate.controlGranted(3, fifth, 5); gate.displayLayout(3, 8)
        assertFalse(gate.acknowledgeInput(3, fifth, 5, 7)); assertTrue(gate.canUse("view"))
        val sixth = gate.requestInput().string("request_id")
        gate.controlGranted(3, sixth, 6)
        assertTrue(gate.acknowledgeInput(3, sixth, 6, 8)); assertTrue(gate.canUse("input.keyboard"))
        gate.surface(false); gate.surface(true)
        assertFalse(gate.acknowledgeInput(3, sixth, 6, 8)); assertTrue(gate.canUse("view"))
    }
    @Test fun `surface replacement requires its own frame and rejects queued old callbacks`() {
        val gate = RemoteSessionGate { 100L }
        gate.foreground(true); gate.authorized(1, setOf("view", "input.keyboard"), 900_000, 1)
        gate.authenticatedDirectPath(1, "udp", "host", "host"); gate.displayLayout(1, 1)
        val firstSurface = gate.surface(true)
        assertFalse(gate.connectionEstablished)
        assertFails { gate.requestInput() }
        assertTrue(gate.presentedFrame(1, firstSurface, 20))
        assertTrue(gate.connectionEstablished)
        val request = gate.requestInput().string("request_id")
        gate.controlGranted(1, request, 1); assertTrue(gate.acknowledgeInput(1, request, 1, 1))
        val replacement = gate.surface(true) // Direct replacement with no detach callback.
        assertFalse(gate.canUse("input.keyboard")); assertFalse(gate.firstFramePresented)
        assertTrue(gate.connectionEstablished) // Initial-connect timeout must not close an established session during replacement.
        assertFalse(gate.presentedFrame(1, firstSurface, 21)); assertFails { gate.requestInput() }
        assertFalse(gate.presentedFrame(1, replacement, 0)); assertFails { gate.requestInput() }
        assertTrue(gate.presentedFrame(1, replacement, 1))
        assertFalse(gate.acknowledgeInput(1, request, 1, 1))
        gate.surface(false)
        gate.foreground(false)
        assertTrue(gate.connectionEstablished) // Nor when it happens to be in the background at the original deadline.
        gate.foreground(true)
        assertFalse(gate.presentedFrame(1, replacement, 2))
        val reattached = gate.surface(true)
        assertFalse(gate.presentedFrame(1, replacement, 3)); assertFails { gate.requestInput() }
        assertTrue(gate.presentedFrame(1, reattached, 1))
        assertFalse(gate.presentedFrame(1, reattached, 2))
    }
}
