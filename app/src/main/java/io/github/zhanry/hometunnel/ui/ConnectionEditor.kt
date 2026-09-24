@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.zhanry.hometunnel.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip

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
import androidx.compose.ui.res.painterResource
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

internal data class ConnectionEdit(val value: TunnelConnection, val isNew: Boolean, val baseline: TunnelConnection = value)

@Composable
internal fun ConnectionEditor(
    edit: ConnectionEdit,
    devices: List<io.github.zhanry.hometunnel.model.ManagedDevice>,
    capabilities: io.github.zhanry.hometunnel.model.ConnectionCapabilities,
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
    var protocol by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.proxyType) }
    var application by rememberSaveable(edit.value.id) { mutableStateOf(edit.value.applicationProtocol ?: "http") }
    val selectedKind = ProxyKind.fromWire(protocol)
    val raw = selectedKind in setOf(ProxyKind.TCP, ProxyKind.UDP)
    val unknown = edit.value.kind == ProxyKind.UNKNOWN
    val validSubdomain = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(subdomain)
    val parsedPort = port.toIntOrNull()
    val canSave = (!edit.isNew || capabilities.permits(selectedKind)) && !unknown && name.isNotBlank() && (raw || validSubdomain) && host.isNotBlank() && parsedPort in 1..65535 && deviceId.isNotBlank() && (!edit.isNew || devices.any { it.id == deviceId && it.status == "active" })
    var confirmDiscard by remember { mutableStateOf(false) }
    val changed = protocol != edit.value.proxyType || name != edit.value.name || subdomain != edit.value.subdomain || host != edit.value.localHost ||
        port != edit.value.localPort.toString() || enabled != edit.value.enabled || deviceId != edit.value.deviceId || scheme != edit.value.localScheme
    val requestClose = { if (!busy) { if (changed) confirmDiscard = true else onDismiss() }; Unit }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(if (edit.isNew) R.string.add_connection else R.string.edit_connection)) },
                navigationIcon = { IconButton(onClick = requestClose, enabled = !busy) { Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.cancel)) } }) },
            bottomBar = {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = requestClose, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp)) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = {
                    onSave(edit.value.copy(
                        deviceId = deviceId,
                        proxyType = protocol,
                        applicationProtocol = application.takeIf { it in setOf("ssh", "rdp", "rtsp") },
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
                if (raw) WarningCard(platformText("端口由服务端自动分配。TCP/UDP 的认证、加密由目标应用负责。", "The server allocates the port. The target application provides TCP/UDP authentication and encryption."))
                if (unknown) WarningCard(stringResource(R.string.unknown_type_warning))
                if (edit.isNew) {
                    Text(platformText("连接协议", "Connection protocol"), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("http", "tcp", "udp", "ssh", "rdp", "rtsp").forEach { preset ->
                            val transport = when (preset) { "http" -> "http"; "udp" -> "udp"; else -> "tcp" }
                            FilterChip(selected = application == preset,
                                enabled = !busy && capabilities.permits(ProxyKind.fromWire(transport)),
                                onClick = { protocol = transport; application = preset
                                    port = when (preset) { "ssh" -> "22"; "rdp" -> "3389"; "rtsp" -> "554"; "http" -> "8080"; else -> port } },
                                label = { Text(preset.uppercase()) })
                        }
                    }
                    if (!capabilities.tcp.canCreate || !capabilities.udp.canCreate)
                        Text(platformText("灰色协议表示服务端未开放、账号无权限或服务端版本不支持。", "Disabled protocols are unavailable for this account or server version."))
                }
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
                if (!raw) OutlinedTextField(
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
                                if (scheme == value) Icon(painterResource(R.drawable.ic_action_shield), contentDescription = null, modifier = Modifier.size(18.dp))
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
                        Icon(painterResource(R.drawable.ic_action_delete), contentDescription = null)
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
