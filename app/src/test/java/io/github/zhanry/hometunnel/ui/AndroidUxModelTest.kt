package io.github.zhanry.hometunnel.ui

import io.github.zhanry.hometunnel.model.ConnectionCapabilities
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.TransportCapability
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.ui.nav.ShellLocation
import io.github.zhanry.hometunnel.ui.nav.canOpenAdmin
import io.github.zhanry.hometunnel.ui.nav.remoteHomeOrder
import io.github.zhanry.hometunnel.ui.nav.systemBack
import io.github.zhanry.hometunnel.ui.remote.ConnectMode
import io.github.zhanry.hometunnel.ui.remote.RemoteStage
import io.github.zhanry.hometunnel.ui.remote.SessionControl
import io.github.zhanry.hometunnel.ui.remote.connectModes
import io.github.zhanry.hometunnel.ui.remote.nineDigitCode
import io.github.zhanry.hometunnel.ui.remote.recentPreferenceKey
import io.github.zhanry.hometunnel.ui.remote.rememberRecent
import io.github.zhanry.hometunnel.ui.remote.remoteStage
import io.github.zhanry.hometunnel.ui.remote.visibleSessionControls
import io.github.zhanry.hometunnel.ui.theme.ThemeChoice
import io.github.zhanry.hometunnel.ui.tunnel.TunnelDraftStore
import io.github.zhanry.hometunnel.ui.tunnel.TunnelFailureKind
import io.github.zhanry.hometunnel.ui.tunnel.TunnelTemplateId
import io.github.zhanry.hometunnel.ui.tunnel.WizardFields
import io.github.zhanry.hometunnel.ui.tunnel.WizardStep
import io.github.zhanry.hometunnel.ui.tunnel.assignedPort
import io.github.zhanry.hometunnel.ui.tunnel.baselineFields
import io.github.zhanry.hometunnel.ui.tunnel.canOpenStep
import io.github.zhanry.hometunnel.ui.tunnel.classifyTunnelFailure
import io.github.zhanry.hometunnel.ui.tunnel.inferTemplate
import io.github.zhanry.hometunnel.ui.tunnel.matchSubmittedTunnel
import io.github.zhanry.hometunnel.ui.tunnel.templateById
import io.github.zhanry.hometunnel.ui.tunnel.tunnelAccountKey
import io.github.zhanry.hometunnel.ui.tunnel.validateWizard
import io.github.zhanry.hometunnel.ui.tunnel.withTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AndroidUxModelTest {
    private val openCapabilities = ConnectionCapabilities(
        supported = true,
        tcp = TransportCapability(enabled = true, canCreate = true),
        udp = TransportCapability(enabled = true, canCreate = true),
    )
    private val httpOnly = ConnectionCapabilities()

    private fun fields(template: String = "HTTP", device: String = "nas", name: String = "Photos") = WizardFields(
        templateId = template,
        deviceId = device,
        name = name,
        subdomain = "photos",
        scheme = "http",
        host = "127.0.0.1",
        port = "8080",
        enabled = true,
    )

    @Test
    fun `wizard blocks an incomplete tunnel and does not invent a public port`() {
        val missing = validateWizard(fields(device = "", name = ""), openCapabilities, true, setOf("nas"), false)
        assertTrue(missing.any { it.step == WizardStep.DEVICE })
        assertFalse(canOpenStep(WizardStep.TARGET, missing))
        val https = templateById("HTTPS")!!
        assertEquals(ProxyKind.HTTP, https.transport)
        assertEquals("https", https.localScheme)
        assertEquals(443, https.defaultPort)
        val updated = withTemplate(fields(), https)
        assertEquals("443", updated.port)
        assertNull(assignedPort(null))
        assertNull(assignedPort(0))
        assertEquals(20001, assignedPort(20001))
    }

    @Test
    fun `udp and https templates follow advertised transports`() {
        assertTrue(validateWizard(fields(template = "UDP").copy(scheme = "udp", port = "53"), openCapabilities, true, setOf("nas"), false).isEmpty())
        assertTrue(validateWizard(fields(template = "UDP"), httpOnly, true, setOf("nas"), false).any { it.code == "transport_denied" })
        val raw = validateWizard(fields(template = "TCP").copy(port = "22", subdomain = ""), openCapabilities, true, setOf("nas"), false)
        assertTrue(raw.none { it.code == "subdomain" })
        assertEquals(TunnelTemplateId.SSH, inferTemplate(sample().copy(proxyType = "tcp", applicationProtocol = "ssh", localPort = 22)))
        assertEquals(TunnelTemplateId.HTTPS, inferTemplate(sample().copy(localScheme = "https", localPort = 443)))
    }

    @Test
    fun `drafts stay on the account that created them and round-trip without a local port assignment`() {
        val store = TunnelDraftStore()
        val accountA = tunnelAccountKey("account-a", "https://a.example/api/v1", "lin")
        val accountB = tunnelAccountKey(null, "https://b.example/api/v1", "lin")
        val edit = ConnectionEdit(sample().copy(remotePort = null), true, fields = fields())
        store.save(accountA, edit.value.id, edit.exportState())
        assertNull(store.load(accountB, edit.value.id))
        val restored = importConnectionEdit(store.load(accountA, edit.value.id)!!)
        assertEquals(edit.fields, restored?.fields)
        assertNull(restored?.value?.remotePort)
        store.clear(accountA, edit.value.id)
        assertNull(store.load(accountA, edit.value.id))
        assertFalse(edit.exportState().any { it.contains("password", ignoreCase = true) || it.contains("token", ignoreCase = true) })
    }

    @Test
    fun `failure text distinguishes permission port sync offline and conflict`() {
        assertEquals(TunnelFailureKind.CONFLICT, classifyTunnelFailure("VERSION_CONFLICT: stale"))
        assertEquals(TunnelFailureKind.PERMISSION, classifyTunnelFailure("FORBIDDEN: Server has not enabled this transport for your account"))
        assertEquals(TunnelFailureKind.PORT, classifyTunnelFailure("PORT_POOL_EXHAUSTED"))
        assertEquals(TunnelFailureKind.SYNC, classifyTunnelFailure("SYNC_PENDING"))
        assertEquals(TunnelFailureKind.OFFLINE, classifyTunnelFailure("DEVICE_OFFLINE"))
        assertEquals(TunnelFailureKind.OTHER, classifyTunnelFailure("Request failed"))
    }

    @Test
    fun `theme choice restores only the three supported modes`() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored(null))
        assertEquals(ThemeChoice.DARK, ThemeChoice.fromStored("DARK"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromStored("contrast"))
        assertEquals(-1, ThemeChoice.SYSTEM.nightMode())
        assertEquals(1, ThemeChoice.LIGHT.nightMode())
        assertEquals(2, ThemeChoice.DARK.nightMode())
    }

    @Test
    fun `navigation back leaves a remote session updates and admin without dropping the tab`() {
        assertEquals(ShellLocation(tab = 1), ShellLocation(tab = 1, remote = true).systemBack())
        assertEquals(ShellLocation(tab = 3), ShellLocation(tab = 3, updates = true).systemBack())
        assertEquals(ShellLocation(tab = 3), ShellLocation(tab = 4).systemBack())
        assertEquals(ShellLocation(tab = 2), ShellLocation(tab = 2).systemBack())
        assertFalse(canOpenAdmin(false))
        assertTrue(canOpenAdmin(true))
    }

    @Test
    fun `remote modes and session controls stay unavailable until the backend authorizes them`() {
        val idle = connectModes(nativeAvailable = true, unattendedAdvertised = false)
        assertEquals(4, idle.size)
        assertTrue(idle.first { it.mode == ConnectMode.APPROVAL }.enabled)
        assertFalse(idle.first { it.mode == ConnectMode.UNATTENDED }.enabled)
        assertEquals("unattended", idle.first { it.mode == ConnectMode.UNATTENDED }.blockedReason)
        assertTrue(connectModes(true, true).all { it.enabled })
        assertTrue(connectModes(false, true).none { it.enabled })
        assertEquals(RemoteStage.APPROVAL, remoteStage(false, true, true, "pending_approval", null, true, false))
        assertEquals(RemoteStage.DIRECT_UDP, remoteStage(false, true, true, "connecting", null, false, true))
        assertEquals(RemoteStage.CONNECTED, remoteStage(false, true, true, "active", null, false, true))
        assertEquals(RemoteStage.FAILED, remoteStage(false, true, true, "idle", "RD_TIMEOUT", false, false))
        assertEquals(RemoteStage.AUTHENTICATION, remoteStage(false, true, false, "idle", null, false, false))
        val hidden = visibleSessionControls(emptySet(), hasDisplay = false)
        assertTrue(hidden.isEmpty())
        val granted = visibleSessionControls(setOf("input.text", "clipboard.read", "audio.system", "files.send"), hasDisplay = true)
        assertEquals(setOf(SessionControl.DISPLAY, SessionControl.UNICODE, SessionControl.CLIPBOARD, SessionControl.SYSTEM_AUDIO, SessionControl.FILES), granted)
        assertFalse(SessionControl.MICROPHONE in granted)
        assertFalse(SessionControl.POINTER in granted)
    }

    @Test
    fun `recent codes keep only account-scoped device codes`() {
        assertNull(nineDigitCode("secret-password"))
        assertEquals(listOf("123456789"), rememberRecent(listOf("password", "123456789"), "not-a-code"))
        assertEquals(listOf("987654321", "123456789"), rememberRecent(listOf("123456789", "123456789"), "987654321"))
        assertFalse(recentPreferenceKey("account-a") == recentPreferenceKey("account-b"))
        val favorites = remoteHomeOrder(listOf(
            ManagedDevice("b", "Beta", online = true),
            ManagedDevice("a", "Alpha", online = false, favorite = true),
        ))
        assertEquals(listOf("a", "b"), favorites.map { it.id })
    }

    private fun sample() = TunnelConnection(
        id = "photos",
        deviceId = "nas",
        name = "Photos",
        subdomain = "photos",
        proxyType = "http",
        localPort = 8080,
        version = 3,
        state = "Pending",
    ).let { it.copy() }

    @Test
    fun `a saved tunnel result uses only a server row and never invents an address`() {
        val previous = sample()
        val created = previous.copy(id = "created", name = "Photos", remotePort = 21001, publicUrl = "https://photos.example")
        val matched = matchSubmittedTunnel(listOf(previous, created), previous.copy(id = ""), setOf(previous.id), true)
        assertEquals("created", matched?.id)
        assertEquals("https://photos.example", matched?.publicDisplayEndpoint)
        assertNull(matchSubmittedTunnel(listOf(previous), previous.copy(id = ""), setOf(previous.id), true))
        assertEquals(previous.id, matchSubmittedTunnel(listOf(previous.copy(state = "Online")), previous, emptySet(), false)?.id)
    }

    @Test
    fun `saved wizard state restores the same fields after rotation`() {
        val edit = ConnectionEdit(sample(), false, step = 2, fields = baselineFields(sample()).copy(name = "Renamed"))
        val restored = importConnectionEdit(edit.exportState())
        assertEquals("Renamed", restored?.fields?.name)
        assertEquals(2, restored?.step)
        assertEquals(3, restored?.value?.version)
        assertTrue(edit.changed())
    }
}
