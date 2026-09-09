package io.github.zhanry.hometunnel.network

import io.github.zhanry.hometunnel.model.*

interface AdministrationApi {
    suspend fun adminSummary(): AdminSummary
    suspend fun adminUsers(search: String): AdminUserList
    suspend fun adminUser(id: String): AdminUser
    suspend fun adminCreateUser(username: String, displayName: String): AdminPasswordResponse
    suspend fun adminUpdateUser(id: String, displayName: String, version: Long): AdminUser
    suspend fun adminSetUserEnabled(id: String, enabled: Boolean): AdminUser
    suspend fun adminResetPassword(id: String): AdminPasswordResponse
    suspend fun adminDeleteUser(id: String, version: Long)
    suspend fun adminDevices(userId: String): AdminDeviceList
    suspend fun adminConnections(userId: String, search: String, page: Int): AdminConnectionList
    suspend fun adminSettings(): AdminSettings
    suspend fun adminSaveSettings(settings: AdminSettings): AdminSettings
    suspend fun adminHealth(): AdminHealth
    suspend fun adminAudit(page: Int): AdminAuditList
}
