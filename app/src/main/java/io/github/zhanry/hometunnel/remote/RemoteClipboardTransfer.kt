package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class RemoteClipboardTransfer(
    private val send: (Int, ByteArray) -> Unit,
    private val receiveText: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Outgoing(val id: UUID, val bytes: ByteArray, val digest: String, var accepted: Boolean = false, var offset: Int = 0, val deadline: Long)
    private data class Incoming(val id: UUID, val size: Int, val digest: String, val bytes: ByteArrayOutputStream, val deadline: Long)

    private var readEnabled = false
    private var writeEnabled = false
    private var outgoing: Outgoing? = null
    private var incoming: Incoming? = null
    private var lastDigest: String? = null
    private val seen = ArrayDeque<UUID>()

    fun feature(permission: String, enabled: Boolean) {
        when (permission) {
            "clipboard.read" -> { readEnabled = enabled; if (!enabled) incoming = null }
            "clipboard.write" -> { writeEnabled = enabled; if (!enabled) outgoing = null }
            else -> error("RD_SCOPE_DENIED")
        }
        if (!readEnabled && !writeEnabled) { seen.clear(); lastDigest = null }
    }

    fun reset() {
        readEnabled = false; writeEnabled = false; incoming = null; outgoing = null
        seen.clear(); lastDigest = null
    }

    fun offer(text: String): Boolean {
        check(writeEnabled) { "RD_CLIPBOARD_DISABLED" }
        require(RemoteJson.validUnicode(text) && '\u0000' !in text) { "RD_CLIPBOARD_INVALID" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= P.CLIPBOARD_BYTES) { "RD_CLIPBOARD_TOO_LARGE" }
        val digest = sha256(bytes)
        if (outgoing != null || digest == lastDigest) return false
        val transfer = Outgoing(UUID.randomUUID(), bytes, digest, deadline = now() + 30_000)
        outgoing = transfer
        try {
            send(P.CLIPBOARD_OFFER, json(mapOf(
                "id" to JsonPrimitive(transfer.id.toString()), "size" to JsonPrimitive(bytes.size),
                "sha256" to JsonPrimitive(digest), "mime" to JsonPrimitive("text/plain;charset=utf-8"),
            )))
        } catch (error: Exception) { outgoing = null; throw error }
        return true
    }

    fun receive(frame: ByteArray) {
        require(frame.size in P.HEADER_LENGTH..16384) { "RD_CLIPBOARD_INVALID" }
        val header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        require((header.short.toInt() and 0xffff) == P.MAGIC && (header.get().toInt() and 0xff) == P.VERSION) { "RD_CLIPBOARD_INVALID" }
        val type = header.get().toInt() and 0xff
        require(type in setOf(P.CLIPBOARD_OFFER, P.CLIPBOARD_ACCEPT, P.CLIPBOARD_CHUNK, P.CLIPBOARD_ACK)) { "RD_CLIPBOARD_INVALID" }
        require(ByteBuffer.wrap(frame, 20, 4).order(ByteOrder.BIG_ENDIAN).int == frame.size - P.HEADER_LENGTH) { "RD_CLIPBOARD_INVALID" }
        val payload = frame.copyOfRange(P.HEADER_LENGTH, frame.size)
        require(now() < (incoming?.deadline ?: Long.MAX_VALUE) && now() < (outgoing?.deadline ?: Long.MAX_VALUE)) { "RD_CLIPBOARD_TIMEOUT" }
        when (type) {
            P.CLIPBOARD_OFFER -> receiveOffer(payload)
            P.CLIPBOARD_ACCEPT -> receiveAccept(payload)
            P.CLIPBOARD_CHUNK -> receiveChunk(payload)
            P.CLIPBOARD_ACK -> receiveAck(payload)
        }
    }

    private fun receiveOffer(payload: ByteArray) {
        check(readEnabled && incoming == null) { "RD_CLIPBOARD_INVALID" }
        val body = RemoteJson.parse(payload, 16360)
        require(body.keys == setOf("id", "size", "sha256", "mime") && body["mime"] == JsonPrimitive("text/plain;charset=utf-8")) { "RD_CLIPBOARD_INVALID" }
        val id = parseId(body.getValue("id").jsonPrimitive.content)
        val size = body.getValue("size").jsonPrimitive.intOrNull ?: -1
        val digest = body.getValue("sha256").jsonPrimitive.content
        require(size in 0..P.CLIPBOARD_BYTES && DIGEST.matches(digest) && id !in seen) { "RD_CLIPBOARD_INVALID" }
        incoming = Incoming(id, size, digest, ByteArrayOutputStream(size), now() + 30_000)
        send(P.CLIPBOARD_ACCEPT, json(mapOf("id" to JsonPrimitive(id.toString()))))
        if (size == 0) finishReceive()
    }

    private fun receiveChunk(payload: ByteArray) {
        check(readEnabled) { "RD_CLIPBOARD_DISABLED" }
        val transfer = incoming ?: error("RD_CLIPBOARD_INVALID")
        require(payload.size in 25..16360) { "RD_CLIPBOARD_INVALID" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val id = UUID(buffer.long, buffer.long)
        val offset = buffer.long
        require(id == transfer.id && offset == transfer.bytes.size().toLong() && (payload.size - 24).toLong() <= transfer.size - offset) { "RD_CLIPBOARD_INVALID" }
        transfer.bytes.write(payload, 24, payload.size - 24)
        if (transfer.bytes.size() == transfer.size) finishReceive()
    }

    private fun finishReceive() {
        val transfer = incoming ?: error("RD_CLIPBOARD_INVALID")
        val bytes = transfer.bytes.toByteArray()
        require(sha256(bytes) == transfer.digest) { "RD_CLIPBOARD_INVALID" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text) { "RD_CLIPBOARD_INVALID" }
        lastDigest = transfer.digest
        receiveText(text)
        remember(transfer.id)
        incoming = null
        send(P.CLIPBOARD_ACK, json(mapOf("id" to JsonPrimitive(transfer.id.toString()))))
    }

    private fun receiveAccept(payload: ByteArray) {
        check(writeEnabled) { "RD_CLIPBOARD_DISABLED" }
        val transfer = outgoing ?: error("RD_CLIPBOARD_INVALID")
        require(!transfer.accepted && responseId(payload) == transfer.id) { "RD_CLIPBOARD_INVALID" }
        transfer.accepted = true
        while (transfer.offset < transfer.bytes.size) {
            val count = minOf(4096, transfer.bytes.size - transfer.offset)
            val body = ByteBuffer.allocate(24 + count).order(ByteOrder.BIG_ENDIAN)
                .putLong(transfer.id.mostSignificantBits).putLong(transfer.id.leastSignificantBits)
                .putLong(transfer.offset.toLong()).put(transfer.bytes, transfer.offset, count).array()
            send(P.CLIPBOARD_CHUNK, body)
            transfer.offset += count
        }
    }

    private fun receiveAck(payload: ByteArray) {
        check(writeEnabled) { "RD_CLIPBOARD_DISABLED" }
        val transfer = outgoing ?: error("RD_CLIPBOARD_INVALID")
        require(transfer.accepted && transfer.offset == transfer.bytes.size && responseId(payload) == transfer.id) { "RD_CLIPBOARD_INVALID" }
        lastDigest = transfer.digest
        remember(transfer.id)
        outgoing = null
    }

    private fun responseId(payload: ByteArray): UUID {
        val body = RemoteJson.parse(payload, 16360)
        require(body.keys == setOf("id")) { "RD_CLIPBOARD_INVALID" }
        return parseId(body.getValue("id").jsonPrimitive.content)
    }

    private fun remember(id: UUID) {
        seen.addLast(id)
        if (seen.size > 128) seen.removeFirst()
    }

    private fun json(values: Map<String, JsonPrimitive>) = JsonObject(values).toString().toByteArray(Charsets.UTF_8)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun parseId(value: String): UUID {
        val id = UUID.fromString(value)
        require(id.toString() == value) { "RD_CLIPBOARD_INVALID" }
        return id
    }

    companion object { private val DIGEST = Regex("[0-9a-f]{64}") }
}
