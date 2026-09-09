package io.github.zhanry.hometunnel.repository

import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.network.AdministrationApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AdminPage { OVERVIEW, USERS, USER, DEVICES, CONNECTIONS, SETTINGS, HEALTH, AUDIT }

class IssuedPassword(val username: String, val value: String, val expiresInSeconds: Long?) {
    override fun toString(): String = "IssuedPassword([redacted])"
}

data class AdminUiState(
    val page: AdminPage = AdminPage.OVERVIEW,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val errorCode: String? = null,
    val summary: AdminSummary? = null,
    val users: List<AdminUser> = emptyList(),
    val search: String = "",
    val user: AdminUser? = null,
    val userTargetId: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val devices: List<AdminDevice> = emptyList(),
    val connections: AdminConnectionList? = null,
    val settings: AdminSettings? = null,
    val health: AdminHealth? = null,
    val audit: AdminAuditList? = null,
    val issuedPassword: IssuedPassword? = null,
)

/** Administrative data and temporary passwords stay in memory and are cleared with the session. */
class AdminRepository(
    private val api: suspend () -> AdministrationApi,
    private val identity: () -> UserInfo?,
    private val onAccessDenied: (ApiException) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AdminUiState())
    val state = mutable.asStateFlow()
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var generation = 0
    private var readSequence = 0

    fun reset() {
        generation++
        readSequence++
        readJob?.cancel()
        writeJob?.cancel()
        mutable.value = AdminUiState()
    }

    fun dismissPassword() { mutable.value = mutable.value.copy(issuedPassword = null) }
    fun clearError() { mutable.value = mutable.value.copy(errorCode = null) }

    fun open(page: AdminPage, owner: AdminUser? = null) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(page = page, ownerId = owner?.id.orEmpty(),
            ownerName = owner?.displayName.orEmpty(), errorCode = null,
            connections = if (page == AdminPage.CONNECTIONS) null else mutable.value.connections)
        refresh()
    }

    fun back() {
        if (mutable.value.saving) return
        if (mutable.value.page == AdminPage.USER) open(AdminPage.USERS)
        else if (mutable.value.ownerId.isNotEmpty()) openUser(mutable.value.ownerId)
        else open(AdminPage.OVERVIEW)
    }

    fun openUser(id: String) = read {
        mutable.value = mutable.value.copy(page = AdminPage.USER, user = null, userTargetId = id, ownerId = "", ownerName = "")
        val user = it.adminUser(id)
        mutable.value = mutable.value.copy(user = user)
    }

    fun searchUsers(value: String) {
        mutable.value = mutable.value.copy(search = value.trim())
        refresh()
    }

    fun refresh() = read { service ->
        val before = mutable.value
        when (before.page) {
            AdminPage.OVERVIEW -> mutable.value = mutable.value.copy(summary = service.adminSummary())
            AdminPage.USERS -> mutable.value = mutable.value.copy(users = service.adminUsers(before.search).items)
            AdminPage.USER -> if (before.userTargetId.isNotEmpty()) mutable.value = mutable.value.copy(user = service.adminUser(before.userTargetId))
            AdminPage.DEVICES -> mutable.value = mutable.value.copy(devices = service.adminDevices(before.ownerId).items)
            AdminPage.CONNECTIONS -> mutable.value = mutable.value.copy(connections = service.adminConnections(before.ownerId, "", before.connections?.page ?: 1))
            AdminPage.SETTINGS -> mutable.value = mutable.value.copy(settings = service.adminSettings())
            AdminPage.HEALTH -> mutable.value = mutable.value.copy(health = service.adminHealth())
            AdminPage.AUDIT -> mutable.value = mutable.value.copy(audit = service.adminAudit(before.audit?.page ?: 1))
        }
    }

    fun connectionPage(page: Int) = read { service ->
        mutable.value = mutable.value.copy(connections = service.adminConnections(mutable.value.ownerId, "", page))
    }
    fun auditPage(page: Int) = read { service -> mutable.value = mutable.value.copy(audit = service.adminAudit(page)) }

    fun createUser(username: String, displayName: String, onSuccess: () -> Unit) = write(onSuccess) { service ->
        val result = service.adminCreateUser(username, displayName)
        mutable.value = mutable.value.copy(users = result.user?.let { listOf(it) + mutable.value.users } ?: mutable.value.users,
            issuedPassword = IssuedPassword(result.user?.username ?: username, result.temporaryPassword, result.expiresInSeconds))
    }

    fun editUser(user: AdminUser, displayName: String, onSuccess: () -> Unit) = write(onSuccess) { service ->
        replaceUser(service.adminUpdateUser(user.id, displayName, user.version))
    }

    fun setEnabled(user: AdminUser, enabled: Boolean, onSuccess: () -> Unit) = write(onSuccess) { service ->
        requireOrdinaryUser(user)
        replaceUser(service.adminSetUserEnabled(user.id, enabled))
    }

    fun resetPassword(user: AdminUser, onSuccess: () -> Unit) = write(onSuccess) { service ->
        requireOrdinaryUser(user)
        val result = service.adminResetPassword(user.id)
        mutable.value = mutable.value.copy(page = AdminPage.USERS, user = null,
            users = mutable.value.users.map { if (it.id == user.id) it.copy(passwordState = "must_change") else it },
            issuedPassword = IssuedPassword(user.username, result.temporaryPassword, result.expiresInSeconds))
    }

    fun deleteUser(user: AdminUser, onSuccess: () -> Unit) = write(onSuccess) { service ->
        requireOrdinaryUser(user)
        service.adminDeleteUser(user.id, user.version)
        mutable.value = mutable.value.copy(page = AdminPage.USERS, user = null,
            users = mutable.value.users.filterNot { it.id == user.id }, devices = emptyList(), connections = null)
    }

    fun saveSettings(value: AdminSettings, onSuccess: () -> Unit) = write(onSuccess) { service ->
        require(value.prefixPolicy in setOf("off", "suggest", "enforce"))
        mutable.value = mutable.value.copy(settings = service.adminSaveSettings(value))
    }

    private fun replaceUser(user: AdminUser) {
        mutable.value = mutable.value.copy(user = user, users = mutable.value.users.map { if (it.id == user.id) user else it })
    }

    private fun requireOrdinaryUser(user: AdminUser) {
        if (user.isAdministrator || user.id == identity()?.id) throw ApiException(409, "ADMIN_SINGLETON", "Administrator is protected")
    }

    private fun requireAdmin() {
        val actor = identity()
        if (actor?.role != "admin" || actor.passwordState != "normal" || actor.deviceId != null) throw ApiException(403, "FORBIDDEN", "Administrator session required")
    }

    private fun read(block: suspend (AdministrationApi) -> Unit) {
        if (mutable.value.saving) return
        readJob?.cancel()
        val started = generation
        val sequence = ++readSequence
        readJob = scope.launch {
            mutable.value = mutable.value.copy(loading = true, errorCode = null)
            try {
                requireAdmin()
                block(api())
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { if (started == generation && sequence == readSequence) failed(error)
            } finally { if (started == generation && sequence == readSequence) mutable.value = mutable.value.copy(loading = false) }
        }
    }

    private fun write(onSuccess: () -> Unit, block: suspend (AdministrationApi) -> Unit) {
        if (mutable.value.saving) return
        readJob?.cancel()
        readSequence++
        val started = generation
        writeJob = scope.launch {
            mutable.value = mutable.value.copy(saving = true, loading = false, errorCode = null)
            try {
                requireAdmin()
                block(api())
                if (started == generation) onSuccess()
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { if (started == generation) failed(error)
            } finally { if (started == generation) mutable.value = mutable.value.copy(saving = false) }
        }
    }

    private fun failed(error: Throwable) {
        if (error is ApiException && error.statusCode in setOf(401, 403, 423)) {
            reset()
            onAccessDenied(error)
        }
        mutable.value = mutable.value.copy(errorCode = (error as? ApiException)?.errorCode ?: "NETWORK_ERROR")
    }
}
