package io.github.zhanry.hometunnel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.network.AdministrationApi
import io.github.zhanry.hometunnel.ui.tunnel.baselineFields
import io.github.zhanry.hometunnel.ui.tunnel.templateById
import io.github.zhanry.hometunnel.ui.tunnel.withTemplate
import kotlinx.coroutines.awaitCancellation

/** Screenshot-only inputs. All writes fail and no network client is created. */
internal class UiReviewAdminApi(private val scenario: String) : AdministrationApi {
    private val member = AdminUser("member-1", "member", "Review member", "user", "active", version = 12,
        deviceCount = 2, connectionCount = 3, monthToDateBytes = 1_000_000_000, monthlyQuotaBytes = 10_000_000_000)
    private suspend fun <T> read(value: T): T {
        when (scenario) {
            "loading" -> awaitCancellation()
            "error" -> throw ApiException(503, "TEMPORARY_UNAVAILABLE", "Synthetic service failure")
            "offline" -> throw java.io.IOException("Synthetic offline state")
            "no-permission" -> throw ApiException(403, "FORBIDDEN", "Synthetic access denial")
        }
        return value
    }
    override suspend fun adminSummary() = read(if (scenario == "empty") AdminSummary() else AdminSummary(2, 1, 3, 2, 123_456_789, 456_789_012))
    override suspend fun adminUsers(search: String) = read(AdminUserList(if (scenario == "empty") emptyList() else listOf(member)))
    override suspend fun adminUser(id: String) = read(member)
    override suspend fun adminDevices(userId: String) = read(AdminDeviceList(if (scenario == "empty") emptyList() else listOf(
        AdminDevice("study", "member-1", "member", "Study PC", "active", true, "9.0.0"),
        AdminDevice("nas", "member-1", "member", "Family NAS", "active", false, "9.0.0"))))
    override suspend fun adminConnections(userId: String, search: String, page: Int) = read(AdminConnectionList(
        if (scenario == "empty") emptyList() else listOf(AdminConnection("photos", "Family photos", "member", "member-1", "nas", "http",
            publicUrl = "https://photos.example.test", state = "Online")), if (scenario == "empty") 0 else 1, page, 1))
    override suspend fun adminSettings() = read(AdminSettings("suggest", true, TransportPools(
        TransportPool(true, true, true, poolStart = 10000, poolEnd = 11000, portStart = 10000, portEnd = 11000,
            allocatedPorts = 2, availablePorts = 999, activeConnections = 1),
        TransportPool(true, true, true, poolStart = 12000, poolEnd = 13000, portStart = 12000, portEnd = 13000,
            availablePorts = 1001)), 1))
    override suspend fun adminHealth() = read(AdminHealth("healthy", listOf(HealthComponent("control-center", "healthy", "9.0.0"),
        HealthComponent("traffic-gateway", "healthy", "9.0.0"))))
    override suspend fun adminAudit(page: Int) = read(AdminAuditList(if (scenario == "empty") emptyList() else listOf(
        AdminAuditEvent(1, "UserCreated", "owner", "User", "member-1", "2026-09-27T12:00:00Z")), page, 1))
    override suspend fun adminCreateUser(username: String, displayName: String): AdminPasswordResponse = error("Screenshot fixture forbids writes")
    override suspend fun adminUpdateUser(id: String, displayName: String, version: Long): AdminUser = error("Screenshot fixture forbids writes")
    override suspend fun adminSetUserEnabled(id: String, enabled: Boolean): AdminUser = error("Screenshot fixture forbids writes")
    override suspend fun adminResetPassword(id: String): AdminPasswordResponse = error("Screenshot fixture forbids writes")
    override suspend fun adminDeleteUser(id: String, version: Long): Unit = error("Screenshot fixture forbids writes")
    override suspend fun adminSaveSettings(settings: AdminSettings): AdminSettings = error("Screenshot fixture forbids writes")
}

@Composable
internal fun UiReviewWizard(step: Int, result: Boolean, templateId: String, scenario: String, devices: List<ManagedDevice>) {
    val template = requireNotNull(templateById(templateId))
    val value = TunnelConnection("review-tunnel", "study", "Review service", "review-app", template.transport.wireName,
        remotePort = if (result && template.raw) 10001 else null, publicUrl = if (result && !template.raw) "https://review-app.example.test" else null,
        publicEndpoint = if (result && template.raw) "example.test:10001" else null, localPort = template.defaultPort.takeIf { it > 0 } ?: 8080,
        localScheme = template.localScheme, version = 1, state = if (scenario == "ready") "Online" else "Pending",
        applicationProtocol = template.applicationProtocol)
    var edit by remember { mutableStateOf(ConnectionEdit(value, true, step = step,
        fields = withTemplate(baselineFields(value), template).copy(name = "Review service draft"))) }
    val allowed = scenario != "no-permission"
    val capabilities = ConnectionCapabilities(true, TransportCapability(true, allowed, 10000, 11000), TransportCapability(true, allowed, 12000, 13000))
    val error = when (scenario) {
        "error" -> "TEMPORARY_UNAVAILABLE"
        "no-permission" -> "FORBIDDEN"
        "conflict" -> "VERSION_CONFLICT"
        "port-conflict" -> "PORT_CONFLICT"
        "sync-failed" -> "CONFIG_SYNC_FAILED"
        else -> null
    }
    ConnectionEditor(edit, if (scenario == "offline") devices.map { it.copy(online = false) } else devices,
        capabilities, busy = scenario == "loading", error = error,
        reported = if (result && scenario !in setOf("waiting", "unconfirmed", "loading")) value else null,
        waitingForServer = result && scenario in setOf("waiting", "loading"), reportUnconfirmed = result && scenario == "unconfirmed",
        onEdit = { edit = it }, onReloadLatest = {}, onDismiss = {}, onSave = {}, onDelete = null)
}
