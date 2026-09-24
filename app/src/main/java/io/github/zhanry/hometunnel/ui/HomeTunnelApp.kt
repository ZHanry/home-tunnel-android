@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.zhanry.hometunnel.ui

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip

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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.network.AvailableUpdate
import io.github.zhanry.hometunnel.network.ReleaseUpdates
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
                AppScreen.HOME -> key(state.persisted.activeAccountId) { HomeScreen(
                    state = state,
                    repository = repository,
                    snackbar = snackbar,
                ) }
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
    var mfa by remember { mutableStateOf("") }
    AuthFrame {
        Box(Modifier.fillMaxWidth().height(166.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF1F0FF), Color(0xFFE9E8FF))))) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 35.dp, y = (-55).dp).size(195.dp)
                .border(1.dp, Color.White.copy(alpha = .65f), CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFC9C8FF), Color(0xFFE9E8FF))), CircleShape))
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                BrandMark()
                Text("hometunnel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF252841))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("WELCOME BACK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.login_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.server_address), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = server,
            onValueChange = { server = it; mfa = ""; repository.clearLoginMfa() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            placeholder = { Text(stringResource(R.string.server_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        Text(stringResource(R.string.username), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; mfa = ""; repository.clearLoginMfa() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            placeholder = { Text(stringResource(R.string.username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Text(stringResource(R.string.password), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; mfa = ""; repository.clearLoginMfa() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            placeholder = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(painterResource(if (passwordVisible) R.drawable.ic_action_eye_off else R.drawable.ic_action_eye),
                    contentDescription = stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password))
            } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        if (state.loginMfaRequired) MfaField(mfa) { mfa = it }
        Button(
            onClick = { repository.login(server, username, password, mfa) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
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
        if (state.persisted.savedAccounts.isNotEmpty()) SavedServers(state, repository)
        LanguageMenu(compact = true)
    }
}

@Composable
private fun PasswordChangeScreen(state: AppUiState, repository: HomeTunnelRepository) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    AuthFrame {
        Icon(
            painterResource(R.drawable.ic_action_lock),
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
        MfaField(mfa) { mfa = it }
        Button(
            onClick = { repository.changeRequiredPassword(current, next, mfa) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !state.busy && current.isNotEmpty() && next.length >= 12 && next == confirm,
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.save_password))
        }
        TextButton(onClick = repository::cancelPasswordChange, enabled = !state.busy) {
            Icon(painterResource(R.drawable.ic_action_back), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun AuthFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 540.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
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
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    if (remoteOpen) {
        io.github.zhanry.hometunnel.remote.RemoteScreen(repository.remote) { remoteOpen = false }
        return
    }
    if (updatesOpen) {
        UpdatesScreen { updatesOpen = false }
        return
    }
    var deleteTarget by remember { mutableStateOf<TunnelConnection?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedDevice by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var deviceSearch by rememberSaveable { mutableStateOf("") }
    var tunnelFilter by rememberSaveable { mutableStateOf("all") }
    var confirmLogout by remember { mutableStateOf(false) }
    var selectedConnections by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabLabels = listOf(R.string.nav_overview, R.string.nav_devices, R.string.nav_connections, R.string.nav_account, R.string.nav_management)
    val tabIcons = listOf(R.drawable.ic_nav_remote, R.drawable.ic_nav_devices, R.drawable.ic_nav_tunnels, R.drawable.ic_nav_account)
    val visibleTabs = listOf(0, 1, 2, 3)
    val scrollState = pageScrollState(tab, when (tab) { 1 -> deviceSearch; 2 -> Triple(selectedDevice, search, tunnelFilter); else -> Unit })
    LaunchedEffect(state.isAdmin) { if (!state.isAdmin && tab == 4) tab = 0 }
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
                    Text(listOf("REMOTE DESKTOP", "MY DEVICES", "PRIVATE SERVICES", "ACCOUNT", "CONTROL CENTER")[tab], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(tabLabels[tab]), fontWeight = FontWeight.Bold)
                } },
                actions = { if (tab != 4) IconButton(onClick = { repository.refreshConnections() }, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = stringResource(R.string.refresh_status))
                } },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                visibleTabs.forEach { index ->
                    NavigationBarItem(selected = tab == index || tab == 4 && index == 3, onClick = { tab = index },
                        icon = { Icon(painterResource(tabIcons[index]), contentDescription = null) },
                        label = { Text(stringResource(tabLabels[index])) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 2) ExtendedFloatingActionButton(onClick = createConnection,
                icon = { Icon(painterResource(R.drawable.ic_action_plus), contentDescription = null) },
                text = { Text(stringResource(R.string.add_connection)) })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (tab == 4 && state.isAdmin) {
                AdminWorkspace(repository.administration, state.persisted.profile?.publicBaseUrl.orEmpty())
            } else LazyColumn(
                state = scrollState,
                modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = if (tab == 2) 100.dp else 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.stale) item { WarningCard(stringResource(R.string.cached_data_warning)) }
                when (tab) {
                    0 -> {
                        item { RemoteHomeHero { remoteOpen = true } }
                        item { SectionLabel(platformText("我的远控设备", "My remote devices")) }
                        if (state.devices.isEmpty()) item { OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(platformText("还没有登记设备。请先在电脑上安装桌面客户端。", "No devices yet. Install the desktop client on your computer first."), Modifier.padding(22.dp))
                        } }
                        items(state.devices.sortedWith(compareByDescending<ManagedDevice> { it.online }.thenBy { it.name }), key = { it.id }) { device ->
                            RemoteHomeDevice(device.name, device.online && device.status == "active") { remoteOpen = true }
                        }
                        item { OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(platformText("无人值守", "Unattended access"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(platformText("被控电脑由管理员开启并绑定后，可在远控页面快捷连接；不支持时仍需单次授权。", "Once an administrator enables and binds the host, connect quickly from Remote Desktop. Otherwise one-session approval is required."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                    1 -> {
                        item { OutlinedTextField(deviceSearch, { deviceSearch = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(platformText("搜索设备名称或 ID", "Search name or ID")) }, singleLine = true,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_action_search), contentDescription = null) }) }
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionLabel(platformText("全部设备", "All devices"))
                            Text(platformText("${state.devices.size} 台设备", "${state.devices.size} devices"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } }
                        val visibleDevices = state.devices.filter { it.name.contains(deviceSearch, true) || it.id.contains(deviceSearch, true) }
                            .sortedWith(compareByDescending<ManagedDevice> { it.favorite }.thenByDescending { it.online })
                        if (visibleDevices.isEmpty()) item { EmptyDevicesCard(hasDevices = state.devices.isNotEmpty()) }
                        items(visibleDevices, key = { it.id }) { device ->
                            DeviceListCard(device, repository, state.connections.count { it.deviceId == device.id },
                                onConnections = { selectedDevice = device.id; search = ""; tab = 2 }, onRemote = { remoteOpen = true })
                        }
                    }
                    2 -> {
                        item { TunnelsHero() }
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionLabel(platformText("我的连接", "My connections"))
                            Text(platformText("${state.connections.size} 条", "${state.connections.size} tunnels"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to platformText("全部", "All"), "online" to platformText("运行中", "Running"), "paused" to platformText("已暂停", "Paused")).forEach { (key, label) ->
                                FilterChip(selected = tunnelFilter == key, onClick = { tunnelFilter = key }, label = { Text(label) })
                            }
                        } }
                        item { OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.search_connections)) }, singleLine = true,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_action_search), contentDescription = null) }) }
                        if (selectedDevice.isNotEmpty()) item {
                            OutlinedButton(onClick = { selectedDevice = "" }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.device_filter, state.devices.find { it.id == selectedDevice }?.name.orEmpty()))
                            }
                        }
                        val filtered = state.connections.filter { (selectedDevice.isEmpty() || it.deviceId == selectedDevice) &&
                            (search.isBlank() || it.name.contains(search, true) || it.publicDisplayEndpoint.contains(search, true)) &&
                            (tunnelFilter == "all" || tunnelFilter == "online" && it.enabled && it.state.equals("online", true) || tunnelFilter == "paused" && !it.enabled) }
                        if (filtered.isEmpty()) item {
                            if (state.connections.isEmpty()) EmptyConnectionsCard()
                            else Text(stringResource(R.string.no_search_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item { BatchConnectionControls(state.connections.filter { it.id in selectedConnections }, repository) { selectedConnections = emptySet() } }
                        items(filtered, key = { it.id }) { connection ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = connection.id in selectedConnections,
                                        enabled = connection.kind != ProxyKind.UNKNOWN && !state.busy && (selectedConnections.size < 50 || connection.id in selectedConnections),
                                        onCheckedChange = { checked -> selectedConnections = if (checked) selectedConnections + connection.id else selectedConnections - connection.id })
                                    Text(platformText("选择此连接", "Select connection"))
                                }
                                ConnectionCard(connection, { editor = ConnectionEdit(connection, false) }, copyAddress)
                            }
                        }
                    }
                    3 -> {
                        item { AccountContent(state, repository, onManagement = { tab = 4 },
                            onUpdates = { updatesOpen = true }) { confirmLogout = true } }
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
            capabilities = state.capabilities,
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
private fun RemoteHomeHero(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF5554C7), Color(0xFF7270E1))),
        ).padding(23.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CONNECT FROM ANYWHERE", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(platformText("你的电脑，\n就在身边。", "Your computer,\nwithin reach."), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(platformText("选择设备，进入远程桌面。", "Choose a device to open remote desktop."), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4B4CC8))) {
            Text(platformText("查看远控设备 →", "View remote devices →"))
        }
    }
}

@Composable
private fun TunnelsHero() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF303A70), Color(0xFF5966B6))))
            .padding(23.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("YOUR SERVICES", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(platformText("家里的服务，\n随时能访问。", "Your services,\nwithin reach."), color = Color.White,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(platformText("远程桌面与内网穿透分开管理。", "Remote desktop and tunnels have separate workspaces."),
            color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyDevicesCard(hasDevices: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_nav_devices), contentDescription = null,
                modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (hasDevices) platformText("没有匹配的设备", "No matching devices")
                else platformText("还没有登记设备", "No registered devices"), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceListCard(device: ManagedDevice, repository: HomeTunnelRepository, connectionCount: Int,
    onConnections: () -> Unit, onRemote: () -> Unit) {
    val online = device.online && device.status == "active"
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(43.dp).clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_nav_devices), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.device_service_count, connectionCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(if (online) R.string.status_online else R.string.status_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (online) Color(0xFF148263) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text(device.id, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onConnections) { Text(platformText("查看连接", "View tunnels")) }
                OutlinedButton(onClick = onRemote, enabled = online) { Text(platformText("远控入口", "Remote access")) }
            }
            DeviceMetadataControls(device, repository)
        }
    }
}

@Composable
private fun RemoteHomeDevice(name: String, online: Boolean, onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(43.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_nav_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (online) platformText("在线", "Online") else platformText("离线", "Offline"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onOpen, enabled = online) { Text(platformText("打开远控", "Open remote")) }
            }
        }
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
            Icon(painterResource(R.drawable.ic_action_cloud_off), contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.no_connections), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.no_connections_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionCard(connection: TunnelConnection, onEdit: () -> Unit, onCopy: (String) -> Unit) {
    val status = localizedConnectionState(connection)
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(connection.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(connection.proxyType.uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            Text(connection.publicDisplayEndpoint.ifBlank { "${connection.localHost}:${connection.localPort}" },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(11.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("${connection.localHost}:${connection.localPort}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (connection.enabled && connection.state.equals("online", true)) Color(0xFF148263)
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onCopy(connection.publicDisplayEndpoint.ifBlank { connection.subdomain }) }) {
                    Icon(painterResource(R.drawable.ic_action_copy), contentDescription = stringResource(R.string.copy_address),
                        modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.ic_action_edit), contentDescription = stringResource(R.string.edit_connection),
                        modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
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

@Composable
internal fun WarningCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun AccountContent(state: AppUiState, repository: HomeTunnelRepository,
    onManagement: () -> Unit, onUpdates: () -> Unit, onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text((state.currentUser?.displayName ?: state.persisted.userDisplayName
                        ?: state.persisted.username.orEmpty()).take(1).uppercase(),
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(state.currentUser?.displayName ?: state.persisted.userDisplayName ?: state.persisted.username.orEmpty(),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${stringResource(if (state.isAdmin) R.string.admin_role_admin else R.string.admin_role_user)} · ${state.persisted.profile?.publicBaseUrl.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        PlatformAccountControls(state, repository)
        if (state.isAdmin) OutlinedButton(onClick = onManagement, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_action_shield), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nav_management))
        }
        SectionLabel(stringResource(R.string.preferences))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.language)); LanguageMenu(compact = false)
        }
        Text(stringResource(R.string.theme_system), color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionLabel(stringResource(R.string.about_app))
        OutlinedButton(onClick = onUpdates, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.check_update))
        }
        Text(stringResource(R.string.version_label, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestMessage = stringResource(R.string.update_current)
    val unavailableMessage = stringResource(R.string.update_unavailable)
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AvailableUpdate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 540.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.cancel))
                }
                Text(stringResource(R.string.check_update), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(release?.let { stringResource(R.string.update_available, it.version) }
                        ?: platformText("检查正式版本", "Check official releases"),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(message ?: platformText("仅检查 GitHub 正式 Release", "Only official GitHub Releases are checked"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        checking = true
                        scope.launch {
                            try {
                                release = ReleaseUpdates.check()
                                message = if (release == null) latestMessage else null
                            } catch (_: Exception) {
                                release = null
                                message = unavailableMessage
                            } finally { checking = false }
                        }
                    }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (checking) R.string.checking_update else R.string.check_update))
                    }
                    release?.let { available ->
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.url))) },
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_release)) }
                    }
                }
            }
            SectionLabel(platformText("更新设置", "Update settings"))
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    UpdateDetailRow(platformText("当前版本", "Current version"), BuildConfig.VERSION_NAME)
                    HorizontalDivider()
                    UpdateDetailRow(platformText("检查方式", "Check method"), platformText("手动 · 正式版", "Manual · stable"))
                    HorizontalDivider()
                    UpdateDetailRow(platformText("自动下载安装", "Automatic install"), platformText("未启用", "Disabled"))
                }
            }
            Text(platformText("私有候选包不会显示为公开更新。安装前请核对发布来源与完整性。",
                "Private release candidates are not shown as public updates. Verify the source and integrity before installing."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LanguageMenu(compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (compact) {
            TextButton(onClick = { expanded = true }) {
                Icon(painterResource(R.drawable.ic_action_language), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.language))
            }
        } else {
            IconButton(onClick = { expanded = true }) {
                Icon(painterResource(R.drawable.ic_action_more), contentDescription = stringResource(R.string.language))
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
    Image(painterResource(R.drawable.ic_home_tunnel), contentDescription = null, Modifier.size(56.dp))
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
