@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.zhanry.hometunnel.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.text.format.Formatter
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.repository.AdminPage
import io.github.zhanry.hometunnel.repository.AdminRepository
import io.github.zhanry.hometunnel.repository.AdminUiState
import io.github.zhanry.hometunnel.repository.IssuedPassword

@Composable
internal fun AdminWorkspace(controller: AdminRepository, consoleUrl: String) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var create by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = state.page == AdminPage.SETTINGS && draft != state.settings
    val contentKey: Any = when (state.page) {
        AdminPage.USER -> state.userTargetId
        AdminPage.USERS -> state.search
        AdminPage.DEVICES -> state.ownerId
        AdminPage.CONNECTIONS -> state.ownerId to (state.connections?.page ?: 1)
        AdminPage.AUDIT -> state.audit?.page ?: 1
        else -> Unit
    }
    val scrollState = pageScrollState(state.page, contentKey)
    val requestBack = { if (dirty) discardAction = { controller.back() } else controller.back() }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(state.page) { if (state.page != AdminPage.USER) { edit = false; action = null } }
    DisposableEffect(controller) { onDispose { controller.dismissPassword() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.dismissPassword() }
    BackHandler(enabled = state.page != AdminPage.OVERVIEW) { if (!state.saving) requestBack() }
    LazyColumn(
        state = scrollState,
        modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.page != AdminPage.OVERVIEW) IconButton(onClick = requestBack, enabled = !state.saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.admin_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(adminTitle(state.page)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (state.ownerName.isNotEmpty()) Text(state.ownerName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(enabled = !state.loading && !state.saving, onClick = {
                    if (dirty) discardAction = { controller.refresh() } else controller.refresh()
                }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh_status)) }
            }
        }
        if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.errorCode?.let { code -> item { AdminError(code) { controller.refresh() } } }
        when (state.page) {
            AdminPage.OVERVIEW -> {
                item { Text(stringResource(R.string.admin_scope), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.summary?.let { summary -> item { AdminSummaryCard(summary) } }
                val destinations = listOf(
                    Triple(AdminPage.USERS, Icons.Default.People, R.string.admin_users_hint),
                    Triple(AdminPage.DEVICES, Icons.Default.Devices, R.string.admin_devices_hint),
                    Triple(AdminPage.CONNECTIONS, Icons.Default.Link, R.string.admin_connections_hint),
                    Triple(AdminPage.SETTINGS, Icons.Default.Tune, R.string.admin_settings_hint),
                    Triple(AdminPage.HEALTH, Icons.Default.MonitorHeart, R.string.admin_health_hint),
                    Triple(AdminPage.AUDIT, Icons.Default.History, R.string.admin_audit_hint),
                )
                items(destinations) { (page, icon, hint) ->
                    AdminDestination(stringResource(adminTitle(page)), stringResource(hint), icon) { controller.open(page) }
                }
                item { OutlinedButton(onClick = {
                    val uri = Uri.parse(consoleUrl)
                    if (uri.scheme == "https" && !uri.host.isNullOrBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_web_console)) } }
            }
            AdminPage.USERS -> {
                item {
                    var search by remember(state.search) { mutableStateOf(state.search) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.admin_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.searchUsers(search) }, enabled = !state.loading && !state.saving,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_search_action)) }
                            Button(onClick = { create = true; controller.clearError() }, enabled = !state.saving,
                                modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.admin_new_user)) }
                        }
                    }
                }
                if (state.users.isEmpty() && !state.loading) item { Text(stringResource(R.string.admin_no_results)) }
                items(state.users, key = { it.id }) { user -> AdminUserCard(user) { controller.openUser(user.id) } }
                if (state.users.size >= 100) item { Text(stringResource(R.string.admin_users_limit)) }
            }
            AdminPage.USER -> state.user?.let { user ->
                item { AdminUserCard(user) }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { edit = true; controller.clearError() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_edit)) }
                    OutlinedButton(onClick = { controller.open(AdminPage.DEVICES, user) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_view_devices)) }
                    OutlinedButton(onClick = { controller.open(AdminPage.CONNECTIONS, user) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_view_connections)) }
                } }
                if (user.isAdministrator) item { Text(stringResource(R.string.admin_protected), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider()
                        OutlinedButton(onClick = { action = "reset"; controller.clearError() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_reset)) }
                        OutlinedButton(onClick = { action = if (user.status == "active") "disable" else "enable"; controller.clearError() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (user.status == "active") R.string.admin_disable else R.string.admin_enable)) }
                        TextButton(onClick = { action = "delete"; controller.clearError() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.admin_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            AdminPage.DEVICES -> {
                if (state.devices.isEmpty() && !state.loading) item { Text(stringResource(R.string.admin_no_results)) }
                items(state.devices, key = { it.id }) { device ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.admin_owner, device.username))
                        Text(stringResource(if (device.status == "revoked") R.string.admin_revoked else if (device.online) R.string.admin_online else R.string.admin_offline))
                        device.clientVersion?.let { Text(stringResource(R.string.admin_client_version, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                }
                if (state.devices.size >= 200) item { Text(stringResource(R.string.admin_devices_limit)) }
            }
            AdminPage.CONNECTIONS -> {
                val result = state.connections
                if (result?.items.isNullOrEmpty() && !state.loading) item { Text(stringResource(R.string.admin_no_results)) }
                items(result?.items.orEmpty(), key = { it.id }) { connection ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(connection.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.admin_owner, connection.username))
                        Text("${connection.proxyType.uppercase()} · ${stringResource(connectionStatusLabel(connection))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (connection.endpoint.isNotEmpty()) {
                            Text(connection.endpoint)
                            TextButton(onClick = { copyAdminText(context, connection.endpoint, false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.copy_address))
                            }
                        }
                    } }
                }
                result?.let { item { AdminPagination(it.page, it.totalPages, state.loading, controller::connectionPage) } }
            }
            AdminPage.SETTINGS -> draft?.let { value -> item {
                AdminSettingsForm(value, state.settings, state.saving, { draft = it }) {
                    controller.saveSettings(value) { Toast.makeText(context, R.string.admin_settings_saved, Toast.LENGTH_SHORT).show() }
                }
            } }
            AdminPage.HEALTH -> state.health?.let { health ->
                item { Text(stringResource(healthLabel(health.status)), style = MaterialTheme.typography.titleLarge) }
                items(health.components, key = { it.component }) { component ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(componentLabel(component.component)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(healthLabel(component.status)))
                        component.version?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                }
            }
            AdminPage.AUDIT -> {
                val result = state.audit
                if (result?.items.isNullOrEmpty() && !state.loading) item { Text(stringResource(R.string.admin_no_results)) }
                items(result?.items.orEmpty(), key = { it.id }) { event ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(auditLabel(event.action), style = MaterialTheme.typography.titleMedium)
                        Text(localAuditTime(event.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${event.targetType} · ${event.targetId.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                result?.let { item { AdminPagination(it.page, it.totalPages, state.loading, controller::auditPage) } }
            }
        }
    }
    if (create || edit && state.user != null) AdminUserEditor(if (create) null else state.user, state, controller,
        onDismiss = { create = false; edit = false }, onSave = { username, name ->
            if (create) controller.createUser(username, name) { create = false }
            else state.user?.let { controller.editUser(it, name) { edit = false } }
        })
    action?.let { requested -> state.user?.let { user -> AdminUserAction(user, requested, state, controller,
        onDismiss = { action = null }, onConfirm = {
            when (requested) {
                "delete" -> controller.deleteUser(user) { action = null }
                "reset" -> controller.resetPassword(user) { action = null }
                else -> controller.setEnabled(user, requested == "enable") { action = null }
            }
        }) } }
    state.issuedPassword?.let { PasswordIssuedDialog(it) { controller.dismissPassword(); controller.refresh() } }
    discardAction?.let { continuation -> AlertDialog(onDismissRequest = { discardAction = null },
        title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.admin_discard_warning)) },
        confirmButton = { TextButton(onClick = { draft = state.settings; discardAction = null; continuation() }) { Text(stringResource(R.string.admin_discard)) } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text(stringResource(R.string.keep_editing)) } }) }
}

@Composable
private fun AdminDestination(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}

@Composable
private fun AdminSummaryCard(value: AdminSummary) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.admin_user_count, value.users), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.admin_online_devices, value.onlineDevices))
            Text(stringResource(R.string.admin_connection_count, value.connections))
            Text(stringResource(R.string.admin_online_connections, value.onlineConnections))
            Text(stringResource(R.string.admin_transfer, bytesText(value.upload24h), bytesText(value.download24h)))
            if (value.errors > 0) Text(stringResource(R.string.admin_errors, value.errors), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AdminUserCard(user: AdminUser, onClick: (() -> Unit)? = null) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(user.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (user.isAdministrator) R.string.admin_role_admin else R.string.admin_role_user), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(if (user.status == "active") R.string.admin_active else R.string.admin_disabled))
            }
            Text("${stringResource(R.string.admin_device_count, user.deviceCount)} · ${stringResource(R.string.admin_connection_count, user.connectionCount)}")
            Text(stringResource(R.string.admin_usage, bytesText(user.monthToDateBytes), user.monthlyQuotaBytes?.let { bytesText(it) } ?: stringResource(R.string.admin_unlimited)))
            if (user.quotaSuspended) Text(stringResource(R.string.admin_quota_suspended), color = MaterialTheme.colorScheme.error)
            if (user.passwordState == "must_change") Text(stringResource(R.string.admin_must_change), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) OutlinedCard(modifier = Modifier.fillMaxWidth(), content = content)
    else OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun AdminSettingsForm(value: AdminSettings, saved: AdminSettings?, busy: Boolean, onChange: (AdminSettings) -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.admin_prefix_policy), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.admin_prefix_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("off" to R.string.admin_prefix_off, "suggest" to R.string.admin_prefix_suggest, "enforce" to R.string.admin_prefix_enforce).forEach { (key, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value.prefixPolicy == key, enabled = !busy, role = Role.RadioButton,
                onClick = { onChange(value.copy(prefixPolicy = key)) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value.prefixPolicy == key, onClick = null, enabled = !busy)
                Spacer(Modifier.width(12.dp)); Text(stringResource(label), modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider()
        if (value.clientRawTunnelsEnabled != null) {
            val permissionLabel = stringResource(R.string.admin_raw_permission)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.admin_raw_permission), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = value.clientRawTunnelsEnabled, enabled = !busy,
                    onCheckedChange = { onChange(value.copy(clientRawTunnelsEnabled = it)) },
                    modifier = Modifier.semantics { contentDescription = permissionLabel })
            }
            Text(stringResource(R.string.admin_raw_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(stringResource(R.string.admin_raw_upgrade), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSave, enabled = !busy && value != saved, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun AdminPagination(page: Int, total: Int, busy: Boolean, onPage: (Int) -> Unit) {
    if (total <= 1) return
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.admin_page, page, total))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 1, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_previous)) }
            OutlinedButton(onClick = { onPage(page + 1) }, enabled = !busy && page < total, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_next)) }
        }
    }
}

@Composable
private fun AdminError(code: String, onRefresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(adminErrorLabel(code)), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_load_latest)) }
        }
    }
}

@Composable private fun bytesText(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

private fun adminTitle(page: AdminPage): Int = when (page) {
    AdminPage.OVERVIEW -> R.string.admin_overview
    AdminPage.USERS, AdminPage.USER -> R.string.admin_users
    AdminPage.DEVICES -> R.string.admin_devices
    AdminPage.CONNECTIONS -> R.string.admin_connections
    AdminPage.SETTINGS -> R.string.admin_settings
    AdminPage.HEALTH -> R.string.admin_health
    AdminPage.AUDIT -> R.string.admin_audit
}

private fun adminErrorLabel(code: String): Int = when (code) {
    "VERSION_CONFLICT" -> R.string.admin_error_conflict
    "FORBIDDEN", "AUTH_INVALID", "SESSION_REVOKED", "PASSWORD_CHANGE_REQUIRED" -> R.string.admin_error_forbidden
    "ADMIN_SINGLETON", "STATE_CONFLICT" -> R.string.admin_error_protected
    "VALIDATION_ERROR", "USERNAME_EXISTS", "USERNAME_TAKEN", "RESOURCE_CONFLICT" -> R.string.admin_error_validation
    "NOT_FOUND" -> R.string.admin_error_missing
    "NETWORK_ERROR" -> R.string.admin_error_network
    else -> R.string.admin_error_generic
}

private fun healthLabel(status: String): Int = when (status) {
    "healthy" -> R.string.admin_healthy
    "degraded" -> R.string.admin_degraded
    "unhealthy" -> R.string.admin_unhealthy
    else -> R.string.admin_unknown
}

private fun connectionStatusLabel(value: AdminConnection): Int = when {
    !value.enabled -> R.string.admin_disabled
    value.state == "Online" -> R.string.admin_online
    value.state in setOf("Pending", "Applying", "Starting") -> R.string.admin_pending
    value.state in setOf("Error", "Degraded") -> R.string.admin_degraded
    value.state == "Offline" -> R.string.admin_offline
    else -> R.string.admin_unknown
}

private fun localAuditTime(value: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
}.getOrDefault(value)

private fun componentLabel(component: String): Int = when (component) {
    "control-center" -> R.string.admin_component_control
    "sqlite" -> R.string.admin_component_database
    "outbox" -> R.string.admin_component_events
    "gateway", "traffic-gateway" -> R.string.admin_component_gateway
    "frps" -> R.string.admin_component_frps
    "caddy" -> R.string.admin_component_caddy
    "backup" -> R.string.admin_component_backup
    else -> R.string.admin_unknown
}

@Composable
private fun auditLabel(action: String): String = when (action) {
    "UserCreated" -> stringResource(R.string.admin_audit_created)
    "UserUpdated" -> stringResource(R.string.admin_audit_updated)
    "UserDeleted" -> stringResource(R.string.admin_audit_deleted)
    "UserDisabled" -> stringResource(R.string.admin_audit_disabled)
    "UserEnabled" -> stringResource(R.string.admin_audit_enabled)
    "PasswordReset" -> stringResource(R.string.admin_audit_password)
    "DeploymentSettingsUpdated" -> stringResource(R.string.admin_audit_settings)
    else -> action
}

private fun copyAdminText(context: Context, value: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(if (sensitive) "Temporary password" else "Address", value)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, R.string.admin_copied, Toast.LENGTH_SHORT).show()
}

@Composable
private fun AdminUserEditor(user: AdminUser?, state: AdminUiState, controller: AdminRepository,
                            onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var username by remember(user?.id) { mutableStateOf(user?.username.orEmpty()) }
    var displayName by remember(user?.id) { mutableStateOf(user?.displayName.orEmpty()) }
    val validName = displayName.trim().length in 1..120
    val validUsername = Regex("^[a-z0-9][a-z0-9._-]{2,63}$").matches(username.trim())
    val valid = validName && (user != null || validUsername)
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(if (user == null) R.string.admin_new_user else R.string.admin_edit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (user == null) Text(stringResource(R.string.admin_create_hint))
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, readOnly = user != null,
                label = { Text(stringResource(R.string.admin_username)) }, enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = { if (user == null) Text(stringResource(R.string.admin_username_hint)) },
                isError = username.isNotEmpty() && !validUsername)
            OutlinedTextField(displayName, { displayName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.admin_display_name)) }, enabled = !state.saving,
                isError = displayName.isNotEmpty() && !validName)
            if (state.saving || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.errorCode?.let { AdminError(it, controller::refresh) }
        } },
        confirmButton = { Button(onClick = { onSave(username.trim(), displayName.trim()) }, enabled = valid && !state.saving && !state.loading,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (user == null) R.string.admin_create else R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun AdminUserAction(user: AdminUser, action: String, state: AdminUiState, controller: AdminRepository,
                            onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmation by remember(user.id, action) { mutableStateOf("") }
    val title = when (action) { "delete" -> R.string.admin_delete; "reset" -> R.string.admin_reset; "enable" -> R.string.admin_enable; else -> R.string.admin_disable }
    val warning = when (action) {
        "delete" -> stringResource(R.string.admin_delete_warning, user.username, user.deviceCount, user.connectionCount)
        "reset" -> stringResource(R.string.admin_reset_warning, user.username)
        "enable" -> stringResource(R.string.admin_enable_warning, user.username)
        else -> stringResource(R.string.admin_disable_warning, user.username)
    }
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(warning)
            if (action == "delete") OutlinedTextField(confirmation, { confirmation = it }, enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.admin_delete_confirmation)) })
            if (state.saving || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.errorCode?.let { AdminError(it, controller::refresh) }
        } },
        confirmButton = { Button(onClick = onConfirm,
            enabled = !state.saving && !state.loading && (action != "delete" || confirmation == user.username),
            colors = if (action == "enable") ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(title)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PasswordIssuedDialog(value: IssuedPassword, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val window = remember(context) { context.activity()?.window }
    var visible by remember(value) { mutableStateOf(false) }
    DisposableEffect(window) {
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    AlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.admin_temporary_password)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.admin_password_notice, value.username))
            value.expiresInSeconds?.let { Text(stringResource(R.string.admin_password_expiry, (it + 59) / 60)) }
            OutlinedTextField(value.value, {}, readOnly = true, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.admin_temporary_password)) },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.admin_hide_password else R.string.admin_show_password)) } })
            OutlinedButton(onClick = { copyAdminText(context, value.value, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.admin_copy_password))
            }
        } },
        confirmButton = { Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.admin_password_saved)) } })
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
