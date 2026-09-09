@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.zhanry.hometunnel.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import io.github.zhanry.hometunnel.BuildConfig
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.AgentState
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.repository.AppScreen
import io.github.zhanry.hometunnel.repository.AppUiState
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import kotlinx.coroutines.launch

@Composable
fun HomeTunnelApp(
    repository: HomeTunnelRepository,
) {
    val state by repository.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    if (state.screen == AppScreen.HOME) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { repository.refreshConnections(silent = true) }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            repository.clearError()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(targetState = state.screen, label = "screen") { screen ->
            when (screen) {
                AppScreen.LOADING -> LoadingScreen()
                AppScreen.LOGIN -> LoginScreen(state, repository)
                AppScreen.PASSWORD_CHANGE -> PasswordChangeScreen(state, repository)
                AppScreen.HOME -> HomeScreen(
                    state = state,
                    repository = repository,
                    snackbar = snackbar,
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark()
            CircularProgressIndicator()
            Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoginScreen(state: AppUiState, repository: HomeTunnelRepository) {
    var server by rememberSaveable { mutableStateOf(state.persisted.lastServerUrl ?: state.persisted.profile?.publicBaseUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(state.persisted.username.orEmpty()) }
    // Passwords deliberately use remember, not rememberSaveable: they must not
    // enter the Activity saved-state bundle or survive process recreation.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    AuthFrame {
        BrandMark()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.login_heading),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            label = { Text(stringResource(R.string.server_address)) },
            placeholder = { Text(stringResource(R.string.server_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
            } },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        Button(
            onClick = { repository.login(server, username, password) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !state.busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.signing_in))
            } else {
                Text(stringResource(R.string.sign_in))
            }
        }
        OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.secure_discovery_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LanguageMenu(compact = true)
    }
}

@Composable
private fun PasswordChangeScreen(state: AppUiState, repository: HomeTunnelRepository) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AuthFrame {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.change_password),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.password_change_required),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = current,
            onValueChange = { current = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.current_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = next,
            onValueChange = { next = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_password)) },
            supportingText = { Text(stringResource(R.string.password_minimum)) },
            isError = next.isNotEmpty() && next.length < 12,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.confirm_password)) },
            supportingText = {
                if (confirm.isNotEmpty() && confirm != next) Text(stringResource(R.string.passwords_do_not_match))
            },
            isError = confirm.isNotEmpty() && confirm != next,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            onClick = { repository.changeRequiredPassword(current, next) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !state.busy && current.isNotEmpty() && next.length >= 12 && next == confirm,
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.save_password))
        }
        TextButton(onClick = repository::cancelPasswordChange, enabled = !state.busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun AuthFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            modifier = Modifier.align(Alignment.Center).widthIn(max = 540.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
internal fun HomeScreen(
    state: AppUiState,
    repository: HomeTunnelRepository,
    snackbar: SnackbarHostState,
) {
    var editor by remember { mutableStateOf<ConnectionEdit?>(null) }
    var deleteTarget by remember { mutableStateOf<TunnelConnection?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedDevice by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabLabels = listOf(R.string.nav_overview, R.string.nav_devices, R.string.nav_connections, R.string.nav_account)
    val tabIcons = listOf(Icons.Default.Home, Icons.Default.Devices, Icons.Default.Link, Icons.Default.Person)
    val createConnection = {
        val available = state.devices.filter { it.status == "active" }
        if (available.isEmpty()) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.install_client_first)) }
        } else {
            val deviceId = selectedDevice.takeIf { id -> available.any { it.id == id } }
                ?: available.singleOrNull()?.id.orEmpty()
            editor = ConnectionEdit(newHttpConnection(state, deviceId), true)
        }
        Unit
    }
    val copyAddress: (String) -> Unit = { url ->
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        scope.launch { snackbar.showSnackbar(context.getString(R.string.copied_address)) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("HOME TUNNEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(tabLabels[tab]), fontWeight = FontWeight.Bold)
                } },
                actions = { IconButton(onClick = { repository.refreshConnections() }, enabled = !state.busy) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_status))
                } },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabLabels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(tabIcons[index], contentDescription = null) },
                        label = { Text(stringResource(label)) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 2) ExtendedFloatingActionButton(onClick = createConnection,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_connection)) })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = if (tab == 2) 100.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.stale) item { WarningCard(stringResource(R.string.cached_data_warning)) }
                when (tab) {
                    0 -> {
                        item { ManagementStatusCard(state) }
                        item { Button(onClick = createConnection, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_connection))
                        } }
                        item { SectionLabel(stringResource(R.string.attention_title)) }
                        val attention = state.connections.filter { it.enabled && it.state.lowercase() != "online" }
                        if (attention.isEmpty()) item {
                            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp)) {
                                Text(stringResource(if (state.connections.isEmpty()) R.string.no_connections else R.string.all_connected), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(if (state.connections.isEmpty()) R.string.no_connections_detail else R.string.all_connected_detail), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            } }
                        }
                        items(attention.take(4), key = { it.id }) { connection ->
                            ConnectionCard(connection, { editor = ConnectionEdit(connection, false) }, copyAddress)
                        }
                        item { OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.view_devices)); Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        } }
                    }
                    1 -> {
                        item { Text(stringResource(R.string.device_scope_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (state.devices.isEmpty()) item { EmptyConnectionsCard() }
                        items(state.devices, key = { it.id }) { device ->
                            OutlinedCard(onClick = { selectedDevice = device.id; search = ""; tab = 2 }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(stringResource(if (device.online && device.status == "active") R.string.status_online else R.string.status_offline), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(device.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    HorizontalDivider()
                                    Text(stringResource(R.string.device_service_count, state.connections.count { it.deviceId == device.id }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    2 -> {
                        item { OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.search_connections)) }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }) }
                        if (selectedDevice.isNotEmpty()) item {
                            OutlinedButton(onClick = { selectedDevice = "" }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.device_filter, state.devices.find { it.id == selectedDevice }?.name.orEmpty()))
                            }
                        }
                        val filtered = state.connections.filter { (selectedDevice.isEmpty() || it.deviceId == selectedDevice) &&
                            (search.isBlank() || it.name.contains(search, true) || it.publicDisplayEndpoint.contains(search, true)) }
                        if (filtered.isEmpty()) item {
                            if (state.connections.isEmpty()) EmptyConnectionsCard()
                            else Text(stringResource(R.string.no_search_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(filtered, key = { it.id }) { connection ->
                            ConnectionCard(connection, { editor = ConnectionEdit(connection, false) }, copyAddress)
                        }
                    }
                    3 -> {
                        item { AccountContent(state) { confirmLogout = true } }
                    }
                }
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.sign_out)) },
        text = { Text(stringResource(R.string.sign_out_confirmation)) },
        confirmButton = { Button(onClick = { confirmLogout = false; repository.logout { } }, enabled = !state.busy) { Text(stringResource(R.string.sign_out)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } })

    editor?.let { edit ->
        ConnectionEditor(
            edit = edit,
            devices = state.devices,
            username = state.persisted.username.orEmpty(),
            busy = state.busy,
            error = state.error,
            onReloadLatest = {
                repository.loadConnectionVersion(edit.value.id) { version ->
                    editor = edit.copy(value = edit.value.copy(version = version))
                }
            },
            onDismiss = { if (!state.busy) editor = null },
            onSave = { value ->
                repository.saveConnection(value, edit.isNew, edit.baseline) { editor = null }
            },
            onDelete = if (edit.isNew || edit.value.kind == ProxyKind.UNKNOWN) null else {
                { deleteTarget = edit.value }
            },
        )
    }
    deleteTarget?.let { value ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_connection)) },
            text = { Text(stringResource(R.string.delete_confirmation, value.name)) },
            confirmButton = {
                Button(enabled = !state.busy, onClick = {
                    repository.deleteConnection(value) {
                        deleteTarget = null
                        editor = null
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

}

@Composable
private fun ManagementStatusCard(state: AppUiState) {
    val online = state.devices.count { it.online && it.status == "active" }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.overview_eyebrow), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.overview_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(state.persisted.profile?.tunnelDomain.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("$online / ${state.devices.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.online_devices), style = MaterialTheme.typography.labelMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text(state.connections.size.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.nav_connections), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyConnectionsCard() {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.no_connections), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.no_connections_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionCard(connection: TunnelConnection, onEdit: () -> Unit, onCopy: (String) -> Unit) {
    val status = localizedConnectionState(connection)
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(connection.proxyType.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(connection.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(connection.publicDisplayEndpoint.ifBlank { "${connection.localHost}:${connection.localPort}" },
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_connection))
                }
                TextButton(onClick = { onCopy(connection.publicDisplayEndpoint.ifBlank { connection.subdomain }) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_address))
                }
            }
        }
    }
}

@Composable
private fun localizedConnectionState(connection: TunnelConnection): String {
    if (!connection.enabled) return stringResource(R.string.status_paused)
    return when (connection.state.lowercase()) {
        "online" -> stringResource(R.string.status_online)
        "applying" -> stringResource(R.string.status_syncing)
        "waiting", "pending" -> stringResource(R.string.status_waiting)
        "error" -> stringResource(R.string.status_error)
        else -> connection.state
    }
}

private data class ConnectionEdit(val value: TunnelConnection, val isNew: Boolean, val baseline: TunnelConnection = value)

@Composable
private fun ConnectionEditor(
    edit: ConnectionEdit,
    devices: List<io.github.zhanry.hometunnel.model.ManagedDevice>,
    username: String,
    busy: Boolean,
    error: String?,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (TunnelConnection) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.name) }
    var deviceId by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.deviceId) }
    var subdomain by rememberSaveable(edit.value.id) {
        mutableStateOf(edit.value.subdomain.ifBlank { if (username.isBlank()) "" else "${username.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(40)}-app" })
    }
    var scheme by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.localScheme) }
    var host by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.localHost) }
    var port by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.localPort.takeIf { it > 0 }?.toString().orEmpty()) }
    var enabled by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.enabled) }
    val raw = edit.value.kind in setOf(ProxyKind.TCP, ProxyKind.UDP)
    val unknown = edit.value.kind == ProxyKind.UNKNOWN
    val validSubdomain = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(subdomain)
    val parsedPort = port.toIntOrNull()
    val canSave = !unknown && name.isNotBlank() && (raw || validSubdomain) && host.isNotBlank() && parsedPort in 1..65535 && deviceId.isNotBlank() && (!edit.isNew || devices.any { it.id == deviceId && it.status == "active" })
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = name != edit.value.name || subdomain != edit.value.subdomain || host != edit.value.localHost ||
        port != edit.value.localPort.toString() || enabled != edit.value.enabled || deviceId != edit.value.deviceId || scheme != edit.value.localScheme
    val requestClose = { if (!busy) { if (changed) confirmDiscard = true else onDismiss() }; Unit }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(if (edit.isNew) R.string.add_connection else R.string.edit_connection)) },
                navigationIcon = { IconButton(onClick = requestClose, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel)) } }) },
            bottomBar = {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = requestClose, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    onSave(edit.value.copy(
                        deviceId = deviceId,
                        name = name.trim(),
                        subdomain = subdomain.trim(),
                        localScheme = if (raw) edit.value.localScheme else scheme,
                        localHost = host.trim(),
                        localPort = requireNotNull(parsedPort),
                        enabled = enabled,
                    ))
                },
                enabled = canSave && !busy,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.save)) }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (error?.contains("VERSION_CONFLICT") == true) {
                    OutlinedButton(onClick = onReloadLatest, enabled = !busy) { Text(stringResource(R.string.reload_latest)) }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (raw) WarningCard(stringResource(R.string.raw_mvp_warning, edit.value.proxyType.uppercase()))
                if (unknown) WarningCard(stringResource(R.string.unknown_type_warning))
                SectionLabel(stringResource(R.string.editor_identity))
                if (edit.isNew) {
                    Text(stringResource(R.string.choose_device), style = MaterialTheme.typography.bodyMedium)
                    devices.filter { it.status == "active" }.forEach { device ->
                        Row(Modifier.fillMaxWidth().selectable(selected = deviceId == device.id,
                            onClick = { deviceId = device.id }, enabled = !busy, role = Role.RadioButton)
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = deviceId == device.id, onClick = null, enabled = !busy)
                            Spacer(Modifier.width(12.dp))
                            Text(device.name, modifier = Modifier.weight(1f))
                            Text(stringResource(if (device.online) R.string.status_online else R.string.status_offline), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(devices.find { it.id == deviceId }?.name.orEmpty(), color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !unknown,
                    label = { Text(stringResource(R.string.connection_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = subdomain,
                    onValueChange = { subdomain = it.lowercase() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !unknown,
                    label = { Text(stringResource(R.string.public_subdomain)) },
                    isError = subdomain.isNotEmpty() && !validSubdomain,
                    singleLine = true,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                SectionLabel(stringResource(R.string.editor_destination))
                Text(stringResource(R.string.target_device_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!raw && !unknown) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("http", "https").forEach { value ->
                            AssistChip(onClick = { scheme = value }, label = { Text(value) }, leadingIcon = {
                                if (scheme == value) Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !unknown,
                    label = { Text(stringResource(R.string.local_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !unknown,
                    label = { Text(stringResource(R.string.local_port)) },
                    isError = port.isNotEmpty() && parsedPort !in 1..65535,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy && !unknown)
                    Text(stringResource(R.string.enabled))
                }
                onDelete?.let {
                    OutlinedButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_connection))
                    }
                }
                }
            }
        }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_detail)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } })
    }
}

@Composable
private fun WarningCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun AccountContent(state: AppUiState, onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Text(state.persisted.userDisplayName ?: state.persisted.username.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(state.persisted.profile?.publicBaseUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionLabel(stringResource(R.string.preferences))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.language)); LanguageMenu(compact = false)
        }
        Text(stringResource(R.string.theme_system), color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionLabel(stringResource(R.string.about_app))
        Text(stringResource(R.string.management_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LanguageMenu(compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            TextButton(onClick = { expanded = true }) {
                Icon(Icons.Default.Language, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.language))
            }
        } else {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.language))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "" to stringResource(R.string.language_system),
                "en" to stringResource(R.string.language_english),
                "zh-CN" to stringResource(R.string.language_chinese),
            ).forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tag),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(70.dp).clip(RoundedCornerShape(23.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp),
        )
    }
}

private fun newHttpConnection(state: AppUiState, deviceId: String): TunnelConnection = TunnelConnection(
    id = "",
    deviceId = deviceId,
    name = "",
    subdomain = "",
    proxyType = "http",
    localScheme = "http",
    localHost = "127.0.0.1",
    localPort = 8080,
    enabled = true,
    version = 0,
)
