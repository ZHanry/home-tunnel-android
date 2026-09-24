package io.github.zhanry.hometunnel.remote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.zhanry.hometunnel.R
import kotlinx.serialization.json.JsonPrimitive

@Composable
private fun text(zh: String, en: String): String = if (LocalConfiguration.current.locales[0].language == "zh") zh else en

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(controller: RemoteController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticate by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(setOf("view", "input.keyboard", "input.pointer", "input.text", "clipboard.read", "clipboard.write")) }
    var selected by remember { mutableStateOf(emptyList<RemoteSelectedFile>()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var assistDeviceId by remember { mutableStateOf("") }
    var assistPassword by remember { mutableStateOf("") }
    var assistMode by remember { mutableStateOf("request") }
    var mfaRequired by remember { mutableStateOf(false) }
    var trustTarget by remember { mutableStateOf<RemoteEndpoint?>(null) }
    var trustPassword by remember { mutableStateOf("") }
    var trustMfa by remember { mutableStateOf("") }
    var trustMfaRequired by remember { mutableStateOf(false) }
    var sessionPanel by remember { mutableStateOf<String?>(null) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    val activity = context.activity()
    val files = remember { RemoteFiles(context.contentResolver) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try { selected = withContext(Dispatchers.IO) { files.selected(uris) } }
            catch (_: Exception) { localError = "RD_FILE_TYPE_OR_SIZE_UNSUPPORTED" }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && controller.canUse("audio.microphone")) {
            runCatching { controller.requestFeature("audio.microphone", true) }.onFailure { localError = "RD_MICROPHONE_UNAVAILABLE" }
        } else localError = "RD_MICROPHONE_PERMISSION_DENIED"
    }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(state.native.available, state.native.permissions) {
        if (state.native.available) permissions = (permissions intersect state.native.permissions) + "view"
    }
    LaunchedEffect(state.error, state.authenticated) {
        if (state.authenticated) { authenticate = false; mfaRequired = false }
        else if (state.error == "MFA_REQUIRED") mfaRequired = true
    }
    LaunchedEffect(state.error, state.pairing?.id, trustTarget) {
        if (trustTarget != null && state.error == "MFA_REQUIRED") trustMfaRequired = true
        if (trustTarget != null && state.pairing?.transcript?.get("mode") == JsonPrimitive("persistent")) {
            trustTarget = null; trustPassword = ""; trustMfa = ""; trustMfaRequired = false
        }
    }
    BackHandler { controller.closeSession(); onBack() }
    DisposableEffect(controller) { onDispose { controller.setSurface(null) } }
    DisposableEffect(activity) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    LaunchedEffect(activity, landscape, state.sessionId) {
        activity?.requestedOrientation = if (landscape && state.sessionId != null) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(state.sessionId) { if (state.sessionId == null) { sessionPanel = null; landscape = false } }
    MaterialTheme(colorScheme = if (state.sessionId != null) darkColorScheme(background = Color(0xFF171A2A), surface = Color(0xFF20243A), primary = Color(0xFFAAA9FF)) else MaterialTheme.colorScheme) {
    if (state.sessionId != null) {
        RemoteSessionView(state, controller, localError, landscape, onLandscape = { landscape = !landscape },
            onBack = { controller.closeSession(); onBack() }, onPanel = { sessionPanel = it },
            onControlError = { localError = it })
    } else LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.sessionId == null) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { controller.closeSession(); onBack() }) { Text(text("返回", "Back")) }
                TextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text(text("刷新", "Refresh")) }
            }
            Text(text("远程桌面", "Remote desktop"), style = MaterialTheme.typography.headlineMedium)
            Text(text("选择设备，开始安全的 UDP 直连。", "Choose a device for a secure direct UDP connection."))
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error != null || localError != null) item {
            Text(localError ?: state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        if (!state.enabled && !state.loading) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Text(text("该服务器未启用远程桌面。现有隧道管理仍可使用。", "This server has not enabled remote desktop. Tunnel management remains available."), Modifier.padding(16.dp)) }
        }
        if (!state.native.available && state.sessionId == null) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text("此预览包尚不能建立画面、音频或文件连接", "This preview cannot yet establish video, audio or file connections"))
                Text(text("可验证账号身份与配对流程。远控引擎就绪后，连接功能才会开放。", "Account identity and pairing are available for verification. Connections become available when the remote engine is ready."), style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (state.enabled && !state.authenticated && state.sessionId == null) item {
            Button(onClick = { authenticate = true }, enabled = !state.loading) { Text(text("验证账号并启用此控制端", "Verify account and enroll this controller")) }
        }
        state.fingerprint?.takeIf { state.sessionId == null }?.let { fingerprint -> item {
            Text(text("本机身份指纹", "This controller's fingerprint"), style = MaterialTheme.typography.labelLarge)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall)
        } }
        if (state.authenticated && state.sessionId == null) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text("连接其他账号", "Connect another account"), style = MaterialTheme.typography.titleMedium)
                    Text(text("选择连接方式并输入被控端的 9 位设备 ID。", "Choose a method and enter the host's 9-digit device ID."), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("request" to text("请求批准", "Request"), "fixed" to text("固定密码", "Fixed"), "temporary" to text("临时密码", "One-time")).forEach { (mode, label) ->
                            if (assistMode == mode) Button(onClick = { assistMode = mode }, enabled = !state.loading) { Text(label) }
                            else OutlinedButton(onClick = { assistMode = mode }, enabled = !state.loading) { Text(label) }
                        }
                    }
                    OutlinedTextField(assistDeviceId, { assistDeviceId = it.filter(Char::isDigit).take(9) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(text("设备 ID", "Device ID")) }, singleLine = true)
                    if (assistMode != "request") OutlinedTextField(assistPassword, { assistPassword = it.take(128) },
                        modifier = Modifier.fillMaxWidth(), label = { Text(if (assistMode == "fixed") text("固定密码", "Fixed password") else text("临时密码", "Temporary password")) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = {
                        when (assistMode) {
                            "request" -> controller.requestAccess(assistDeviceId, permissions)
                            "fixed" -> controller.fixedPassword(assistDeviceId, assistPassword, permissions)
                            else -> controller.assist(assistDeviceId, assistPassword, permissions)
                        }
                        assistPassword = ""
                    }, enabled = assistDeviceId.length == 9 && (assistMode == "request" || assistPassword.isNotBlank()) && state.native.available && !state.loading && state.pairing == null) {
                        Text(if (assistMode == "request") text("发送连接请求", "Send request") else text("连接远程设备", "Connect remote device"))
                    }
                    if (assistMode == "request" && state.loading) Text(text("等待被控端批准，最多两分钟。", "Waiting for host approval, up to two minutes."), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Text(text("本次配对请求的权限", "Permissions requested for this pairing"), style = MaterialTheme.typography.titleMedium)
            P.permissions.forEach { permission ->
                Row {
                    Checkbox(checked = permission in permissions, enabled = permission != "view" && !state.loading && (!state.native.available || permission in state.native.permissions),
                        onCheckedChange = { enabled -> permissions = if (enabled) permissions + permission else permissions - permission })
                    Text(permissionLabel(permission), Modifier.padding(top = 12.dp))
                }
            }
        }
        items(if (state.sessionId == null) state.endpoints else emptyList(), key = { it.id }) { endpoint ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.titleMedium)
                    Text(endpoint.fingerprint, style = MaterialTheme.typography.bodySmall)
                    Text(if (endpoint.available) text("已在本机开启", "Enabled on host") else text("被控端未开启或已撤销", "Host disabled or revoked"))
                    OutlinedButton(onClick = { controller.pair(endpoint, permissions) }, enabled = state.authenticated && state.native.available && endpoint.available && !state.loading && state.pairing == null) {
                        Text(text("连接设备", "Connect device"))
                    }
                    if (controller.canBindTrusted(endpoint, permissions)) {
                        OutlinedButton(onClick = {
                            trustTarget = endpoint; trustPassword = ""; trustMfa = ""; trustMfaRequired = false
                        }, enabled = state.authenticated && state.native.available && !state.loading && state.pairing == null) {
                            Text(text("绑定可信设备", "Trust this device"))
                        }
                    }
                }
            }
        }
    }
    if (sessionPanel != null && state.sessionId != null) {
        ModalBottomSheet(onDismissRequest = { sessionPanel = null }, containerColor = Color(0xFF232739)) {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (sessionPanel) {
                    "keyboard" -> {
                        Text(text("键盘与文本", "Keyboard and text"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Esc" to 41, "Tab" to 43, "Enter" to 40, "⌫" to 42).forEach { (label, usage) ->
                                OutlinedButton(enabled = state.inputEnabled && controller.canUse("input.keyboard"), onClick = {
                                    runCatching { controller.key(usage, true); controller.key(usage, false) }
                                        .onFailure { runCatching { controller.releaseControl() }; localError = "RD_INPUT_FAILED" }
                                }) { Text(label) }
                            }
                        }
                        OutlinedTextField(inputText, { if (it.toByteArray().size <= P.TEXT_BYTES) inputText = it },
                            enabled = state.inputEnabled && controller.canUse("input.text"), modifier = Modifier.fillMaxWidth(),
                            label = { Text(text("输入法文本", "Text input")) })
                        Button(enabled = inputText.isNotEmpty() && state.textStatus != "pending" && state.inputEnabled && controller.canUse("input.text"), onClick = {
                            runCatching { controller.submitText(inputText); inputText = "" }.onFailure { localError = "RD_INPUT_FAILED" }
                        }) { Text(text("发送文本", "Send text")) }
                        state.textStatus?.let { status -> Text(when (status) {
                            "pending" -> text("等待远端确认输入", "Waiting for remote confirmation")
                            "confirmed" -> text("远端已确认输入", "Remote input confirmed")
                            "unconfirmed" -> text("未收到确认，请先查看画面，避免重复输入", "Not confirmed. Check the screen before retrying")
                            else -> text("远端未能输入文本", "Remote text input failed")
                        }, style = MaterialTheme.typography.bodySmall) }
                    }
                    "audio" -> {
                        Text(text("声音", "Audio"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedButton(enabled = controller.canUse("audio.system"), onClick = {
                            runCatching { controller.requestFeature("audio.system", true) }.onFailure { localError = "RD_AUDIO_UNAVAILABLE" }
                        }) { Text(text("请求接收远端声音", "Request remote system audio")) }
                        OutlinedButton(enabled = controller.canUse("audio.microphone"), onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                runCatching { controller.requestFeature("audio.microphone", true) }.onFailure { localError = "RD_MICROPHONE_UNAVAILABLE" }
                            else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(text("请求麦克风回传", "Request microphone return")) }
                        Text(text("声音需要会话授权；请求不会自动开启权限。", "Audio requires session permission; requesting it does not grant access."), style = MaterialTheme.typography.bodySmall)
                    }
                    "clipboard" -> {
                        Text(text("文本剪贴板", "Text clipboard"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (state.clipboardEnabled) text("已开启双向同步", "Two-way sync is on")
                            else text("等待远端授权或系统剪贴板", "Waiting for remote authorization or system clipboard"))
                        Text(text("连接且应用位于前台时自动同步纯文本，最多 64 KB。离开应用后立即暂停。", "Plain text syncs automatically while connected and foregrounded, up to 64 KB. It pauses when you leave the app."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    "files" -> {
                        Text(text("文件", "Files"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        OutlinedButton(enabled = controller.canUse("files.send"), onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text(text("选择文件", "Choose files"))
                        }
                        Text(if (selected.isEmpty()) text("尚未选择文件。文件传输仍需远端确认。", "No files selected. Transfer still requires host approval.")
                            else text("已选择，尚未发送：", "Selected, not sent: ") + selected.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text(text("会话信息", "Session information"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(state.hostName ?: text("远程电脑", "Remote computer"))
                        Text(text("连接状态：", "Connection: ") + state.phase)
                        state.display?.let { Text("${it.width} × ${it.height}") }
                        Text(text("仅使用 UDP 直连。切到后台会暂停输入与麦克风。", "Direct UDP only. Backgrounding pauses input and microphone."), style = MaterialTheme.typography.bodySmall)
                    }
                }
                (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    }
    if (authenticate) {
        var password by remember { mutableStateOf("") }
        var mfa by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { authenticate = false }, title = { Text(text("重新验证账号", "Verify your account")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text(text("密码", "Password")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (mfaRequired) OutlinedTextField(mfa, { mfa = it }, label = { Text(text("动态码或恢复码", "MFA or recovery code")) }, singleLine = true)
                if (state.error != null && state.error != "MFA_REQUIRED") Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(enabled = password.isNotEmpty() && !state.loading && (!mfaRequired || mfa.isNotBlank()), onClick = {
                controller.authenticate(password, mfa)
            }) { Text(text("验证", "Verify")) } },
            dismissButton = { TextButton(onClick = { authenticate = false }) { Text(text("取消", "Cancel")) } })
    }
    trustTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!state.loading) { trustTarget = null; trustPassword = ""; trustMfa = ""; trustMfaRequired = false } },
            title = { Text(text("绑定可信设备", "Trust this device")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.name)
                Text(text("请重新验证账号；被控电脑的本机管理员仍需批准持续授权。", "Verify your account again. The host administrator must still approve persistent access."))
                OutlinedTextField(trustPassword, { trustPassword = it }, label = { Text(text("密码", "Password")) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (trustMfaRequired) OutlinedTextField(trustMfa, { trustMfa = it }, label = { Text(text("动态码或恢复码", "MFA or recovery code")) }, singleLine = true)
                state.error?.let { if (it != "MFA_REQUIRED") Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled = trustPassword.isNotEmpty() && !state.loading && (!trustMfaRequired || trustMfa.isNotBlank()),
                onClick = { controller.bindTrusted(target, permissions, trustPassword, trustMfa); trustMfa = "" }) {
                Text(text("验证并申请绑定", "Verify and request trust"))
            } },
            dismissButton = { TextButton(onClick = { trustTarget = null; trustPassword = ""; trustMfa = ""; trustMfaRequired = false }, enabled = !state.loading) {
                Text(text("取消", "Cancel"))
            } })
    }
    state.pairing?.let { pending ->
        val assisted = pending.host.assistInviteId != null
        AlertDialog(onDismissRequest = { controller.cancelPairing() }, title = { Text(if (assisted) text("正在验证连接", "Verifying connection") else text("等待被控端批准", "Waiting for host approval")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pending.host.name)
                Text(pending.code ?: if (assisted) text("正在核对设备身份与本次授权。", "Checking the device identity and access grant.") else text("等待被控电脑本机批准。", "Waiting for approval on the host."))
                Text(if (assisted) text("画面与标准输入将自动连接；附加功能仍需被控端支持。", "Screen and standard input connect automatically; extra features require host support.")
                    else if (pending.transcript["mode"] == JsonPrimitive("persistent")) text("本机管理员批准后，将绑定此控制端并自动连接。持续授权可在被控端随时撤销。", "After the host administrator approves, this controller will be trusted and connect automatically. The host can revoke access at any time.")
                    else text("批准后将自动建立安全连接。", "The secure connection starts automatically after approval."))
            } },
            confirmButton = { TextButton(onClick = { controller.cancelPairing() }, enabled = !state.loading) { Text(text("取消", "Cancel")) } })
    }
}

@Composable
private fun RemoteSessionView(
    state: RemoteViewState,
    controller: RemoteController,
    localError: String?,
    landscape: Boolean,
    onLandscape: () -> Unit,
    onBack: () -> Unit,
    onPanel: (String) -> Unit,
    onControlError: (String) -> Unit,
) {
    val backdrop = Color(0xFF171A27)
    val toolbar = Color(0xFF202434)
    val muted = Color(0xFFADB4CC)
    val connected = state.phase == "active"
    Column(Modifier.fillMaxSize().background(backdrop).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(68.dp).background(Color(0xFF151923)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_session_close), contentDescription = text("结束并返回", "End and go back")) }
            Column(Modifier.weight(1f)) {
                Text(state.hostName ?: text("远程电脑", "Remote computer"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(text("远程控制 · UDP 直连", "Remote control · direct UDP"), color = muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(if (connected) text("● 已连接", "● Connected") else text("● 连接中", "● Connecting"),
                color = if (connected) Color(0xFF72D4AD) else Color(0xFFF3CA7C), style = MaterialTheme.typography.labelSmall)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(backdrop), contentAlignment = Alignment.Center) {
            val aspect = state.display?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 16f / 9f
            val canvasWidth = minOf(maxWidth, maxHeight * aspect)
            val canvasHeight = canvasWidth / aspect
            Box(Modifier.width(canvasWidth).height(canvasHeight).clip(RoundedCornerShape(7.dp)).background(Color.Black)) {
                AndroidView(factory = { viewContext -> RemoteSurfaceView(viewContext, controller) },
                    update = { it.display(state.display) }, modifier = Modifier.fillMaxSize())
                if (!connected) Text(text("正在建立安全连接…", "Establishing a secure connection…"),
                    Modifier.align(Alignment.Center).background(Color(0xCC151923), RoundedCornerShape(9.dp)).padding(14.dp),
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            if (connected) Text(if (state.inputEnabled) text("轻触点击 · 拖动移动鼠标", "Tap to click · drag to move pointer")
                else text("仅查看 · 点按下方鼠标请求控制", "View only · tap Mouse to request control"),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), color = muted, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onPanel("info") }, modifier = Modifier.size(40.dp).background(toolbar, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(R.drawable.ic_session_info), contentDescription = text("会话信息", "Session information"), modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onLandscape, modifier = Modifier.size(40.dp).background(toolbar, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(R.drawable.ic_session_rotate), contentDescription = if (landscape) text("切换竖屏", "Portrait") else text("切换横屏", "Landscape"), modifier = Modifier.size(19.dp))
                }
            }
            (localError ?: state.error)?.let { error ->
                Text(error, Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0xFF512834), RoundedCornerShape(8.dp)).padding(8.dp),
                    color = Color(0xFFFFC6CF), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth().height(70.dp).background(toolbar).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteSessionTool(R.drawable.ic_session_mouse, text("鼠标", "Mouse"), state.inputEnabled, Modifier.weight(1f), connected) {
                if (state.inputEnabled) runCatching { controller.releaseControl() }.onFailure { onControlError("RD_CONTROL_NOT_READY") }
                else runCatching { controller.requestControl() }.onFailure { onControlError("RD_CONTROL_NOT_READY") }
            }
            RemoteSessionTool(R.drawable.ic_session_keyboard, text("键盘", "Keyboard"), false, Modifier.weight(1f), true) { onPanel("keyboard") }
            RemoteSessionTool(R.drawable.ic_action_copy, text("剪贴板", "Clipboard"), state.clipboardEnabled, Modifier.weight(1f), true) { onPanel("clipboard") }
            RemoteSessionTool(R.drawable.ic_session_display, text("画面", "Display"), false, Modifier.weight(1f), true) { onPanel("info") }
            RemoteSessionTool(R.drawable.ic_session_audio, text("声音", "Audio"), false, Modifier.weight(1f), true) { onPanel("audio") }
            RemoteSessionTool(R.drawable.ic_session_file, text("文件", "Files"), false, Modifier.weight(1f), true) { onPanel("files") }
            RemoteSessionTool(R.drawable.ic_session_close, text("断开", "End"), false, Modifier.weight(1f), true, Color(0xFFFF9EAF), onBack)
        }
    }
}

@Composable
private fun RemoteSessionTool(icon: Int, label: String, selected: Boolean, modifier: Modifier, enabled: Boolean,
    tint: Color = Color(0xFFDDE2F2), onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.size(38.dp).background(if (selected) Color(0xFF3D416B) else Color.Transparent, RoundedCornerShape(10.dp))) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(21.dp), tint = if (enabled) tint else tint.copy(alpha = .45f))
        }
        Text(label, color = if (selected) Color(0xFFB9B7FF) else tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun permissionLabel(permission: String): String = when (permission) {
    "view" -> text("查看画面", "View screen")
    "input.keyboard" -> text("键盘", "Keyboard")
    "input.pointer" -> text("鼠标与触控", "Pointer and touch")
    "input.text" -> text("输入法文本", "IME text")
    "audio.system" -> text("接收系统声音", "Receive system audio")
    "audio.microphone" -> text("麦克风回传", "Microphone return")
    "clipboard.read" -> text("读取远端文本剪贴板", "Read remote text clipboard")
    "clipboard.write" -> text("写入远端文本剪贴板", "Write remote text clipboard")
    "files.send" -> text("发送文件", "Send files")
    "files.receive" -> text("接收文件", "Receive files")
    else -> permission
}
