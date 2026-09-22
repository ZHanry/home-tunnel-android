package io.github.zhanry.hometunnel.remote

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun text(zh: String, en: String): String = if (LocalConfiguration.current.locales[0].language == "zh") zh else en

@Composable
fun RemoteScreen(controller: RemoteController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticate by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(setOf("view", "input.keyboard", "input.pointer", "input.text")) }
    var selected by remember { mutableStateOf(emptyList<RemoteSelectedFile>()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
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
    BackHandler { controller.closeSession(); onBack() }
    DisposableEffect(controller) { onDispose { controller.setSurface(null) } }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { controller.closeSession(); onBack() }) { Text(text("返回", "Back")) }
                TextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text(text("刷新", "Refresh")) }
            }
            Text(text("远程桌面", "Remote desktop"), style = MaterialTheme.typography.headlineMedium)
            Text(text("同账号设备之间直连；被控电脑必须本机批准。", "Direct connections between your devices; the host must approve locally."))
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error != null || localError != null) item {
            Text(localError ?: state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        if (!state.enabled && !state.loading) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Text(text("该服务器未启用远程桌面。现有隧道管理仍可使用。", "This server has not enabled remote desktop. Tunnel management remains available."), Modifier.padding(16.dp)) }
        }
        if (!state.native.available) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text("此预览包尚不能建立画面、音频或文件连接", "This preview cannot yet establish video, audio or file connections"))
                Text(text("可验证账号身份与配对流程。远控引擎就绪后，连接功能才会开放。", "Account identity and pairing are available for verification. Connections become available when the remote engine is ready."), style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (state.enabled && !state.authenticated) item {
            Button(onClick = { authenticate = true }, enabled = !state.loading) { Text(text("验证账号并启用此控制端", "Verify account and enroll this controller")) }
        }
        state.fingerprint?.let { fingerprint -> item {
            Text(text("本机身份指纹", "This controller's fingerprint"), style = MaterialTheme.typography.labelLarge)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall)
        } }
        if (state.authenticated) item {
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
        items(state.endpoints, key = { it.id }) { endpoint ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.titleMedium)
                    Text(endpoint.fingerprint, style = MaterialTheme.typography.bodySmall)
                    Text(if (endpoint.available) text("已在本机开启", "Enabled on host") else text("被控端未开启或已撤销", "Host disabled or revoked"))
                    OutlinedButton(onClick = { controller.pair(endpoint, permissions) }, enabled = state.authenticated && endpoint.available && !state.loading && state.pairing == null) {
                        Text(text("请求本机配对", "Request local pairing"))
                    }
                }
            }
        }
        if (state.sessionId != null) item {
            Text(state.phase)
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AndroidView(factory = { viewContext -> RemoteSurfaceView(viewContext, controller) },
                    update = { it.display(state.display) }, modifier = Modifier.fillMaxSize())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = state.phase == "active" && !state.inputEnabled, onClick = {
                    runCatching { controller.requestControl() }.onFailure { localError = "RD_CONTROL_NOT_READY" }
                }) { Text(text("请求控制", "Request control")) }
                OutlinedButton(enabled = state.inputEnabled, onClick = { runCatching { controller.releaseControl() } }) { Text(text("释放控制", "Release control")) }
            }
            Text(if (state.inputEnabled) text("已取得控制：轻触点击，拖动移动鼠标。", "Control enabled: tap to click and drag to move the pointer.")
                else text("仅查看画面。请求控制并完成同步后才能输入。", "Viewing only. Request control and wait for synchronization before input."))
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
            OutlinedButton(enabled = inputText.isNotEmpty() && state.textStatus != "pending" && state.inputEnabled && controller.canUse("input.text"), onClick = {
                runCatching { controller.submitText(inputText); inputText = "" }.onFailure { localError = "RD_INPUT_FAILED" }
            }) { Text(text("发送文本", "Send text")) }
            state.textStatus?.let { status -> Text(when (status) {
                "pending" -> text("等待远端确认文本输入。", "Waiting for text input confirmation.")
                "confirmed" -> text("远端已确认文本输入。", "The host confirmed text input.")
                "unconfirmed" -> text("未收到确认，请先检查远端画面，避免重复输入。", "No confirmation received. Check the remote screen before retrying.")
                else -> text("远端未能输入文本。", "The host could not enter the text.")
            }) }
            Button(onClick = { controller.closeSession() }) { Text(text("断开连接", "Disconnect")) }
        }
        item {
            HorizontalDivider()
            Text(text("会话工具", "Session tools"), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = controller.canUse("audio.microphone"), onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    runCatching { controller.requestFeature("audio.microphone", true) }.onFailure { localError = "RD_MICROPHONE_UNAVAILABLE" }
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(text("开启麦克风回传", "Enable microphone return")) }
            OutlinedButton(enabled = controller.canUse("files.send"), onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(text("选择待发送文件", "Choose files to send"))
            }
            if (selected.isNotEmpty()) Text(text("已选择，尚未发送：", "Selected, not sent: ") + selected.joinToString { it.name })
            Text(text("音频、剪贴板和文件权限默认关闭，需会话授权与本机确认。切换到后台会暂停控制和麦克风。", "Audio, clipboard and files are off by default and require session permission and local consent. Backgrounding pauses control and microphone."), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (authenticate) {
        var password by remember { mutableStateOf("") }
        var mfa by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { authenticate = false }, title = { Text(text("重新验证账号", "Verify your account")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text(text("密码", "Password")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(mfa, { mfa = it }, label = { Text(text("双重验证代码（如已开启）", "MFA code (if enabled)")) }, singleLine = true)
            } },
            confirmButton = { Button(enabled = password.isNotEmpty(), onClick = {
                controller.authenticate(password, mfa); password = ""; mfa = ""; authenticate = false
            }) { Text(text("验证", "Verify")) } },
            dismissButton = { TextButton(onClick = { authenticate = false }) { Text(text("取消", "Cancel")) } })
    }
    state.pairing?.let { pending ->
        AlertDialog(onDismissRequest = { controller.cancelPairing() }, title = { Text(text("核对被控电脑上的配对码", "Compare the code on the host")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pending.host.name)
                Text(pending.code ?: text("等待被控电脑本机批准。", "Waiting for approval on the host."))
                Text(text("只在两个屏幕显示完全相同的码时确认。", "Confirm only when both screens show exactly the same code."))
                TextButton(onClick = { controller.refreshPairing() }, enabled = !state.loading) { Text(text("刷新配对码", "Refresh pairing code")) }
            } },
            confirmButton = { Button(onClick = { controller.confirmPairing() }, enabled = pending.code != null && !state.loading) { Text(text("两边一致，确认", "Codes match, confirm")) } },
            dismissButton = { TextButton(onClick = { controller.cancelPairing() }, enabled = !state.loading) { Text(text("拒绝", "Reject")) } })
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
