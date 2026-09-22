package io.github.zhanry.hometunnel.remote

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class RemoteNativeHandshakeTest {
    private val session = UUID.fromString("b0000000-0000-4000-8000-000000000001")
    private val epoch = 0xffffffffL
    private val ticket = "signed.ticket.bytes"
    private val offer = "signed.offer.bytes"
    private val answer = "signed.answer.bytes"

    private class CountingIdentity : RemoteIdentity {
        private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicJwk = RemoteCrypto.jwk(key.public as ECPublicKey)
        var calls = 0
            private set
        var signed: ByteArray? = null
            private set

        override fun sign(bytes: ByteArray): ByteArray {
            calls++
            signed = bytes.copyOf()
            return Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(bytes); sign() }
        }
    }

    private fun transcript(): ByteArray = RemoteCrypto.transcript(
        session, epoch, ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() },
        RemoteCrypto.sha256(offer.toByteArray()), RemoteCrypto.sha256(answer.toByteArray()), RemoteCrypto.sha256(ticket.toByteArray()),
    )

    private fun rejected(bytes: ByteArray = transcript(), sessionId: String = session.toString(), connectionEpoch: Long = epoch,
                         signedTicket: String = ticket, signedOffer: String = offer, signedAnswer: String = answer) {
        val identity = CountingIdentity()
        assertFailsWith<IllegalArgumentException> {
            RemoteNativeHandshake.proof(identity, sessionId, connectionEpoch, signedTicket, signedOffer, signedAnswer, bytes)
        }
        assertEquals(0, identity.calls, "Invalid native transcript must never reach the AndroidKeyStore signing boundary")
        assertEquals(null, identity.signed)
    }

    @Test fun `exact 222 byte transcript is signed once and the proof verifies`() {
        val identity = CountingIdentity()
        val bytes = transcript()
        assertEquals(222, bytes.size)
        val proof = RemoteNativeHandshake.proof(identity, session.toString(), epoch, ticket, offer, answer, bytes)
        assertEquals(1, identity.calls)
        assertContentEquals(bytes, identity.signed)
        assertEquals(64, proof.size)
        assertTrue(RemoteCrypto.verifyTranscript(identity.publicJwk, bytes, proof))
    }

    @Test fun `foreign session epoch domain or signed envelope hashes are rejected before signing`() {
        rejected(sessionId = "b0000000-0000-4000-8000-000000000002")
        rejected(sessionId = "invalid-session")
        rejected(connectionEpoch = epoch - 1)
        rejected(connectionEpoch = 0)
        rejected(connectionEpoch = 0x100000000L)
        rejected(signedOffer = "$offer-tampered")
        rejected(signedAnswer = "$answer-tampered")
        rejected(signedTicket = "$ticket-tampered")
        // Domain, UUID, epoch, offer hash, answer hash, ticket hash, respectively.
        for (offset in listOf(4, 22, 38, 118, 154, 190)) {
            rejected(transcript().also { it[offset] = (it[offset].toInt() xor 1).toByte() })
        }
    }

    @Test fun `every length prefix truncation and trailing bytes are rejected before signing`() {
        // Domain, UUID, controller nonce, host nonce, offer hash, answer hash, ticket hash.
        for (offset in listOf(0, 18, 42, 78, 114, 150, 186)) {
            val original = ByteBuffer.wrap(transcript()).getInt(offset)
            for (length in listOf(-1, 0, original - 1, original + 1, Int.MAX_VALUE)) {
                rejected(transcript().also { ByteBuffer.wrap(it).putInt(offset, length) })
            }
        }
        for (length in listOf(0, 199, 200, 201, 217, 221)) rejected(transcript().copyOf(length))
        for (length in listOf(223, 256, 257)) rejected(transcript().copyOf(length))
    }
}
