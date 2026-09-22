package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RemoteFileOffer(val id: UUID, val name: String, val size: Long) {
    init { RemoteFiles.safeName(name); require(size in 0..P.FILE_BYTES) { "RD_FILE_TOO_LARGE" } }
    fun json(): JsonObject = buildJsonObject { put("id", id.toString()); put("name", name); put("size", size) }
}

data class RemoteFileChunk(val id: UUID, val offset: Long, val data: ByteArray) {
    fun encode(): ByteArray {
        require(offset in 0..P.FILE_BYTES && data.size in 1..P.FILE_CHUNK_BYTES && data.size <= P.FILE_BYTES - offset)
        return ByteBuffer.allocate(24 + data.size).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .putLong(offset).put(data).array()
    }
    companion object {
        fun decode(bytes: ByteArray): RemoteFileChunk {
            require(bytes.size in 25..24 + P.FILE_CHUNK_BYTES) { "RD_FILE_CHUNK" }
            val buffer = ByteBuffer.wrap(bytes)
            val id = UUID(buffer.long, buffer.long)
            val offset = buffer.long
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            require(offset in 0..P.FILE_BYTES && data.size <= P.FILE_BYTES - offset) { "RD_FILE_OFFSET" }
            return RemoteFileChunk(id, offset, data)
        }
    }
}

/** One peer-acknowledged chunk at a time; callers run stream operations off the UI thread. */
class RemoteSendTransfer(val offer: RemoteFileOffer, private val input: InputStream) : Closeable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var accepted = false
    private var offset = 0L
    private var awaitingOffset: Long? = null
    private var finalHash: ByteArray? = null
    private var closed = false
    private var sourceClosed = false
    var completed = false; private set

    fun accept(id: UUID) { check(!closed && !accepted && id == offer.id) { "RD_FILE_ACCEPT" }; accepted = true }
    fun nextChunk(): RemoteFileChunk? {
        check(!closed && accepted && awaitingOffset == null && finalHash == null) { "RD_FILE_ACK_REQUIRED" }
        try {
            if (offset == offer.size) {
                require(input.read() == -1) { "RD_FILE_SIZE_CHANGED" }
                finalHash = digest.digest(); input.close(); sourceClosed = true
                return null
            }
            val bytes = ByteArray(minOf(P.FILE_CHUNK_BYTES.toLong(), offer.size - offset).toInt())
            val count = input.read(bytes)
            require(count > 0) { "RD_FILE_SIZE_CHANGED" }
            val data = bytes.copyOf(count)
            digest.update(data)
            val chunk = RemoteFileChunk(offer.id, offset, data)
            offset += count; awaitingOffset = offset
            return chunk
        } catch (error: Exception) { close(); throw error }
    }
    fun acknowledgeChunk(id: UUID, receivedOffset: Long) {
        check(!closed && id == offer.id && awaitingOffset != null && awaitingOffset == receivedOffset) { "RD_FILE_ACK" }
        awaitingOffset = null
    }
    fun completion(): JsonObject {
        check(!closed && finalHash != null && awaitingOffset == null) { "RD_FILE_INCOMPLETE" }
        return buildJsonObject {
            put("id", offer.id.toString()); put("size", offer.size)
            put("sha256", requireNotNull(finalHash).joinToString("") { "%02x".format(it) })
        }
    }
    fun acknowledgeCompletion(id: UUID, hash: ByteArray) {
        check(!closed && id == offer.id && finalHash != null && MessageDigest.isEqual(hash, finalHash)) { "RD_FILE_ACK" }
        completed = true; close()
    }
    override fun close() {
        if (closed) return
        closed = true; awaitingOffset = null
        if (!sourceClosed) { input.close(); sourceClosed = true }
    }
}

/** Batch limits apply to the complete selection, including files already completed. */
class RemoteTransferBatch(offers: List<RemoteFileOffer>) {
    private val pending = offers.map { it.id }.toMutableSet()
    private val active = mutableSetOf<UUID>()
    init {
        require(offers.size in 1..P.BATCH_FILES && pending.size == offers.size && offers.sumOf { it.size } <= P.BATCH_FILE_BYTES) { "RD_FILE_BATCH_TOO_LARGE" }
    }
    fun start(id: UUID) {
        check(id in pending && active.size < 2 && id !in active) { "RD_FILE_CONCURRENCY" }
        active += id
    }
    fun finish(id: UUID) { check(active.remove(id)); pending.remove(id) }
}
