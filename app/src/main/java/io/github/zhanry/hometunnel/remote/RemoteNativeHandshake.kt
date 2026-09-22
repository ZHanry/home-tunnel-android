package io.github.zhanry.hometunnel.remote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Narrow AndroidKeyStore signing boundary. It does not grant native media authority. */
object RemoteNativeHandshake {
    fun peerEnvelope(identity: RemoteIdentity, ticket: JsonObject, sequence: Long, type: String, payload: JsonObject): JsonObject {
        require(type in setOf("peer.offer", "peer.candidates", "peer.candidates_done") && sequence in 1..Long.MAX_VALUE)
        val claims = buildJsonObject {
            put("v", 1); put("type", type); put("session_id", ticket.getValue("session_id"))
            put("connection_epoch", ticket.getValue("connection_epoch"))
            put("from_endpoint_id", ticket.getValue("controller_endpoint_id")); put("to_endpoint_id", ticket.getValue("host_endpoint_id"))
            put("ticket_jti", ticket.getValue("jti")); put("seq", sequence.toString())
            put("created_at", Instant.now().toString()); put("payload", payload)
        }
        return buildJsonObject {
            put("v", 1); put("type", type); put("request_id", UUID.randomUUID().toString())
            put("session_id", ticket.getValue("session_id")); put("connection_epoch", ticket.getValue("connection_epoch"))
            put("payload_jws", RemoteCrypto.signJws(identity, "ht-rd-peer+jwt", claims))
        }
    }

    fun proof(identity: RemoteIdentity, sessionId: String, epoch: Long, ticket: String, offer: String, answer: String, transcript: ByteArray): ByteArray {
        require(transcript.size in 200..256 && epoch in 1..0xffffffffL) { "RD_PROOF_INVALID" }
        val buffer = ByteBuffer.wrap(transcript).order(ByteOrder.BIG_ENDIAN)
        fun field(expectedLength: Int): ByteArray {
            require(buffer.remaining() >= 4 && buffer.int == expectedLength && buffer.remaining() >= expectedLength) { "RD_PROOF_INVALID" }
            return ByteArray(expectedLength).also(buffer::get)
        }
        val domain = "ht-rd-proof-v1".toByteArray(Charsets.US_ASCII)
        require(MessageDigest.isEqual(field(domain.size), domain)) { "RD_PROOF_INVALID" }
        val id = UUID.fromString(sessionId)
        val expectedId = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        require(MessageDigest.isEqual(field(16), expectedId) && buffer.remaining() >= 4 && buffer.int.toLong().and(0xffffffffL) == epoch) { "RD_PROOF_INVALID" }
        field(32); field(32) // Native-generated controller nonce and verified host nonce.
        for (value in listOf(offer, answer, ticket)) require(MessageDigest.isEqual(field(32), RemoteCrypto.sha256(value.toByteArray()))) { "RD_PROOF_INVALID" }
        require(!buffer.hasRemaining()) { "RD_PROOF_INVALID" }
        return RemoteCrypto.derToRaw(identity.sign(transcript))
    }
}
