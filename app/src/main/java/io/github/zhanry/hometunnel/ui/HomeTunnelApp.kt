@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.zhanry.hometunnel.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
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
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.zhanry.hometunnel.ui.nav.ShellLocation
import io.github.zhanry.hometunnel.ui.nav.canOpenAdmin
import io.github.zhanry.hometunnel.ui.nav.remoteHomeOrder
import io.github.zhanry.hometunnel.ui.nav.systemBack
import io.github.zhanry.hometunnel.ui.remote.nineDigitCode
import io.github.zhanry.hometunnel.ui.theme.LocalThemeChoice
import io.github.zhanry.hometunnel.ui.theme.ThemeChoice
import io.github.zhanry.hometunnel.ui.theme.ThemePreferences
import io.github.zhanry.hometunnel.ui.tunnel.TunnelDraftStore
import io.github.zhanry.hometunnel.ui.tunnel.matchSubmittedTunnel
import io.github.zhanry.hometunnel.ui.tunnel.suggestedSubdomain
import io.github.zhanry.hometunnel.ui.tunnel.tunnelAccountKey
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

    val context = LocalContext.current
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val screenContent: @Composable (AppScreen) -> Unit = { screen ->
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (reduceMotion) screenContent(state.screen) else AnimatedContent(targetState = state.screen, label = "screen") { screenContent(it) }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
internal fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark()
            CircularProgressIndicator()
            Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LoginScreen(state: AppUiState, repository: HomeTunnelRepository) {
    var server by rememberSaveable { mutableStateOf(state.persisted.lastServerUrl ?: state.persisted.profile?.publicBaseUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(state.persisted.username.orEmpty()) }
    // Passwords deliberately use remember, not rememberSaveable: they must not
    // enter the Activity saved-state bundle or survive process recreation.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var mfa by remember { mutableStateOf("") }
    val mfaFocus = remember { FocusRequester() }
    LaunchedEffect(state.loginMfaRequired) {
        if (state.loginMfaRequired) mfaFocus.requestFocus()
    }
    AuthFrame {
        Box(Modifier.fillMaxWidth().heightIn(min = 148.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)))) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandMark()
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.login_welcome), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
        if (state.loginMfaRequired) MfaField(mfa, required = true, modifier = Modifier.focusRequester(mfaFocus)) { mfa = it }
        Button(
            onClick = { repository.login(server, username, password, mfa) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !state.busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
                && (!state.loginMfaRequired || mfa.isNotBlank()),
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
internal fun PasswordChangeScreen(state: AppUiState, repository: HomeTunnelRepository) {
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
    val accountKey = tunnelAccountKey(state.persisted.activeAccountId, state.persisted.profile?.apiBaseUrl, state.persisted.username)
    var editor by rememberSaveable(stateSaver = ConnectionEditSaver) { mutableStateOf<ConnectionEdit?>(null) }
    var reportWatch by remember { mutableStateOf<TunnelReportWatch?>(null) }
    var remoteOpen by rememberSaveable { mutableStateOf(false) }
    var remoteCode by rememberSaveable { mutableStateOf("") }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
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
    val scrollState = pageScrollState(tab, when (tab) { 1 -> deviceSearch; 2 -> Triple(selectedDevice, search, tunnelFilter); else -> Unit })
    LaunchedEffect(editor, accountKey) {
        val current = editor ?: return@LaunchedEffect
        if (current.changed()) TunnelDraftStore.shared.save(accountKey, current.value.id, current.exportState())
    }
    LaunchedEffect(state.isAdmin) { if (!canOpenAdmin(state.isAdmin) && tab == 4) tab = 0 }
    val openRemote: (String) -> Unit = { deviceId ->
        remoteCode = nineDigitCode(deviceId).orEmpty()
        remoteOpen = true
    }
    if (remoteOpen) {
        io.github.zhanry.hometunnel.remote.RemoteScreen(repository.remote, { remoteOpen = false }, accountKey, remoteCode)
        return
    }
    if (updatesOpen) {
        UpdatesScreen {
            updatesOpen = ShellLocation(tab = tab, updates = true).systemBack().updates
        }
        return
    }
    val tabLabels = listOf(R.string.nav_overview, R.string.nav_devices, R.string.nav_connections, R.string.nav_account, R.string.nav_management)
    val tabKickers = listOf(R.string.tab_kicker_remote, R.string.tab_kicker_devices, R.string.tab_kicker_tunnels, R.string.tab_kicker_account, R.string.tab_kicker_admin)
    val tabIcons = listOf(R.drawable.ic_nav_remote, R.drawable.ic_nav_devices, R.drawable.ic_nav_tunnels, R.drawable.ic_nav_account)
    val visibleTabs = listOf(0, 1, 2, 3)
    BackHandler(enabled = tab == 4) {
        tab = ShellLocation(tab).systemBack().tab
    }
    val closeEditor = {
        val current = editor
        if (current != null) TunnelDraftStore.shared.clear(accountKey, current.value.id)
        reportWatch = null
        editor = null
    }
    val createConnection = {
        val available = state.devices.filter { it.status == "active" }
        if (available.isEmpty()) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.install_client_first)) }
        } else {
            val deviceId = selectedDevice.takeIf { id -> available.any { it.id == id } }
                ?: available.singleOrNull()?.id.orEmpty()
            val seeded = newHttpConnection(state, deviceId).copy(subdomain = suggestedSubdomain(state.persisted.username.orEmpty()))
            editor = ConnectionEdit(seeded, true)
        }
        Unit
    }
    val copyAddress: (String) -> Unit = { url ->
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        scope.launch { snackbar.showSnackbar(context.getString(R.string.copied_address)) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 720.dp
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Row(Modifier.fillMaxSize()) {
    if (wide) {
        if (largeText) Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(216.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                .padding(8.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                visibleTabs.forEach { index ->
                    LargeNavigationItem(stringResource(tabLabels[index]), tabIcons[index], tab == index || tab == 4 && index == 3,
                        { tab = index }, Modifier.fillMaxWidth())
                }
            }
        } else {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            visibleTabs.forEach { index ->
                NavigationRailItem(
                    selected = tab == index || tab == 4 && index == 3,
                    onClick = { tab = index },
                    icon = { Icon(painterResource(tabIcons[index]), contentDescription = stringResource(tabLabels[index])) },
                    label = { Text(stringResource(tabLabels[index])) },
                )
            }
        }
        }
    }
    Scaffold(
        modifier = Modifier.weight(1f),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Column {
                    if (stringResource(tabKickers[tab]) != stringResource(tabLabels[tab]))
                        Text(stringResource(tabKickers[tab]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(tabLabels[tab]), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                } },
                actions = { if (tab != 4) IconButton(onClick = { repository.refreshConnections() }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_action_refresh), contentDescription = stringResource(R.string.refresh_status))
                } },
            )
        },
        bottomBar = {
            Column {
            if (tab == 2) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = createConnection, modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_action_plus), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_connection))
                    }
                }
            }
            if (!wide && largeText) Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    visibleTabs.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { index ->
                                LargeNavigationItem(stringResource(tabLabels[index]), tabIcons[index], tab == index || tab == 4 && index == 3,
                                    { tab = index }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                visibleTabs.forEach { index ->
                    NavigationBarItem(selected = tab == index || tab == 4 && index == 3, onClick = { tab = index },
                        icon = { Icon(painterResource(tabIcons[index]), contentDescription = null) },
                        label = { Text(stringResource(tabLabels[index]), minLines = 2, maxLines = 2) })
                }
            }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (tab == 4 && state.isAdmin) {
                AdminWorkspace(repository.administration, state.persisted.profile?.publicBaseUrl.orEmpty())
            } else LazyColumn(
                state = scrollState,
                modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.stale) item { WarningCard(stringResource(R.string.cached_data_warning)) }
                when (tab) {
                    0 -> {
                        item { RemoteHomeHero { openRemote("") } }
                        item { Text(stringResource(R.string.remote_controller_only), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        item { SectionLabel(stringResource(R.string.remote_all_devices)) }
                        if (state.devices.isEmpty()) item { OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.remote_empty), Modifier.padding(22.dp))
                        } }
                        items(remoteHomeOrder(state.devices), key = { it.id }) { device ->
                            RemoteHomeDevice(device.name, device.online && device.status == "active", device.favorite) { openRemote(device.id) }
                        }
                        item { OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(stringResource(R.string.remote_unattended_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.remote_unattended_detail), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                    }
                    1 -> {
                        item { OutlinedTextField(deviceSearch, { deviceSearch = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.device_search)) }, singleLine = true,
                            leadingIcon = { Icon(painterResource(R.drawable.ic_action_search), contentDescription = null) }) }
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionLabel(stringResource(R.string.remote_all_devices))
                            Text(stringResource(R.string.device_count, state.devices.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } }
                        val visibleDevices = remoteHomeOrder(state.devices).filter { it.name.contains(deviceSearch, true) || it.id.contains(deviceSearch, true) }
                        if (visibleDevices.isEmpty()) item { EmptyDevicesCard(hasDevices = state.devices.isNotEmpty()) }
                        items(visibleDevices, key = { it.id }) { device ->
                            DeviceListCard(device, repository, state.connections.count { it.deviceId == device.id },
                                onConnections = { selectedDevice = device.id; search = ""; tab = 2 }, onRemote = { openRemote(device.id) })
                        }
                    }
                    2 -> {
                        item { TunnelsHero() }
                        val pendingDraft = TunnelDraftStore.shared.load(accountKey, "")
                        if (editor == null && pendingDraft != null) item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.wizard_draft_title), fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.wizard_draft_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { TunnelDraftStore.shared.clear(accountKey, "") }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(R.string.discard))
                                        }
                                        Button(onClick = { editor = importConnectionEdit(pendingDraft) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                            Text(stringResource(R.string.wizard_draft_continue))
                                        }
                                    }
                                }
                            }
                        }
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SectionLabel(stringResource(R.string.nav_connections))
                            Text(stringResource(R.string.tunnel_count, state.connections.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("all" to R.string.filter_all, "online" to R.string.filter_running, "paused" to R.string.filter_paused).forEach { (key, label) ->
                                FilterChip(selected = tunnelFilter == key, onClick = { tunnelFilter = key }, label = { Text(stringResource(label)) })
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
                                    Text(stringResource(R.string.select_connection))
                                }
                                ConnectionCard(connection, {
                                    editor = TunnelDraftStore.shared.load(accountKey, connection.id)?.let(::importConnectionEdit)
                                        ?: ConnectionEdit(connection, false)
                                }, copyAddress)
                            }
                        }
                    }
                    3 -> {
                        item { AccountContent(state, repository, onManagement = { if (canOpenAdmin(state.isAdmin)) tab = 4 },
                            onUpdates = { updatesOpen = true }) { confirmLogout = true } }
                    }
                }
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
        val watch = reportWatch
        val reportSettled = watch != null && !state.busy && (state.error != null || state.stale || state.lastSyncedAt != watch.syncStamp)
        val reported = if (watch != null && reportSettled && state.error == null) {
            matchSubmittedTunnel(state.connections, watch.submitted, watch.previousIds, watch.isNew)
        } else null
        ConnectionEditor(
            edit = edit,
            devices = state.devices,
            capabilities = state.capabilities,
            busy = state.busy,
            error = state.error,
            reported = reported,
            waitingForServer = watch != null && !reportSettled && state.error == null,
            reportUnconfirmed = watch != null && reportSettled && state.error == null && reported == null,
            onEdit = { editor = it },
            onRefreshReport = { repository.refreshConnections() },
            onCopyAddress = copyAddress,
            onDone = { if (!state.busy) closeEditor() },
            onReloadLatest = {
                repository.loadConnectionVersion(edit.value.id) { version ->
                    editor = edit.copy(value = edit.value.copy(version = version), baseline = edit.baseline.copy(version = version))
                }
            },
            onDismiss = { if (!state.busy) closeEditor() },
            onSave = { value ->
                editor = edit.copy(step = 3)
                reportWatch = TunnelReportWatch(
                    previousIds = state.connections.map { it.id }.toSet(),
                    submitted = value,
                    isNew = edit.isNew,
                    syncStamp = state.lastSyncedAt,
                )
                repository.saveConnection(value, edit.isNew, edit.baseline) { }
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
private fun LargeNavigationItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .selectable(selected = selected, onClick = onClick, role = Role.Tab).heightIn(min = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(stringResource(R.string.remote_hero_kicker), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.remote_hero_title), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.remote_hero_detail), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4B4CC8))) {
            Text(stringResource(R.string.remote_hero_action))
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
        Text(stringResource(R.string.tunnel_hero_kicker), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tunnel_hero_title), color = Color.White,
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tunnel_hero_detail),
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
            Text(stringResource(if (hasDevices) R.string.devices_no_match else R.string.devices_empty_title), fontWeight = FontWeight.Bold)
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
                TextButton(onClick = onConnections, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.view_tunnels)) }
                OutlinedButton(onClick = onRemote, enabled = online, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_entry)) }
            }
            DeviceMetadataControls(device, repository)
        }
    }
}

@Composable
private fun RemoteHomeDevice(name: String, online: Boolean, favorite: Boolean, onOpen: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(43.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_nav_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            stringResource(if (online) R.string.status_online else R.string.status_offline),
                            if (favorite) stringResource(R.string.favorite_mark) else null,
                        ).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onOpen, enabled = online, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open_remote)) }
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
        if (canOpenAdmin(state.isAdmin)) OutlinedButton(onClick = onManagement, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_action_shield), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nav_management))
        }
        SectionLabel(stringResource(R.string.preferences))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.language)); LanguageMenu(compact = false)
        }
        ThemeChoices()
        HorizontalDivider()
        SectionLabel(stringResource(R.string.about_app))
        OutlinedButton(onClick = onUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
private fun ThemeChoices() {
    val context = LocalContext.current
    val selected = LocalThemeChoice.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.theme_heading), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        listOf(
            ThemeChoice.SYSTEM to R.string.theme_option_system,
            ThemeChoice.LIGHT to R.string.theme_option_light,
            ThemeChoice.DARK to R.string.theme_option_dark,
        ).forEach { (choice, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = selected == choice,
                    onClick = { ThemePreferences.save(context, choice) },
                    role = Role.RadioButton,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == choice, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(label))
            }
        }
        Text(stringResource(R.string.theme_system), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpdatesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestMessage = stringResource(R.string.update_current)
    val unavailableMessage = stringResource(R.string.update_unavailable)
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AvailableUpdate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 540.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.back))
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
                        ?: stringResource(R.string.updates_check_heading),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(message ?: stringResource(R.string.updates_check_detail),
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
            SectionLabel(stringResource(R.string.updates_settings))
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    UpdateDetailRow(stringResource(R.string.updates_current), BuildConfig.VERSION_NAME)
                    HorizontalDivider()
                    UpdateDetailRow(stringResource(R.string.updates_method), stringResource(R.string.updates_method_value))
                    HorizontalDivider()
                    UpdateDetailRow(stringResource(R.string.updates_auto), stringResource(R.string.updates_auto_value))
                }
            }
            Text(stringResource(R.string.updates_note),
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

private data class TunnelReportWatch(
    val previousIds: Set<String>,
    val submitted: TunnelConnection,
    val isNew: Boolean,
    val syncStamp: String?,
)

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
