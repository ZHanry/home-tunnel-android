package io.github.zhanry.hometunnel.remote

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RemoteTransferTest {
    @Test fun `received files finish only after exact length and digest verification`() {
        val content = "test content".toByteArray()
        val output = ByteArrayOutputStream()
        var discards = 0
        var commits = 0
        val receiver = RemoteReceiveTransfer(UUID.randomUUID(), content.size.toLong(), output, { commits++ }) { discards++ }
        assertEquals(4L, receiver.chunk(0, content.copyOfRange(0, 4)))
        assertEquals(content.size.toLong(), receiver.chunk(4, content.copyOfRange(4, content.size)))
        receiver.finish(content.size.toLong(), RemoteCrypto.sha256(content)); receiver.close()
        assertEquals(3, commits)
        assertContentEquals(content, output.toByteArray()); assertEquals(0, discards)
        assertFails { receiver.chunk(content.size.toLong(), byteArrayOf(1)) }
    }
    @Test fun `incomplete corrupted cancelled and oversized transfers never report success`() {
        var discarded = 0
        val receiver = RemoteReceiveTransfer(UUID.randomUUID(), 4, ByteArrayOutputStream(), {}) { discarded++ }
        assertFails { receiver.chunk(1, byteArrayOf(0)) }
        assertFails { receiver.chunk(0, ByteArray(5)) }
        assertFails { receiver.finish(4, RemoteCrypto.sha256(byteArrayOf(1, 2, 3, 4))) }
        receiver.close(); assertEquals(1, discarded)
        assertFails { RemoteReceiveTransfer(UUID.randomUUID(), RemoteFiles.MAX_FILE_BYTES + 1, ByteArrayOutputStream(), {}) {} }
    }
    @Test fun `remote filenames cannot choose provider paths or control characters`() {
        for (name in listOf("../config", "..", "folder\\name", "C:evil", "x\ny", "", "\uD800")) assertFails { RemoteFiles.safeName(name) }
        assertEquals("报告.pdf", RemoteFiles.safeName("报告.pdf"))
    }
    @Test fun `sender blocks extra chunks and success until receiver commits and verifies digest`() {
        val content = ByteArray(20_000) { (it % 251).toByte() }
        val id = UUID.randomUUID()
        val sender = RemoteSendTransfer(RemoteFileOffer(id, "sample.bin", content.size.toLong()), ByteArrayInputStream(content))
        assertFails { sender.nextChunk() }
        sender.accept(id)
        val first = requireNotNull(sender.nextChunk())
        assertEquals(16_384, first.data.size)
        assertContentEquals(first.data, RemoteFileChunk.decode(first.encode()).data)
        assertFails { sender.nextChunk() }
        assertFails { sender.acknowledgeChunk(id, 1) }
        sender.acknowledgeChunk(id, 16_384)
        val second = requireNotNull(sender.nextChunk())
        assertEquals(16_384L, second.offset)
        sender.acknowledgeChunk(id, content.size.toLong())
        assertNull(sender.nextChunk()); sender.completion()
        assertFalse(sender.completed)
        assertFails { sender.acknowledgeCompletion(id, ByteArray(32)) }
        sender.acknowledgeCompletion(id, RemoteCrypto.sha256(content))
        assertTrue(sender.completed)
    }
    @Test fun `source size changes commit failures and corrupted completion cannot succeed`() {
        val id = UUID.randomUUID()
        val sender = RemoteSendTransfer(RemoteFileOffer(id, "sample.bin", 0), ByteArrayInputStream(byteArrayOf(1)))
        sender.accept(id); assertFails { sender.nextChunk() }; assertFalse(sender.completed)
        var discarded = 0
        val receiver = RemoteReceiveTransfer(id, 1, ByteArrayOutputStream(), { error("disk full") }) { discarded++ }
        assertFails { receiver.chunk(0, byteArrayOf(1)) }; assertEquals(1, discarded)
        val corrupt = RemoteReceiveTransfer(id, 1, ByteArrayOutputStream(), {}) { discarded++ }
        corrupt.chunk(0, byteArrayOf(1)); assertFails { corrupt.finish(1, RemoteCrypto.sha256(byteArrayOf(2))) }
        assertEquals(2, discarded)
    }
    @Test fun `file selections enforce two concurrent transfers and whole-batch limits`() {
        val offers = (1..3).map { RemoteFileOffer(UUID.randomUUID(), "$it.bin", 1) }
        val batch = RemoteTransferBatch(offers)
        batch.start(offers[0].id); batch.start(offers[1].id)
        assertFails { batch.start(offers[2].id) }
        batch.finish(offers[0].id); batch.start(offers[2].id)
        assertFails { batch.start(offers[0].id) }
        assertFails { RemoteTransferBatch((1..5).map { RemoteFileOffer(UUID.randomUUID(), "$it.bin", RemoteFiles.MAX_FILE_BYTES) }) }
    }
}
