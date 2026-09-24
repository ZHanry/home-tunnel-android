package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RemoteClipboardTransferTest {
    @Test fun `text moves in both directions only while features are enabled`() {
        val toHost = ArrayDeque<ByteArray>()
        val toController = ArrayDeque<ByteArray>()
        val controllerText = mutableListOf<String>()
        val hostText = mutableListOf<String>()
        var controllerSequence = 0L
        var hostSequence = 0L
        val controller = RemoteClipboardTransfer({ type, payload ->
            toHost.addLast(RemoteWire.frame(type, 1, 0, ++controllerSequence, payload))
        }, controllerText::add)
        val host = RemoteClipboardTransfer({ type, payload ->
            toController.addLast(RemoteWire.frame(type, 1, 0, ++hostSequence, payload))
        }, hostText::add)
        for (permission in listOf("clipboard.read", "clipboard.write")) {
            controller.feature(permission, true)
            host.feature(permission, true)
        }
        fun pump() {
            while (toHost.isNotEmpty() || toController.isNotEmpty()) {
                while (toHost.isNotEmpty()) host.receive(toHost.removeFirst())
                while (toController.isNotEmpty()) controller.receive(toController.removeFirst())
            }
        }
        assertTrue(controller.offer("来自手机😀"))
        pump()
        assertEquals(listOf("来自手机😀"), hostText)
        assertFalse(controller.offer("来自手机😀"))
        assertTrue(host.offer("远端文本".repeat(4000)))
        pump()
        assertEquals("远端文本".repeat(4000), controllerText.single())
        controller.feature("clipboard.write", false)
        assertFails { controller.offer("revoked") }
    }

    @Test fun `tampered clipboard content never reaches the operating system`() {
        val sent = ArrayDeque<ByteArray>()
        val received = mutableListOf<String>()
        var sequence = 0L
        val sender = RemoteClipboardTransfer({ type, payload ->
            sent.addLast(RemoteWire.frame(type, 1, 0, ++sequence, payload))
        }, {})
        val receiver = RemoteClipboardTransfer({ type, payload ->
            sent.addLast(RemoteWire.frame(type, 1, 0, ++sequence, payload))
        }, received::add)
        sender.feature("clipboard.write", true)
        receiver.feature("clipboard.read", true)
        assertTrue(sender.offer("safe text"))
        receiver.receive(sent.removeFirst())
        sender.receive(sent.removeFirst())
        val corrupted = sent.removeFirst()
        corrupted[corrupted.lastIndex] = 'x'.code.toByte()
        assertFails { receiver.receive(corrupted) }
        assertTrue(received.isEmpty())
    }
}
