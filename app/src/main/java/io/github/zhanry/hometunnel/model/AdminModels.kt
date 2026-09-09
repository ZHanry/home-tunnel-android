package io.github.zhanry.hometunnel.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdminUser(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    val status: String,
    @SerialName("password_state") val passwordState: String = "normal",
    val version: Long,
    @SerialName("device_count") val deviceCount: Int = 0,
    @SerialName("connection_count") val connectionCount: Int = 0,
    @SerialName("month_to_date_bytes") val monthToDateBytes: Long = 0,
    @SerialName("monthly_quota_bytes") val monthlyQuotaBytes: Long? = null,
    @SerialName("bandwidth_limit_bps") val bandwidthLimitBps: Long? = null,
    @SerialName("quota_suspended") val quotaSuspended: Boolean = false,
) {
    val isAdministrator: Boolean get() = role == "admin"
}

@Serializable data class AdminUserList(val items: List<AdminUser> = emptyList())

@Serializable
data class AdminPasswordResponse(
    val user: AdminUser? = null,
    @SerialName("temporary_password") val temporaryPassword: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Long? = null,
) {
    override fun toString(): String = "AdminPasswordResponse(password=[redacted])"
}

@Serializable
data class AdminSummary(
    val users: Int = 0,
    @SerialName("online_devices") val onlineDevices: Int = 0,
    val connections: Int = 0,
    @SerialName("online_connections") val onlineConnections: Int = 0,
    @SerialName("upload_24h") val upload24h: Long = 0,
    @SerialName("download_24h") val download24h: Long = 0,
    @SerialName("high_errors") val errors: Int = 0,
)

@Serializable
data class AdminSettings(
    @SerialName("subdomain_prefix_policy") val prefixPolicy: String = "suggest",
    @SerialName("client_raw_tunnels_enabled") val clientRawTunnelsEnabled: Boolean? = null,
)

@Serializable data class AdminHealth(val status: String, val components: List<HealthComponent> = emptyList())
@Serializable data class HealthComponent(val component: String, val status: String, val version: String? = null)

@Serializable
data class AdminDevice(
    val id: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val name: String,
    val status: String,
    val online: Boolean = false,
    @SerialName("client_version") val clientVersion: String? = null,
)
@Serializable data class AdminDeviceList(val items: List<AdminDevice> = emptyList())

@Serializable
data class AdminConnection(
    val id: String,
    val name: String = "",
    val username: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("proxy_type") val proxyType: String = "http",
    @SerialName("public_url") val publicUrl: String? = null,
    @SerialName("public_endpoint") val publicEndpoint: String? = null,
    @SerialName("access_url") val accessUrl: String? = null,
    val enabled: Boolean = true,
    val state: String = "Pending",
) {
    val endpoint: String get() = accessUrl ?: publicUrl ?: publicEndpoint.orEmpty()
}
@Serializable
data class AdminConnectionList(
    val items: List<AdminConnection> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class AdminAuditEvent(
    val id: Long,
    val action: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("created_at") val createdAt: String,
)
@Serializable
data class AdminAuditList(
    val items: List<AdminAuditEvent> = emptyList(),
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)
