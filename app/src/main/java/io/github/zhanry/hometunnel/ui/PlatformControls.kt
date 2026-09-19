package io.github.zhanry.hometunnel.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zhanry.hometunnel.BuildConfig
import io.github.zhanry.hometunnel.model.*
import io.github.zhanry.hometunnel.network.HomeTunnelApi
import io.github.zhanry.hometunnel.network.ServerDiscovery
import io.github.zhanry.hometunnel.repository.AppUiState
import io.github.zhanry.hometunnel.repository.HomeTunnelRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun platformText(zh: String, en: String): String =
    if (LocalConfiguration.current.locales[0].language == "zh") zh else en

@Composable
internal fun MfaField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(platformText("动态码或恢复码（启用后必填）", "Authenticator or recovery code (if enabled)")) })
}

@Composable
internal fun SavedServers(state: AppUiState, repository: HomeTunnelRepository) {
    var forgetting by remember { mutableStateOf<SavedAccount?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(platformText("已保存的服务器", "Saved servers"), style = MaterialTheme.typography.titleMedium)
        state.persisted.savedAccounts.forEach { account ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(account.profile.publicBaseUrl)
                    Text(account.username)
                    if (account.id == state.persisted.activeAccountId) Text(platformText("当前服务器", "Current server"))
                    else Row {
                        TextButton(onClick = { repository.switchServer(account.id) }, enabled = !state.busy) { Text(platformText("切换", "Switch")) }
                        TextButton(onClick = { forgetting = account }, enabled = !state.busy) { Text(platformText("移除", "Forget")) }
                    }
                }
            }
        }
        if (state.persisted.signedIn) OutlinedButton(onClick = { repository.addServer() }, enabled = !state.busy && state.persisted.savedAccounts.size < 20) {
            Text(platformText("添加服务器或账号", "Add server or account"))
        }
    }
    forgetting?.let { account -> AlertDialog(onDismissRequest = { forgetting = null },
        title = { Text(platformText("移除本机登录", "Forget local sign-in")) },
        text = { Text(platformText("仅移除本机保存的登录。远端会话仍有效，可在该服务器的账号页面撤销。", "This removes the saved sign-in on this phone. Revoke the remote session from that server's account page.")) },
        confirmButton = { TextButton(onClick = { repository.forgetServer(account.id); forgetting = null }) { Text(platformText("移除", "Forget")) } },
        dismissButton = { TextButton(onClick = { forgetting = null }) { Text(platformText("取消", "Cancel")) } }) }
}

@Composable
internal fun PlatformAccountControls(state: AppUiState, repository: HomeTunnelRepository) {
    var dialog by remember { mutableStateOf("") }
    SavedServers(state, repository)
    listOf("security" to platformText("双重验证与登录会话", "MFA and sign-in sessions"),
        "enroll" to platformText("设备接入码", "Device enrollment codes"),
        "diagnostics" to platformText("诊断与脱敏报告", "Diagnostics and redacted report")).forEach { (key, label) ->
        OutlinedButton(onClick = { dialog = key }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
    if (dialog.isNotEmpty()) AccountPlatformDialog(dialog, state, repository) { dialog = "" }
}

@Composable
private fun AccountPlatformDialog(mode: String, state: AppUiState, repository: HomeTunnelRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<MfaStatus?>(null) }
    var sessions by remember { mutableStateOf<List<ManagementSession>>(emptyList()) }
    var codes by remember { mutableStateOf<List<EnrollmentCode>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf<MfaSetup?>(null) }
    var recovery by remember { mutableStateOf<List<String>>(emptyList()) }
    var enrollment by remember { mutableStateOf<EnrollmentCode?>(null) }
    var deviceName by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    val run: (suspend (HomeTunnelApi) -> Unit) -> Unit = { operation ->
        scope.launch {
            busy = true; error = null
            try { repository.accountAction(operation) }
            catch (failure: Exception) { error = failure.message ?: "Request failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(mode) {
        run { api ->
            if (mode == "security") { status = api.mfaStatus(); sessions = api.sessions().items }
            if (mode == "enroll") codes = api.enrollmentCodes().items
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(when (mode) {
            "security" -> platformText("账号安全", "Account security")
            "enroll" -> platformText("设备接入码", "Device enrollment")
            else -> platformText("管理端诊断", "Management diagnostics")
        }) },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (mode) {
                "security" -> {
                    status?.let { value ->
                        Text(if (value.enabled) platformText("已启用 · 剩余恢复码：${value.recoveryCodesRemaining}", "Enabled · ${value.recoveryCodesRemaining} recovery codes remaining")
                            else platformText("尚未启用双重验证", "MFA is not enabled"))
                        OutlinedTextField(password, { password = it }, label = { Text(platformText("当前密码", "Current password")) },
                            singleLine = true, visualTransformation = PasswordVisualTransformation())
                        MfaField(code) { code = it }
                        Text(platformText("每次操作需要新的动态码。启用或关闭 MFA 会撤销其他管理会话和未使用的接入码。", "Use a new code for each operation. Enabling or disabling MFA revokes other management sessions and unused enrollment codes."))
                        if (!value.enabled && setup == null) Button(enabled = !busy && password.isNotEmpty(), onClick = {
                            run { setup = it.mfaSetup(password) }
                        }) { Text(platformText("设置验证器", "Set up authenticator")) }
                        setup?.let { pending ->
                            Text(platformText("将此密钥添加到验证器，然后输入 6 位动态码确认。10 分钟内有效。", "Add this key to your authenticator, then confirm with its six-digit code within 10 minutes."))
                            SelectionContainer { Text(pending.secret) }
                            Button(enabled = !busy && password.isNotEmpty() && code.length == 6, onClick = {
                                run { api ->
                                    recovery = api.mfaConfirm(password, code).codes
                                    password = ""; code = ""; setup = null
                                    status = api.mfaStatus(); sessions = api.sessions().items
                                }
                            }) { Text(platformText("确认启用", "Confirm MFA")) }
                        }
                        if (value.enabled) {
                            OutlinedButton(enabled = !busy && password.isNotEmpty() && code.isNotBlank(), onClick = {
                                run { api -> recovery = api.replaceRecoveryCodes(password, code).codes; password = ""; code = ""; status = api.mfaStatus() }
                            }) { Text(platformText("替换全部恢复码", "Replace all recovery codes")) }
                            OutlinedButton(enabled = !busy && password.isNotEmpty() && code.isNotBlank(), onClick = {
                                run { api -> api.mfaDisable(password, code); password = ""; code = ""; recovery = emptyList(); status = api.mfaStatus(); sessions = api.sessions().items }
                            }) { Text(platformText("关闭双重验证", "Disable MFA")) }
                        }
                        if (recovery.isNotEmpty()) {
                            Text(platformText("立即离线保存。这些恢复码仅显示一次，每个只能使用一次。", "Save these offline now. They are shown only once and each can be used once."))
                            SelectionContainer { Text(recovery.joinToString("\n")) }
                        }
                    }
                    HorizontalDivider()
                    Text(platformText("管理会话（最多显示最近 100 个）", "Management sessions (latest 100)"))
                    sessions.forEach { session ->
                        Text("${session.clientType} · ${session.createdAt}")
                        if (session.current) Text(platformText("当前会话", "This session"))
                        else TextButton(enabled = !busy, onClick = {
                            run { api -> api.revokeSession(session.id); sessions = api.sessions().items }
                        }) { Text(platformText("撤销此会话", "Revoke session")) }
                    }
                }
                "enroll" -> {
                    Text(platformText("为 Windows、macOS 或 Linux 客户端生成接入码。10 分钟内单次有效；接收者可登记一台拥有你权限的设备。请通过可信渠道传递。", "Create a single-use code for Windows, macOS or Linux. It expires in 10 minutes and grants device access to your account. Share it through a trusted channel."))
                    OutlinedTextField(deviceName, { deviceName = it.take(120) }, label = { Text(platformText("用途备注", "Purpose")) })
                    Button(enabled = !busy && deviceName.isNotBlank(), onClick = {
                        run { api -> enrollment = api.createEnrollmentCode(deviceName); codes = api.enrollmentCodes().items }
                    }) { Text(platformText("生成接入码", "Generate code")) }
                    enrollment?.let { generated ->
                        SelectionContainer { Text(generated.code.orEmpty()) }
                        Text(generated.expiresAt)
                        Text(platformText("请在客户端输入当前服务器地址和接入码。", "Enter this server address and code in the desktop client."))
                    }
                    codes.forEach { item ->
                        Text("${item.name} · ${item.expiresAt}")
                        if (item.consumedAt == null && item.revokedAt == null) TextButton(enabled = !busy, onClick = {
                            run { api -> api.revokeEnrollmentCode(item.id); if (enrollment?.id == item.id) enrollment = null; codes = api.enrollmentCodes().items }
                        }) { Text(platformText("撤销接入码", "Revoke code")) }
                        else Text(platformText("已使用或撤销", "Used or revoked"))
                    }
                }
                else -> {
                    Text(platformText("检查当前服务器 HTTPS 发现、管理授权和设备状态。目标服务、FRPS 和 UDP 请在运行隧道的主机执行 doctor。", "Checks this server's HTTPS discovery, account access and device status. Run doctor on the tunnel host to check local services, FRPS and UDP."))
                    Button(enabled = !busy, onClick = {
                        run { api ->
                            ServerDiscovery.discover(requireNotNull(state.persisted.profile).publicBaseUrl)
                            api.currentUser()
                            val devices = api.listDevices()
                            val connections = api.listConnections()
                            report = buildJsonObject {
                                put("schema", "home-tunnel-management-diagnostics/v1")
                                put("client_version", BuildConfig.VERSION_NAME)
                                put("https_discovery", "passed"); put("management_auth", "passed")
                                put("devices", devices.size); put("devices_online", devices.count { it.online })
                                put("connections", connections.size); put("connections_online", connections.count { it.state == "Online" })
                            }.toString()
                        }
                    }) { Text(platformText("运行检查", "Run checks")) }
                    if (report.isNotEmpty()) {
                        SelectionContainer { Text(report) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text(platformText("复制脱敏报告", "Copy redacted report")) }
                        Text(platformText("报告不包含地址、设备名称或凭据。", "The report excludes addresses, device names and credentials."))
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = onClose) { Text(platformText("关闭", "Close")) } })
}

@Composable
internal fun DeviceMetadataControls(device: ManagedDevice, repository: HomeTunnelRepository) {
    var editing by remember { mutableStateOf(false) }
    var tags by remember(device.tags) { mutableStateOf(device.tags.joinToString(", ")) }
    var favorite by remember(device.favorite) { mutableStateOf(device.favorite) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text((if (device.favorite) "★ " else "") + device.tags.joinToString(" · "))
    TextButton(onClick = { editing = true }) { Text(platformText("标签与收藏", "Tags and favorite")) }
    if (editing) AlertDialog(onDismissRequest = { if (!busy) editing = false },
        title = { Text(platformText("设备标签与收藏", "Device tags and favorite")) },
        text = { Column {
            OutlinedTextField(tags, { tags = it }, label = { Text(platformText("标签，以逗号分隔，最多 12 个", "Tags, comma separated, up to 12")) })
            Row { Checkbox(favorite, { favorite = it }); Text(platformText("收藏", "Favorite")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try {
                val values = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                require(values.size <= 12 && values.all { it.length <= 32 }) { "Use at most 12 tags of 32 characters" }
                repository.accountAction { it.updateDeviceMetadata(device, values, favorite) }
                editing = false; repository.refreshConnections(silent = true)
            } catch (failure: Exception) { error = failure.message } finally { busy = false }
        } }) { Text(platformText("保存", "Save")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text(platformText("取消", "Cancel")) } })
}

@Composable
internal fun BatchConnectionControls(items: List<TunnelConnection>, repository: HomeTunnelRepository, onFinished: () -> Unit) {
    var action by remember { mutableStateOf<Boolean?>(null) }
    var results by remember { mutableStateOf<List<BatchResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = items.isNotEmpty() && !busy, onClick = { action = false }) { Text(platformText("批量暂停 (${items.size})", "Pause (${items.size})")) }
        OutlinedButton(enabled = items.isNotEmpty() && !busy, onClick = { action = true }) { Text(platformText("批量恢复", "Resume")) }
    }
    action?.let { enabled -> AlertDialog(onDismissRequest = { if (!busy) action = null },
        title = { Text(if (enabled) platformText("恢复所选连接", "Resume selected connections") else platformText("暂停所选连接", "Pause selected connections")) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            items.forEach { Text(it.name) }
            Text(platformText("每项独立执行。冲突项不会覆盖服务器上的新修改。", "Items run independently. Conflicting changes are not overwritten."))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true; error = null
            try { results = repository.accountAction { it.batchConnections(items, enabled).results }; action = null; repository.refreshConnections(silent = true) }
            catch (failure: Exception) { error = failure.message } finally { busy = false }
        } }) { Text(platformText("执行", "Apply")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { action = null }) { Text(platformText("取消", "Cancel")) } }) }
    if (results.isNotEmpty()) AlertDialog(onDismissRequest = { results = emptyList(); onFinished() },
        title = { Text(platformText("逐项结果", "Results")) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { results.forEach { result ->
            Text("${items.find { it.id == result.id }?.name ?: result.id}: ${result.errorCode ?: "OK"}")
        } } },
        confirmButton = { TextButton(onClick = { results = emptyList(); onFinished() }) { Text(platformText("关闭", "Close")) } })
}
