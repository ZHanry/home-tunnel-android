package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.BuildConfig
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.model.ApiErrorBody
import io.github.zhanry.hometunnel.model.ApiException
import io.github.zhanry.hometunnel.model.ConnectionListResponse
import io.github.zhanry.hometunnel.model.DeviceRegistration
import io.github.zhanry.hometunnel.model.PersistedState
import io.github.zhanry.hometunnel.model.RefreshResponse
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.SessionResponse
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.model.AdminUser
import io.github.zhanry.hometunnel.model.AdminUserList
import io.github.zhanry.hometunnel.model.AdminPasswordResponse
import io.github.zhanry.hometunnel.model.AdminSummary
import io.github.zhanry.hometunnel.model.AdminSettings
import io.github.zhanry.hometunnel.model.AdminHealth
import io.github.zhanry.hometunnel.model.AdminDeviceList
import io.github.zhanry.hometunnel.model.AdminConnectionList
import io.github.zhanry.hometunnel.model.AdminAuditList
import io.github.zhanry.hometunnel.model.SyncResponse
import io.github.zhanry.hometunnel.model.TunnelConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class HomeTunnelApi(
    private val profile: ServerProfile,
    private val sessionManager: SessionManager = SessionManager(),
    clientOverride: OkHttpClient? = null,
) : AdministrationApi {
    companion object {
        const val MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val baseUrl = profile.apiBaseUrl.toHttpUrl()
    private val client = clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun currentUser(): UserInfo = authenticatedJson("GET", "auth/me")

    /** Only account-authenticated RD bootstrap routes; RD tokens use their own DPoP transport. */
    suspend fun remoteAccountRequest(method: String, path: String, body: JsonObject? = null): JsonObject {
        require((method == "POST" && path in setOf("rd/reauth", "rd/enrollment-challenges", "rd/endpoints", "rd/token-challenges", "rd/tokens")) ||
            (method == "GET" && path == "rd/endpoints")) { "Unsupported RD account operation" }
        return authenticatedJson(method, path, body)
    }

    override suspend fun adminSummary(): AdminSummary = authenticatedJson("GET", "admin/summary")
    override suspend fun adminUsers(search: String): AdminUserList =
        authenticatedJson("GET", "admin/users?search=${queryValue(search.trim())}")
    override suspend fun adminUser(id: String): AdminUser = authenticatedJson("GET", "admin/users/${pathId(id)}")
    override suspend fun adminCreateUser(username: String, displayName: String): AdminPasswordResponse =
        authenticatedJson("POST", "admin/users", buildJsonObject {
            put("username", username.trim()); put("display_name", displayName.trim()); put("role", "user")
        })
    override suspend fun adminUpdateUser(id: String, displayName: String, version: Long): AdminUser =
        authenticatedJson("PATCH", "admin/users/${pathId(id)}", buildJsonObject {
            put("display_name", displayName.trim()); put("expected_version", version)
        }, version)
    override suspend fun adminSetUserEnabled(id: String, enabled: Boolean): AdminUser =
        authenticatedJson("POST", "admin/users/${pathId(id)}/${if (enabled) "enable" else "disable"}", buildJsonObject { })
    override suspend fun adminResetPassword(id: String): AdminPasswordResponse =
        authenticatedJson("POST", "admin/users/${pathId(id)}/reset-password", buildJsonObject { })
    override suspend fun adminDeleteUser(id: String, version: Long) {
        authenticatedJson<Unit>("DELETE", "admin/users/${pathId(id)}", buildJsonObject { put("expected_version", version) }, version)
    }
    override suspend fun adminDevices(userId: String): AdminDeviceList {
        val items = mutableListOf<AdminDevice>()
        var page = 1
        do {
            val result = authenticatedJson<AdminDeviceList>("GET", "admin/devices?user_id=${queryValue(userId)}&page=$page&page_size=100")
            items += result.items
            require(result.totalPages <= 100) { "Too many device pages; filter by account" }
            page++
        } while (page <= result.totalPages)
        return AdminDeviceList(items.distinctBy { it.id })
    }
    override suspend fun adminConnections(userId: String, search: String, page: Int): AdminConnectionList =
        authenticatedJson("GET", "admin/connections?user_id=${queryValue(userId)}&search=${queryValue(search)}&page=${page.coerceAtLeast(1)}&page_size=25")
    override suspend fun adminSettings(): AdminSettings = authenticatedJson("GET", "admin/settings")
    override suspend fun adminSaveSettings(settings: AdminSettings): AdminSettings =
        authenticatedJson("PATCH", "admin/settings", buildJsonObject {
            put("subdomain_prefix_policy", settings.prefixPolicy)
            settings.clientRawTunnelsEnabled?.let { put("client_raw_tunnels_enabled", it) }
            settings.transportTunnels?.let { pools ->
                put("transport_settings_version", requireNotNull(settings.transportSettingsVersion))
                put("transport_tunnels", buildJsonObject {
                    listOf("tcp" to pools.tcp, "udp" to pools.udp).forEach { (name, pool) ->
                        put(name, buildJsonObject {
                            put("enabled", pool.configuredEnabled)
                            put("port_start", pool.portStart)
                            put("port_end", pool.portEnd)
                        })
                    }
                })
            }
        })
    override suspend fun adminHealth(): AdminHealth = authenticatedJson("GET", "admin/system/health")
    override suspend fun adminAudit(page: Int): AdminAuditList =
        authenticatedJson("GET", "admin/audit-events?page=${page.coerceAtLeast(1)}&page_size=25")

    suspend fun mfaStatus(): MfaStatus = authenticatedJson("GET", "auth/mfa")
    suspend fun sessions(): ManagementSessions = authenticatedJson("GET", "auth/sessions")
    suspend fun revokeSession(id: String) = authenticatedJson<Unit>("DELETE", "auth/sessions/${pathId(id)}")
    suspend fun mfaSetup(password: String): MfaSetup = authenticatedJson("POST", "auth/mfa/setup", credentials(password, ""))
    suspend fun mfaConfirm(password: String, code: String): RecoveryCodes = authenticatedJson("POST", "auth/mfa/confirm", buildJsonObject {
        put("password", password); put("code", code.trim())
    })
    suspend fun replaceRecoveryCodes(password: String, code: String): RecoveryCodes = authenticatedJson("POST", "auth/mfa/recovery-codes", credentials(password, code))
    suspend fun mfaDisable(password: String, code: String) = authenticatedJson<Unit>("POST", "auth/mfa/disable", credentials(password, code))
    suspend fun enrollmentCodes(): EnrollmentCodes = authenticatedJson("GET", "client/enrollment-codes")
    suspend fun createEnrollmentCode(name: String): EnrollmentCode = authenticatedJson("POST", "client/enrollment-codes", buildJsonObject { put("name", name.trim()) })
    suspend fun revokeEnrollmentCode(id: String) = authenticatedJson<Unit>("DELETE", "client/enrollment-codes/${pathId(id)}")
    suspend fun updateDeviceMetadata(device: ManagedDevice, tags: List<String>, favorite: Boolean) = authenticatedJson<JsonObject>("PATCH", "client/devices/${pathId(device.id)}/metadata", buildJsonObject {
        put("tags", JsonArray(tags.map(::JsonPrimitive))); put("favorite", favorite); put("expected_metadata_version", device.metadataVersion)
    })
    suspend fun batchConnections(items: List<TunnelConnection>, enabled: Boolean): BatchResults = authenticatedJson("POST", "client/connections/batch", buildJsonObject {
        put("enabled", enabled); put("items", buildJsonArray { items.forEach { add(buildJsonObject { put("id", it.id); put("expected_version", it.version) }) } })
    })
    private fun credentials(password: String, code: String) = buildJsonObject {
        put("password", password); if (code.isNotBlank()) put("mfa_code", code.trim())
    }

    private fun queryValue(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private fun pathId(value: String): String {
        require(value.isNotBlank() && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Invalid resource identifier" }
        return value
    }

    suspend fun login(username: String, password: String, mfaCode: String = ""): SessionResponse {
        val response: SessionResponse = publicJson(
            "auth/login",
            buildJsonObject {
                put("username", username)
                put("password", password)
                put("client_type", "android")
                if (mfaCode.isNotBlank()) put("mfa_code", mfaCode.trim())
            },
        )
        sessionManager.install(response)
        return response
    }

    suspend fun changePassword(currentPassword: String, newPassword: String, mfaCode: String = "") {
        authenticatedJson<Unit>(
            method = "POST",
            path = "auth/password/change",
            body = buildJsonObject {
                put("current_password", currentPassword)
                put("new_password", newPassword)
                if (mfaCode.isNotBlank()) put("mfa_code", mfaCode.trim())
            },
        )
        sessionManager.clear()
    }

    fun restoreSession(accessToken: String, refreshToken: String, accessExpiresAt: String) {
        sessionManager.install(
            SessionResponse(
                user = UserInfo("", "", "", "user", "normal"),
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessExpiresAt = accessExpiresAt,
                refreshExpiresAt = accessExpiresAt,
            ),
        )
    }

    suspend fun listDevices(): List<ManagedDevice> {
        val result = mutableListOf<ManagedDevice>()
        var page = 1
        do {
            val response = authenticatedJson<DeviceListResponse>("GET", "client/devices?page=$page&page_size=100")
            result += response.items
            require(response.totalPages <= 100) { "Too many device pages" }
            page++
        } while (page <= response.totalPages)
        return result.distinctBy { it.id }
    }

    suspend fun connectionCatalog(): ConnectionListResponse {
        val result = mutableListOf<TunnelConnection>()
        var page = 1
        var capabilities = ConnectionCapabilities()
        do {
            val response = authenticatedJson<ConnectionListResponse>("GET", "client/connections?page=$page&page_size=100")
            result += response.items
            capabilities = response.capabilities
            require(response.totalPages <= 100) { "Too many connection pages" }
            page++
        } while (page <= response.totalPages)
        return ConnectionListResponse(items=result.distinctBy { it.id }, capabilities=capabilities)
    }

    suspend fun listConnections(): List<TunnelConnection> = connectionCatalog().items

    suspend fun createConnection(deviceId: String, value: TunnelConnection): TunnelConnection =
        authenticatedJson(
            method = "POST",
            path = "client/connections",
            body = buildJsonObject {
                put("device_id", deviceId)
                put("name", value.name)
                if (value.kind == ProxyKind.HTTP) put("subdomain", value.subdomain)
                put("local_scheme", value.localScheme)
                put("local_host", value.localHost)
                put("local_port", value.localPort)
                put("enabled", value.enabled)
                put("proxy_type", value.proxyType)
                value.applicationProtocol?.let { put("application_protocol", it) }
            },
        )

    suspend fun updateConnection(value: TunnelConnection, baseline: TunnelConnection? = null): TunnelConnection = authenticatedJson(
        method = "PATCH",
        path = "client/connections/${value.id}",
        body = buildJsonObject {
            if (baseline == null || baseline.name != value.name) put("name", value.name)
            if (value.kind == ProxyKind.HTTP && (baseline == null || baseline.subdomain != value.subdomain)) put("subdomain", value.subdomain)
            if (value.kind == ProxyKind.HTTP && (baseline == null || baseline.localScheme != value.localScheme)) put("local_scheme", value.localScheme)
            if (baseline == null || baseline.localHost != value.localHost) put("local_host", value.localHost)
            if (baseline == null || baseline.localPort != value.localPort) put("local_port", value.localPort)
            if (baseline == null || baseline.enabled != value.enabled) put("enabled", value.enabled)
            put("expected_version", value.version)
        },
        expectedVersion = value.version,
    )

    suspend fun deleteConnection(value: TunnelConnection) {
        authenticatedJson<Unit>(
            method = "DELETE",
            path = "client/connections/${value.id}",
            body = buildJsonObject { put("expected_version", value.version) },
            expectedVersion = value.version,
        )
    }

    suspend fun logout() {
        try {
            authenticatedJson<Unit>("POST", "auth/logout", buildJsonObject { })
        } finally {
            sessionManager.clear()
        }
    }

    fun clearSession() = sessionManager.clear()

    fun hasSession(): Boolean = sessionManager.hasSession()

    private suspend fun refresh(refreshToken: String): RefreshResponse = publicJson(
        "auth/refresh",
        buildJsonObject {
            put("refresh_token", refreshToken)
            put("client_type", "android")
        },
    )

    private suspend inline fun <reified T> publicJson(path: String, body: JsonObject): T =
        withContext(Dispatchers.IO) {
            execute<T>(RequestSpec("POST", path, body, null, null))
        }

    private suspend inline fun <reified T> authenticatedJson(
        method: String,
        path: String,
        body: JsonObject? = null,
        expectedVersion: Long? = null,
    ): T = withContext(Dispatchers.IO) {
        var token = sessionManager.accessToken(::refresh)
        try {
            execute<T>(RequestSpec(method, path, body, token, expectedVersion))
        } catch (error: ApiException) {
            if (error.statusCode != 401 || error.errorCode !in setOf("SESSION_REVOKED", "SESSION_EXPIRED", "AUTH_REQUIRED", "AUTH_EXPIRED", "TOKEN_EXPIRED")) throw error
            token = sessionManager.refreshAfterUnauthorized(token, ::refresh)
            execute<T>(RequestSpec(method, path, body, token, expectedVersion))
        }
    }

    private inline fun <reified T> execute(spec: RequestSpec): T {
        val url = baseUrl.resolve(spec.path) ?: throw IOException("Invalid API path")
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "HomeTunnel-Android/${BuildConfig.VERSION_NAME}")
            .header("X-Request-Id", UUID.randomUUID().toString())
        spec.token?.let { requestBuilder.header("Authorization", "Bearer $it") }
        spec.expectedVersion?.let { requestBuilder.header("If-Match", "\"$it\"") }
        val requestBody = spec.body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        when (spec.method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
            "PATCH" -> requestBuilder.patch(requireNotNull(requestBody))
            "DELETE" -> if (requestBody == null) requestBuilder.delete() else requestBuilder.delete(requestBody)
            else -> throw IllegalArgumentException("Unsupported method ${spec.method}")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.readLimited(MAXIMUM_RESPONSE_BYTES) ?: ByteArray(0)
            if (!response.isSuccessful) {
                val error = runCatching { json.decodeFromString<ApiErrorBody>(bytes.decodeToString()) }
                    .getOrElse { ApiErrorBody(message = "HTTP ${response.code}") }
                throw ApiException(response.code, error.errorCode, error.message)
            }
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                return Unit as T
            }
            if (bytes.isEmpty()) throw IOException("Control-center returned an empty response")
            if (spec.path.startsWith("rd/")) {
                // Bootstrap security responses share the strict RD parser, including duplicate-key rejection.
                @Suppress("UNCHECKED_CAST")
                return io.github.zhanry.hometunnel.remote.RemoteJson.parse(bytes) as T
            }
            return json.decodeFromString(bytes.decodeToString())
        }
    }

    private fun java.io.InputStream.readLimited(maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) throw IOException("Control-center response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class RequestSpec(
        val method: String,
        val path: String,
        val body: JsonObject?,
        val token: String?,
        val expectedVersion: Long?,
    )
}

internal fun requestedConfigVersion(state: PersistedState, forceFull: Boolean): Long =
    if (forceFull) 0 else state.syncRequestConfigVersion
