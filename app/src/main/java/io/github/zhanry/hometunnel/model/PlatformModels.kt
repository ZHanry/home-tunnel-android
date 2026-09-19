package io.github.zhanry.hometunnel.model

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransportCapability(
    val enabled: Boolean = false,
    @SerialName("can_create") val canCreate: Boolean = false,
    @SerialName("port_start") val portStart: Int = 0,
    @SerialName("port_end") val portEnd: Int = 0,
)

@Serializable
data class ConnectionCapabilities(
    val supported: Boolean = false,
    val tcp: TransportCapability = TransportCapability(),
    val udp: TransportCapability = TransportCapability(),
) {
    fun permits(kind: ProxyKind): Boolean = when (kind) {
        ProxyKind.HTTP -> true
        ProxyKind.TCP -> supported && tcp.enabled && tcp.canCreate
        ProxyKind.UDP -> supported && udp.enabled && udp.canCreate
        ProxyKind.UNKNOWN -> false
    }
}

@Serializable
data class TransportPool(
    val enabled: Boolean = false,
    @SerialName("configured_enabled") val configuredEnabled: Boolean = false,
    @SerialName("deployment_ready") val deploymentReady: Boolean = false,
    @SerialName("range_available") val rangeAvailable: Boolean = true,
    @SerialName("pool_start") val poolStart: Int? = null,
    @SerialName("pool_end") val poolEnd: Int? = null,
    @SerialName("port_start") val portStart: Int = 0,
    @SerialName("port_end") val portEnd: Int = 0,
    @SerialName("allocated_ports") val allocatedPorts: Int = 0,
    @SerialName("available_ports") val availablePorts: Int = 0,
    @SerialName("active_connections") val activeConnections: Int = 0,
)
@Serializable data class TransportPools(val tcp: TransportPool = TransportPool(), val udp: TransportPool = TransportPool())

@Serializable data class MfaStatus(val enabled: Boolean = false, @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int = 0)
@Serializable data class MfaSetup(val secret: String, @SerialName("otpauth_uri") val otpauthUri: String, @SerialName("expires_at") val expiresAt: String) {
    override fun toString(): String = "MfaSetup([redacted])"
}
@Serializable data class RecoveryCodes(@SerialName("recovery_codes") val codes: List<String>) { override fun toString(): String = "RecoveryCodes([redacted])" }
@Serializable data class ManagementSession(val id: String, @SerialName("client_type") val clientType: String, @SerialName("user_agent") val userAgent: String = "", @SerialName("created_at") val createdAt: String, val current: Boolean = false)
@Serializable data class ManagementSessions(val items: List<ManagementSession> = emptyList())
@Serializable data class EnrollmentCode(val id: String, val name: String, val code: String? = null, @SerialName("expires_at") val expiresAt: String, @SerialName("consumed_at") val consumedAt: String? = null, @SerialName("revoked_at") val revokedAt: String? = null) { override fun toString(): String = "EnrollmentCode(id=$id, code=[redacted])" }
@Serializable data class EnrollmentCodes(val items: List<EnrollmentCode> = emptyList())
@Serializable data class BatchResult(val id: String, val status: Int, @SerialName("error_code") val errorCode: String? = null)
@Serializable data class BatchResults(val results: List<BatchResult> = emptyList())

@Serializable
data class SavedAccount(
    val id: String = UUID.randomUUID().toString(),
    val profile: ServerProfile,
    val username: String,
    val displayName: String = username,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: String,
) { override fun toString(): String = "SavedAccount(id=$id, credentials=[redacted])" }

/** Pure profile transitions, also used by JVM migration/concurrency tests. */
fun PersistedState.archiveActiveAccount(): PersistedState {
    val server = profile ?: return this
    val user = username ?: return this
    val access = accessToken ?: return this
    val refresh = refreshToken ?: return this
    val id = activeAccountId ?: savedAccounts.find { it.profile.apiBaseUrl == server.apiBaseUrl && it.username == user }?.id ?: UUID.randomUUID().toString()
    val account = SavedAccount(id,server,user,userDisplayName ?: user,access,refresh,accessExpiresAt ?: "1970-01-01T00:00:00Z")
    val accounts = savedAccounts.filterNot { it.id == id } + account
    require(accounts.size <= 20) { "Remove a saved server before adding more than 20 accounts" }
    return copy(activeAccountId=id,savedAccounts=accounts)
}

fun PersistedState.switchAccount(id: String): PersistedState {
    val archived = archiveActiveAccount()
    val account = archived.savedAccounts.firstOrNull { it.id == id } ?: error("Saved account no longer exists")
    return archived.copy(activeAccountId=id,profile=account.profile,lastServerUrl=account.profile.publicBaseUrl,
        username=account.username,userDisplayName=account.displayName,accessToken=account.accessToken,
        refreshToken=account.refreshToken,accessExpiresAt=account.accessExpiresAt,deviceId=null,deviceCredential=null,
        cachedConnections=emptyList(),desiredRunning=false,agentState=AgentState.OFFLINE,agentMessage="")
}

fun PersistedState.withoutActiveAccount(remove: Boolean): PersistedState {
    val archived = archiveActiveAccount()
    return PersistedState(installId=installId,lastServerUrl=profile?.publicBaseUrl ?: lastServerUrl,username=username,
        savedAccounts=archived.savedAccounts.filterNot { remove && it.id == archived.activeAccountId })
}

/** A refresh may finish after a switch. Update only the matching stored session. */
fun PersistedState.withRefreshedAccount(apiBaseUrl: String, previous: String, renewed: RefreshResponse): PersistedState {
    if (profile?.apiBaseUrl == apiBaseUrl && refreshToken == previous) {
        return copy(accessToken = renewed.accessToken, refreshToken = renewed.refreshToken,
            accessExpiresAt = renewed.accessExpiresAt).archiveActiveAccount()
    }
    return copy(savedAccounts = savedAccounts.map { account ->
        if (account.profile.apiBaseUrl == apiBaseUrl && account.refreshToken == previous)
            account.copy(accessToken = renewed.accessToken, refreshToken = renewed.refreshToken,
                accessExpiresAt = renewed.accessExpiresAt)
        else account
    })
}
