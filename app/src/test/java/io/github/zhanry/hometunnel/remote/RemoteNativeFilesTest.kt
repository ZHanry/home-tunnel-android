package io.github.zhanry.hometunnel.remote

import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RemoteNativeFilesTest {
    private fun event(kind: String, offset: Long = 0, size: Long = 3, outgoing: Boolean = false, name: String = "报告.txt") = buildJsonObject {
        put("id", "12345678-1234-4567-8901-123456789abc"); put("name", name)
        put("event", kind); put("size", size); put("offset", offset); put("outgoing", outgoing)
    }
    @Test fun `incoming integrity completion does not claim a file was saved`() {
        val offer = nativeFileItem(event("offer"), null)
        val progressing = nativeFileItem(event("progress", 2), offer)
        assertFalse(progressing.terminal)
        assertFails { nativeFileItem(event("complete", 2), progressing) }
        val verified = nativeFileItem(event("complete", 3), progressing)
        assertEquals("ready_to_save", verified.status)
        assertTrue(verified.terminal)
        assertFails { nativeFileItem(event("progress", 3), verified) }
    }
    @Test fun `outgoing completion and zero byte receipt keep their different meanings`() {
        val sent = nativeFileItem(event("offer", outgoing = true), null)
        assertEquals("complete", nativeFileItem(event("complete", 3, outgoing = true), sent).status)
        val empty = nativeFileItem(event("offer", size = 0), null)
        assertEquals("ready_to_save", nativeFileItem(event("complete", size = 0), empty).status)
    }
    @Test fun `native events cannot change size direction name or move offsets backwards`() {
        val previous = nativeFileItem(event("offer"), null)
        val current = nativeFileItem(event("progress", 2), previous)
        assertFails { nativeFileItem(event("progress", 1), current) }
        assertFails { nativeFileItem(event("complete", 4, size = 4), current) }
        assertFails { nativeFileItem(event("complete", 3, outgoing = true), current) }
        assertFails { nativeFileItem(event("complete", 3, name = "changed.txt"), current) }
        assertFails { nativeFileItem(event("complete", 3), null) }
    }
    @Test fun `all selected names follow the native cross platform file policy`() {
        for (name in listOf("CON.txt", "lpt1.log", "bad*name", "trailing.", "trailing ", "../escape", "nul")) {
            assertFails { RemoteFiles.safeName(name) }
        }
        assertEquals("旅行照片.jpg", RemoteFiles.safeName("旅行照片.jpg"))
    }
    @Test fun `audio and files need a real frame plus capabilities and lose permission in background`() {
        val gate = RemoteSessionGate { 100 }
        val permissions = setOf("view", "audio.system", "files.send", "files.receive")
        gate.bindLocalCapabilities(permissions)
        gate.bindHostDiscovery(mapOf("system_audio" to "available", "files" to "available"))
        gate.authorized(1, permissions, 10000, 1)
        gate.foreground(true)
        gate.authenticatedDirectPath(1, "udp", "host", "srflx")
        assertFalse(gate.canUse("audio.system")); assertFalse(gate.canUse("files.send"))
        val generation = gate.surface(true)
        assertTrue(gate.presentedFrame(1, generation, 1))
        gate.feature("audio.system", true); gate.feature("files.receive", true)
        assertTrue(gate.featureEnabled("audio.system")); assertTrue(gate.featureEnabled("files.receive"))
        gate.surface(false)
        assertFalse(gate.canUse("audio.system"))
        assertTrue(gate.canUse("files.receive")) // Rotation need not abort an approved file.
        gate.foreground(false)
        assertFalse(gate.featureEnabled("files.receive"))
    }
}
