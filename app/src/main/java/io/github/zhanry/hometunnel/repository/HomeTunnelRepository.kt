package io.github.zhanry.hometunnel.repository

import android.content.Context
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.model.AgentState
import io.github.zhanry.hometunnel.model.ApiException
import io.github.zhanry.hometunnel.model.PersistedState
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.ServerProfile
import io.github.zhanry.hometunnel.model.SessionResponse
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.model.UserInfo
import io.github.zhanry.hometunnel.network.HomeTunnelApi
import io.github.zhanry.hometunnel.network.ServerDiscovery
import io.github.zhanry.hometunnel.network.SessionManager
import io.github.zhanry.hometunnel.storage.SecureStateStore
import io.github.zhanry.hometunnel.storage.StateUnavailableException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppScreen { LOADING, LOGIN, PASSWORD_CHANGE, HOME }

data class AppUiState(
    val screen: AppScreen = AppScreen.LOADING,
    val persisted: PersistedState = PersistedState(),
    val connections: List<TunnelConnection> = emptyList(),
    val devices: List<io.github.zhanry.hometunnel.model.ManagedDevice> = emptyList(),
    val capabilities: ConnectionCapabilities = ConnectionCapabilities(),
    val busy: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: String? = null,
    val stale: Boolean = false,
    val currentUser: UserInfo? = null,
) {
    val isAdmin: Boolean get() = currentUser?.role == "admin" && currentUser.passwordState == "normal" && currentUser.deviceId == null
}


class HomeTunnelRepository(
    private val context: Context,
    val store: SecureStateStore,
    private val apiFactory: (ServerProfile, SessionManager) -> HomeTunnelApi = { profile, sessions -> HomeTunnelApi(profile, sessions) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()
    private val refreshMutex = Mutex()
    @Volatile private var sessionGeneration = 0
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val administration: AdminRepository = AdminRepository(
        api = { ensureSignedInApi(_uiState.value.persisted) },
        identity = { _uiState.value.currentUser },
        onAccessDenied = { error ->
            _uiState.value = _uiState.value.copy(currentUser = null)
            scope.launch {
                if (error.statusCode == 403) refreshConnections(silent = true) else setFailure(error)
            }
        },
    )

    @Volatile
    private var api: HomeTunnelApi? = null
    private var pendingLogin: PendingLogin? = null

    init {
        scope.launch {
            var state = try {
                store.load()
            } catch (error: StateUnavailableException) {
                _uiState.value = AppUiState(screen = AppScreen.LOGIN, error = error.message)
                return@launch
            }
            if (state.enrolled) {
                state = state.copy(deviceId = null, deviceCredential = null, accessToken = null,
                    refreshToken = null, cachedConnections = emptyList(), desiredRunning = false)
                store.save(state)
            }
            state = state.archiveActiveAccount()
            store.save(state)
            _uiState.value = AppUiState(
                screen = if (state.signedIn) AppScreen.HOME else AppScreen.LOGIN,
                persisted = state,
                connections = state.cachedConnections,
            )
            if (state.signedIn) refreshConnections(silent = true)
        }
    }

    fun login(server: String, username: String, password: String, mfaCode: String = "") = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            try {
                sessionGeneration++
                administration.reset()
                val profile = ServerDiscovery.discover(server)
                val newApi = managementApi(profile)
                val session = newApi.login(username.trim(), password, mfaCode)
                api = newApi
                if (session.passwordChangeRequired) {
                    pendingLogin = PendingLogin(profile, username.trim())
                    _uiState.value = _uiState.value.copy(
                        screen = AppScreen.PASSWORD_CHANGE,
                        busy = false,
                        error = null,
                    )
                } else {
                    enterManagement(profile, session)
                }
            } catch (error: Throwable) {
                setFailure(error)
            }
        }
    }

    fun changeRequiredPassword(currentPassword: String, newPassword: String, mfaCode: String = "") = scope.launch {
        operationMutex.withLock {
            if (pendingLogin == null) return@withLock setFailure(IllegalStateException("Login session expired"))
            val activeApi = api ?: return@withLock setFailure(IllegalStateException("Login session expired"))
            setBusy(true)
            try {
                activeApi.changePassword(currentPassword, newPassword, mfaCode)
                pendingLogin = null
                api = null
                _uiState.value = _uiState.value.copy(screen = AppScreen.LOGIN, busy = false,
                    error = "Password changed. Sign in with the new password and a new authenticator code.")
            } catch (error: Throwable) {
                setFailure(error)
            }
        }
    }

    fun cancelPasswordChange() {
        pendingLogin = null
        api?.clearSession()
        api = null
        _uiState.value = _uiState.value.copy(screen = AppScreen.LOGIN, busy = false, error = null)
    }

    fun refreshConnections(silent: Boolean = false) = scope.launch {
        if (_uiState.value.busy || !_uiState.value.persisted.signedIn) return@launch
        if (!refreshMutex.tryLock()) return@launch
        val started = sessionGeneration
        if (!silent) setBusy(true)
        try {
            val state = _uiState.value.persisted
            val activeApi = ensureSignedInApi(state)
            val currentUser = activeApi.currentUser()
            if (started != sessionGeneration) return@launch
            if (_uiState.value.currentUser?.id != currentUser.id || currentUser.role != "admin" ||
                currentUser.passwordState != "normal" || currentUser.deviceId != null) administration.reset()
            _uiState.value = _uiState.value.copy(currentUser = currentUser)
            val devices = activeApi.listDevices()
            val catalog = activeApi.connectionCatalog()
            val items = catalog.items
            if (started != sessionGeneration) return@launch
            val persisted = store.update { if (started == sessionGeneration) it.copy(cachedConnections = items) else it }
            if (started != sessionGeneration) return@launch
            _uiState.value = _uiState.value.copy(persisted = persisted, connections = items, devices = devices, capabilities = catalog.capabilities, busy = false, error = null, lastSyncedAt = Instant.now().toString(), stale = false)
        } catch (error: Throwable) {
            if (started != sessionGeneration) return@launch
            _uiState.value = _uiState.value.copy(stale = true)
            if (!silent || error is ApiException && (error.statusCode == 401 || error.statusCode == 423)) setFailure(error)
        } finally {
            refreshMutex.unlock()
        }
    }

    fun saveConnection(value: TunnelConnection, isNew: Boolean, baseline: TunnelConnection? = null, onSuccess: () -> Unit = {}) = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            try {
                require(value.kind != ProxyKind.UNKNOWN) { "Unknown connection types are read-only" }
                val state = _uiState.value.persisted
                val activeApi = ensureSignedInApi(state)
                if (isNew) {
                    require(_uiState.value.capabilities.permits(value.kind)) { "Server has not enabled this transport for your account" }
                    activeApi.createConnection(value.deviceId, value)
                } else {
                    activeApi.updateConnection(value, baseline)
                }
                // A successful write must not be reported as failed when the follow-up read fails.
                val optimistic = if (isNew) _uiState.value.connections else _uiState.value.connections.map { if (it.id == value.id) value else it }
                _uiState.value = _uiState.value.copy(connections = optimistic, busy = false, error = null)
                onSuccess()
                try {
                    val items = activeApi.listConnections()
                    _uiState.value = _uiState.value.copy(connections = items, busy = false, error = null, lastSyncedAt = Instant.now().toString(), stale = false)
                } catch (_: Throwable) {
                    _uiState.value = _uiState.value.copy(stale = true, busy = false)
                }
            } catch (error: Throwable) {
                setFailure(error)
            }
        }
    }

    fun loadConnectionVersion(id: String, onLoaded: (Long) -> Unit) = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            try {
                val items = ensureSignedInApi(_uiState.value.persisted).listConnections()
                val current = items.firstOrNull { it.id == id } ?: error("Connection no longer exists")
                _uiState.value = _uiState.value.copy(connections = items, busy = false, error = null)
                onLoaded(current.version)
            } catch (error: Throwable) { setFailure(error) }
        }
    }

    fun deleteConnection(value: TunnelConnection, onSuccess: () -> Unit = {}) = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            try {
                require(value.kind != ProxyKind.UNKNOWN) { "Unknown connection types are read-only" }
                val state = _uiState.value.persisted
                val activeApi = ensureSignedInApi(state)
                activeApi.deleteConnection(value)
                val items = _uiState.value.connections.filter { it.id != value.id }
                _uiState.value = _uiState.value.copy(connections = items, busy = false, error = null)
                onSuccess()
                refreshConnections(silent = true)
            } catch (error: Throwable) {
                setFailure(error)
            }
        }
    }

    suspend fun clearLocalState() {
        sessionGeneration++
        administration.reset()
        val cleared = store.update { it.withoutActiveAccount(remove = true) }
        api?.clearSession()
        api = null
        pendingLogin = null
        _uiState.value = AppUiState(screen = AppScreen.LOGIN, persisted = cleared)
    }

    fun logout(stopTunnel: () -> Unit) = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            stopTunnel()
            var serverRevokedOrAlreadyInvalid = false
            try {
                val state = store.load()
                if (state.signedIn) ensureSignedInApi(state).logout()
                serverRevokedOrAlreadyInvalid = true
            } catch (error: ApiException) {
                if (error.errorCode in setOf("DEVICE_REVOKED", "USER_DISABLED", "AUTH_INVALID")) {
                    serverRevokedOrAlreadyInvalid = true
                } else {
                    setFailure(error)
                }
            } catch (error: Throwable) {
                // Keep the only device credential so the user can retry revocation
                // after connectivity returns. Never describe an offline local wipe
                // as a successful server-side logout.
                setFailure(error)
            }
            if (serverRevokedOrAlreadyInvalid) {
                clearLocalState()
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun addServer() = scope.launch {
        operationMutex.withLock {
            sessionGeneration++
            administration.reset()
            val saved = store.update { it.withoutActiveAccount(remove = false) }
            api?.clearSession(); api = null; pendingLogin = null
            _uiState.value = AppUiState(screen = AppScreen.LOGIN, persisted = saved)
        }
    }

    fun switchServer(id: String) = scope.launch {
        operationMutex.withLock {
            setBusy(true)
            try {
                sessionGeneration++
                administration.reset()
                val next = store.update { it.switchAccount(id) }
                api?.clearSession(); api = null; pendingLogin = null
                _uiState.value = AppUiState(screen = AppScreen.HOME, persisted = next)
                refreshConnections(silent = true)
            } catch (error: Throwable) { setFailure(error) }
        }
    }

    fun forgetServer(id: String) = scope.launch {
        operationMutex.withLock {
            if (id == _uiState.value.persisted.activeAccountId) return@withLock
            val saved = store.update { it.copy(savedAccounts = it.savedAccounts.filterNot { account -> account.id == id }) }
            _uiState.value = _uiState.value.copy(persisted = saved)
        }
    }

    suspend fun <T> accountAction(block: suspend (HomeTunnelApi) -> T): T = operationMutex.withLock {
        val generation = sessionGeneration
        val result = block(ensureSignedInApi(_uiState.value.persisted))
        check(generation == sessionGeneration) { "Account changed; retry the operation" }
        result
    }

    private suspend fun enterManagement(profile: ServerProfile, session: SessionResponse) {
        administration.reset()
        val current = store.load().archiveActiveAccount()
        val signedIn = current.copy(
            activeAccountId = null,
            profile = profile,
            lastServerUrl = profile.publicBaseUrl,
            userDisplayName = session.user.displayName,
            username = session.user.username,
            deviceId = null,
            deviceCredential = null,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            accessExpiresAt = session.accessExpiresAt,
            cachedConnections = emptyList(),
            agentState = AgentState.OFFLINE,
            agentMessage = "Management session",
            desiredRunning = false,
        )
        val archived = signedIn.archiveActiveAccount()
        store.save(archived)
        _uiState.value = AppUiState(screen = AppScreen.HOME, persisted = archived, busy = false, currentUser = session.user.copy(deviceId = session.deviceId))
        refreshConnections(silent = true)
    }

    private suspend fun ensureSignedInApi(state: PersistedState): HomeTunnelApi {
        require(state.signedIn) { "Not signed in" }
        api?.let { existing ->
            try {
                if (existingSessionAvailable(existing)) return existing
            } catch (_: Throwable) {
                existing.clearSession()
            }
        }
        val created = managementApi(requireNotNull(state.profile))
        if (!state.refreshToken.isNullOrBlank() && !state.accessToken.isNullOrBlank()) {
            created.restoreSession(
                state.accessToken,
                state.refreshToken,
                state.accessExpiresAt ?: java.time.Instant.now().plusSeconds(120).toString(),
            )
        } else {
            error("Management session expired; sign in again")
        }
        api = created
        return created
    }

    private fun existingSessionAvailable(value: HomeTunnelApi): Boolean = value.hasSession()

    private fun managementApi(profile: ServerProfile): HomeTunnelApi {
        val generation = sessionGeneration
        return apiFactory(profile, SessionManager(onRefresh = { previous, renewed ->
            val updated = store.update { it.withRefreshedAccount(profile.apiBaseUrl, previous, renewed) }
            if (generation == sessionGeneration) _uiState.value = _uiState.value.copy(persisted = updated)
        }))
    }

    private fun setBusy(value: Boolean) {
        _uiState.value = _uiState.value.copy(busy = value, error = if (value) null else _uiState.value.error)
    }

    private suspend fun setFailure(error: Throwable) {
        if (error is ApiException && (error.errorCode in setOf("USER_DISABLED", "SESSION_REVOKED", "SESSION_EXPIRED", "PASSWORD_CHANGE_REQUIRED"))) {
            api?.clearSession()
            api = null
            clearLocalState()
        }
        val message = when (error) {
            is ApiException -> "${error.errorCode}: ${error.message}"
            else -> error.message ?: error.javaClass.simpleName
        }
        _uiState.value = _uiState.value.copy(busy = false, error = message)
    }

    private data class PendingLogin(
        val profile: ServerProfile,
        val username: String,
    )
}
