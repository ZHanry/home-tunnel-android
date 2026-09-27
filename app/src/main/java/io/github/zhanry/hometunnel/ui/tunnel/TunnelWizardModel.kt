package io.github.zhanry.hometunnel.ui.tunnel

import io.github.zhanry.hometunnel.model.ConnectionCapabilities
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.TunnelConnection
import kotlinx.serialization.json.Json

enum class TunnelTemplateId {
    HTTP,
    HTTPS,
    TCP,
    UDP,
    NAS,
    HOME_ASSISTANT,
    IMMICH,
    JELLYFIN,
    SSH,
    RDP,
    RTSP,
}

data class TunnelTemplate(
    val id: TunnelTemplateId,
    val transport: ProxyKind,
    val localScheme: String,
    val defaultPort: Int,
    val applicationProtocol: String?,
) {
    val raw: Boolean get() = transport == ProxyKind.TCP || transport == ProxyKind.UDP
}

val TUNNEL_TEMPLATES: List<TunnelTemplate> = listOf(
    TunnelTemplate(TunnelTemplateId.HTTP, ProxyKind.HTTP, "http", 8080, null),
    TunnelTemplate(TunnelTemplateId.HTTPS, ProxyKind.HTTP, "https", 443, null),
    TunnelTemplate(TunnelTemplateId.TCP, ProxyKind.TCP, "tcp", 0, null),
    TunnelTemplate(TunnelTemplateId.UDP, ProxyKind.UDP, "udp", 0, null),
    TunnelTemplate(TunnelTemplateId.NAS, ProxyKind.HTTP, "https", 5001, null),
    TunnelTemplate(TunnelTemplateId.HOME_ASSISTANT, ProxyKind.HTTP, "http", 8123, null),
    TunnelTemplate(TunnelTemplateId.IMMICH, ProxyKind.HTTP, "http", 2283, null),
    TunnelTemplate(TunnelTemplateId.JELLYFIN, ProxyKind.HTTP, "http", 8096, null),
    TunnelTemplate(TunnelTemplateId.SSH, ProxyKind.TCP, "tcp", 22, "ssh"),
    TunnelTemplate(TunnelTemplateId.RDP, ProxyKind.TCP, "tcp", 3389, "rdp"),
    TunnelTemplate(TunnelTemplateId.RTSP, ProxyKind.TCP, "tcp", 554, "rtsp"),
)

fun templateById(id: String): TunnelTemplate? = TUNNEL_TEMPLATES.firstOrNull { it.id.name.equals(id, ignoreCase = true) }

fun templatePermitted(template: TunnelTemplate, capabilities: ConnectionCapabilities): Boolean =
    capabilities.permits(template.transport)

enum class WizardStep { DEVICE, TARGET, ACCESS, REVIEW }

data class WizardFields(
    val templateId: String,
    val deviceId: String,
    val name: String,
    val subdomain: String,
    val scheme: String,
    val host: String,
    val port: String,
    val enabled: Boolean,
)

data class FieldError(val step: WizardStep, val code: String)

private val subdomainPattern = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

fun suggestedSubdomain(username: String): String {
    val stem = username.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(40)
    return if (stem.isBlank()) "" else "$stem-app"
}

fun inferTemplate(connection: TunnelConnection): TunnelTemplateId {
    when (connection.applicationProtocol?.lowercase()) {
        "ssh" -> return TunnelTemplateId.SSH
        "rdp" -> return TunnelTemplateId.RDP
        "rtsp" -> return TunnelTemplateId.RTSP
    }
    return when (connection.kind) {
        ProxyKind.UDP -> TunnelTemplateId.UDP
        ProxyKind.TCP -> TunnelTemplateId.TCP
        ProxyKind.HTTP -> when {
            connection.localScheme.equals("https", true) && connection.localPort == 5001 -> TunnelTemplateId.NAS
            connection.localScheme.equals("https", true) -> TunnelTemplateId.HTTPS
            connection.localPort == 8123 -> TunnelTemplateId.HOME_ASSISTANT
            connection.localPort == 2283 -> TunnelTemplateId.IMMICH
            connection.localPort == 8096 -> TunnelTemplateId.JELLYFIN
            else -> TunnelTemplateId.HTTP
        }
        ProxyKind.UNKNOWN -> TunnelTemplateId.HTTP
    }
}

fun baselineFields(connection: TunnelConnection): WizardFields = WizardFields(
    templateId = inferTemplate(connection).name,
    deviceId = connection.deviceId,
    name = connection.name,
    subdomain = connection.subdomain,
    scheme = connection.localScheme.ifBlank { "http" },
    host = connection.localHost.ifBlank { "127.0.0.1" },
    port = connection.localPort.takeIf { it > 0 }?.toString().orEmpty(),
    enabled = connection.enabled,
)

fun withTemplate(fields: WizardFields, template: TunnelTemplate): WizardFields = fields.copy(
    templateId = template.id.name,
    scheme = template.localScheme,
    port = if (template.defaultPort > 0) template.defaultPort.toString() else fields.port,
)

fun validateWizard(
    fields: WizardFields,
    capabilities: ConnectionCapabilities,
    isNew: Boolean,
    activeDeviceIds: Set<String>,
    unknown: Boolean,
): List<FieldError> {
    if (unknown) return listOf(FieldError(WizardStep.DEVICE, "unknown_type"))
    val template = templateById(fields.templateId)
    val errors = mutableListOf<FieldError>()
    if (template == null) errors += FieldError(WizardStep.DEVICE, "template")
    else if (isNew && !templatePermitted(template, capabilities)) errors += FieldError(WizardStep.DEVICE, "transport_denied")
    if (fields.deviceId.isBlank()) errors += FieldError(WizardStep.DEVICE, "device")
    else if (isNew && fields.deviceId !in activeDeviceIds) errors += FieldError(WizardStep.DEVICE, "device_inactive")
    if (fields.name.isBlank()) errors += FieldError(WizardStep.DEVICE, "name")
    if (fields.host.isBlank()) errors += FieldError(WizardStep.TARGET, "host")
    val port = fields.port.toIntOrNull()
    if (port == null || port !in 1..65535) errors += FieldError(WizardStep.TARGET, "port")
    if (template?.raw != true) {
        if (!subdomainPattern.matches(fields.subdomain)) errors += FieldError(WizardStep.ACCESS, "subdomain")
        if (fields.scheme !in setOf("http", "https")) errors += FieldError(WizardStep.ACCESS, "scheme")
    }
    return errors
}

fun canOpenStep(target: WizardStep, issues: List<FieldError>): Boolean {
    val order = WizardStep.entries
    return order.take(order.indexOf(target)).all { step -> issues.none { it.step == step } }
}

fun wizardChanged(fields: WizardFields, baseline: WizardFields): Boolean = fields != baseline

/**
 * Public port assigned by the server. Zero and missing values stay unassigned.
 * Callers must not replace a missing value with a locally chosen port.
 */
fun assignedPort(remotePort: Int?): Int? = remotePort?.takeIf { it in 1..65535 }

enum class TunnelFailureKind { PERMISSION, PORT, SYNC, OFFLINE, CONFLICT, OTHER }

fun classifyTunnelFailure(message: String?): TunnelFailureKind {
    val text = message?.uppercase().orEmpty()
    if (text.isBlank()) return TunnelFailureKind.OTHER
    return when {
        "VERSION_CONFLICT" in text -> TunnelFailureKind.CONFLICT
        "FORBIDDEN" in text || "UNAUTHORIZED" in text || "PERMISSION" in text ||
            "NOT ENABLED THIS TRANSPORT" in text || "SCOPE_DENIED" in text -> TunnelFailureKind.PERMISSION
        "PORT" in text || "POOL" in text -> TunnelFailureKind.PORT
        "SYNC" in text || "STALE" in text || "APPLYING" in text -> TunnelFailureKind.SYNC
        "OFFLINE" in text || "UNREACHABLE" in text || "UNKNOWN_HOST" in text ||
            "TIMEOUT" in text || "FAILED_TO_CONNECT" in text || "NETWORK" in text -> TunnelFailureKind.OFFLINE
        else -> TunnelFailureKind.OTHER
    }
}

/**
 * Finds the row the server listed after a save. A new tunnel is matched only by an id
 * that was not present before the save. No address is synthesized when nothing matches.
 */
fun matchSubmittedTunnel(
    connections: List<TunnelConnection>,
    submitted: TunnelConnection,
    previousIds: Set<String>,
    isNew: Boolean,
): TunnelConnection? {
    if (!isNew) return connections.firstOrNull { it.id == submitted.id && it.id.isNotBlank() }
    return connections.firstOrNull { candidate ->
        candidate.id.isNotBlank() && candidate.id !in previousIds &&
            candidate.deviceId == submitted.deviceId &&
            candidate.name == submitted.name &&
            candidate.localHost == submitted.localHost &&
            candidate.localPort == submitted.localPort &&
            candidate.proxyType == submitted.proxyType
    }
}

fun tunnelAccountKey(activeAccountId: String?, apiBaseUrl: String?, username: String?): String =
    activeAccountId?.takeIf { it.isNotBlank() }?.let { "id:$it" }
        ?: "profile:${apiBaseUrl.orEmpty()}|${username.orEmpty()}"

/** In-memory, account-scoped drafts. Values are wizard fields only, never passwords or tokens. */
class TunnelDraftStore {
    private val items = mutableMapOf<String, List<String>>()

    private fun key(accountKey: String, connectionId: String) = "$accountKey\u0000$connectionId"

    fun save(accountKey: String, connectionId: String, state: List<String>) {
        items[key(accountKey, connectionId)] = state
    }

    fun load(accountKey: String, connectionId: String): List<String>? = items[key(accountKey, connectionId)]

    fun clear(accountKey: String, connectionId: String) {
        items.remove(key(accountKey, connectionId))
    }

    companion object {
        val shared = TunnelDraftStore()
    }
}

private val wizardJson = Json { ignoreUnknownKeys = true }

fun exportWizardState(
    isNew: Boolean,
    step: Int,
    value: TunnelConnection,
    baseline: TunnelConnection,
    fields: WizardFields,
): List<String> = listOf(
    "v1",
    isNew.toString(),
    step.coerceIn(0, 3).toString(),
    wizardJson.encodeToString(TunnelConnection.serializer(), value),
    wizardJson.encodeToString(TunnelConnection.serializer(), baseline),
    fields.templateId,
    fields.deviceId,
    fields.name,
    fields.subdomain,
    fields.scheme,
    fields.host,
    fields.port,
    fields.enabled.toString(),
)

data class ImportedWizard(
    val isNew: Boolean,
    val step: Int,
    val value: TunnelConnection,
    val baseline: TunnelConnection,
    val fields: WizardFields,
)

fun importWizardState(saved: List<String>): ImportedWizard? {
    if (saved.size != 13 || saved[0] != "v1") return null
    return runCatching {
        ImportedWizard(
            isNew = saved[1].toBooleanStrict(),
            step = saved[2].toInt().coerceIn(0, 3),
            value = wizardJson.decodeFromString(TunnelConnection.serializer(), saved[3]),
            baseline = wizardJson.decodeFromString(TunnelConnection.serializer(), saved[4]),
            fields = WizardFields(
                templateId = saved[5],
                deviceId = saved[6],
                name = saved[7],
                subdomain = saved[8],
                scheme = saved[9],
                host = saved[10],
                port = saved[11],
                enabled = saved[12].toBooleanStrict(),
            ),
        )
    }.getOrNull()
}
