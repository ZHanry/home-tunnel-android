package io.github.zhanry.hometunnel.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.ui.remote.ConnectMode
import io.github.zhanry.hometunnel.ui.remote.RecentCodes
import io.github.zhanry.hometunnel.ui.remote.RemoteStage
import io.github.zhanry.hometunnel.ui.remote.SessionControl
import io.github.zhanry.hometunnel.ui.remote.connectModes
import io.github.zhanry.hometunnel.ui.remote.displayCaption
import io.github.zhanry.hometunnel.ui.remote.failureActionKey
import io.github.zhanry.hometunnel.ui.remote.remoteStage
import io.github.zhanry.hometunnel.ui.remote.visibleSessionControls
import kotlinx.serialization.json.JsonPrimitive

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(controller: RemoteController, onBack: () -> Unit, accountKey: String = "", initialCode: String = "") {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticate by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(setOf("view", "input.keyboard", "input.pointer", "input.text", "clipboard.read", "clipboard.write")) }
    var selected by remember { mutableStateOf(emptyList<RemoteSelectedFile>()) }
    var pickerSession by rememberSaveable { mutableStateOf<String?>(null) }
    var saveSession by rememberSaveable { mutableStateOf<String?>(null) }
    var saveFileId by rememberSaveable { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var assistDeviceId by rememberSaveable { mutableStateOf(initialCode.filter(Char::isDigit).take(9)) }
    var assistPassword by remember { mutableStateOf("") }
    var assistMode by rememberSaveable { mutableStateOf(ConnectMode.APPROVAL.name) }
    var mfaRequired by remember { mutableStateOf(false) }
    var trustTarget by remember { mutableStateOf<RemoteEndpoint?>(null) }
    var trustPassword by remember { mutableStateOf("") }
    var trustMfa by remember { mutableStateOf("") }
    var trustMfaRequired by remember { mutableStateOf(false) }
    var sessionPanel by remember { mutableStateOf<String?>(null) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val activity = context.activity()
    val files = remember { RemoteFiles(context.contentResolver) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val session = pickerSession
        if (uris.isNotEmpty() && session != null && controller.state.value.sessionId == session && controller.filesTransportReady()) scope.launch {
            try {
                val selection = withContext(Dispatchers.IO) { files.selected(uris) }
                if (controller.state.value.sessionId == session) selected = selection
            }
            catch (_: Exception) { localError = "RD_FILE_TYPE_OR_SIZE_UNSUPPORTED" }
        }
        pickerSession = null
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val session = saveSession; val id = saveFileId
        if (uri != null && session != null && id != null) {
            runCatching { controller.saveFile(session, id, uri) }.onFailure { localError = "RD_FILE_SESSION_CHANGED" }
        }
        saveSession = null; saveFileId = null
    }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(initialCode) {
        val code = initialCode.filter(Char::isDigit).take(9)
        if (code.length == 9) assistDeviceId = code
    }
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
    LaunchedEffect(state.sessionId, accountKey) {
        selected = emptyList(); localError = null
        if (state.sessionId == null) { sessionPanel = null; landscape = false }
    }
    val stage = remoteStage(state.loading, state.enabled, state.authenticated, state.phase, localError ?: state.error, state.pairing != null, state.sessionId != null)
    val offered = state.endpoints.mapNotNull { it.offeredAccessModes }
    val modes = connectModes(
        state.native.available,
        state.endpoints.any { hostOffersUnattended(it) },
        if (offered.isEmpty()) null else offered.flatten().toSet(),
    )
    val selectedMode = ConnectMode.entries.firstOrNull { it.name == assistMode } ?: ConnectMode.APPROVAL
    MaterialTheme(colorScheme = if (state.sessionId != null) darkColorScheme(background = Color(0xFF171A2A), surface = Color(0xFF20243A), primary = Color(0xFFAAA9FF)) else MaterialTheme.colorScheme) {
    if (state.sessionId != null) {
        RemoteSessionView(state, controller, localError, landscape, permissions, onLandscape = { landscape = !landscape },
            onBack = { controller.closeSession(); onBack() }, onPanel = { sessionPanel = it },
            onControlError = { localError = it }, zoom = zoom, onZoom = { zoom = it })
    } else LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { controller.closeSession(); onBack() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.back)) }
                TextButton(onClick = { controller.refresh() }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_refresh)) }
            }
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.remote_intro))
            Text(stringResource(stageLabel(stage)), color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if ((state.error != null && state.error != "MFA_REQUIRED") || localError != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localError ?: state.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (stage == RemoteStage.FAILED) {
                    OutlinedButton(onClick = { localError = null; controller.refresh() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.remote_retry))
                    }
                }
            }
        }
        if (!state.enabled && !state.loading) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Text(stringResource(R.string.remote_unavailable), Modifier.padding(16.dp)) }
        }
        if (!state.native.available && state.sessionId == null) item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_native_title))
                Text(stringResource(R.string.remote_native_detail), style = MaterialTheme.typography.bodySmall)
            } }
        }
        if (state.enabled && !state.authenticated && state.sessionId == null) item {
            Button(onClick = { authenticate = true }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_enroll)) }
        }
        state.fingerprint?.takeIf { state.sessionId == null }?.let { fingerprint -> item {
            Text(stringResource(R.string.remote_fingerprint), style = MaterialTheme.typography.labelLarge)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall)
        } }
        if (state.authenticated && state.sessionId == null) item {
            val recent = RecentCodes.read(context, accountKey)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.remote_connect_other), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.remote_connect_other_detail), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode.mode,
                                onClick = { assistMode = mode.mode.name },
                                enabled = mode.enabled && !state.loading,
                                label = { Text(stringResource(modeLabel(mode.mode))) },
                            )
                        }
                    }
                    modes.firstOrNull { it.mode == selectedMode && !it.enabled }?.let { blocked ->
                        Text(stringResource(if (blocked.blockedReason == "unattended") R.string.remote_mode_unattended_off else R.string.remote_mode_native_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selectedMode == ConnectMode.UNATTENDED) {
                        if (modes.first { it.mode == ConnectMode.UNATTENDED }.enabled) {
                            Text(stringResource(R.string.remote_trust_detail), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        if (recent.isNotEmpty()) {
                            Text(stringResource(R.string.remote_recent), style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                recent.forEach { code ->
                                    FilterChip(selected = assistDeviceId == code, onClick = { assistDeviceId = code }, label = { Text(code) })
                                }
                            }
                        }
                        OutlinedTextField(assistDeviceId, { assistDeviceId = it.filter(Char::isDigit).take(9) },
                            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.remote_device_code)) }, singleLine = true)
                        if (selectedMode != ConnectMode.APPROVAL) OutlinedTextField(assistPassword, { assistPassword = it.take(128) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(if (selectedMode == ConnectMode.FIXED) R.string.remote_password_fixed else R.string.remote_password_once)) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(onClick = {
                            when (selectedMode) {
                                ConnectMode.APPROVAL -> controller.requestAccess(assistDeviceId, permissions)
                                ConnectMode.FIXED -> controller.fixedPassword(assistDeviceId, assistPassword, permissions)
                                ConnectMode.ONE_TIME -> controller.assist(assistDeviceId, assistPassword, permissions)
                                ConnectMode.UNATTENDED -> Unit
                            }
                            if (selectedMode != ConnectMode.UNATTENDED) {
                                RecentCodes.push(context, accountKey, assistDeviceId)
                                assistPassword = ""
                            }
                        }, enabled = selectedMode != ConnectMode.UNATTENDED && assistDeviceId.length == 9 && (selectedMode == ConnectMode.APPROVAL || assistPassword.isNotBlank()) && state.native.available && !state.loading && state.pairing == null,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (selectedMode == ConnectMode.APPROVAL) R.string.remote_send_request else R.string.remote_connect))
                        }
                        if (selectedMode == ConnectMode.APPROVAL && state.loading) Text(stringResource(R.string.remote_waiting_approval), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.remote_permissions), style = MaterialTheme.typography.titleMedium)
            P.permissions.forEach { permission ->
                Row {
                    Checkbox(checked = permission in permissions, enabled = permission != "view" && permission != "audio.microphone" && !state.loading && permission in state.native.permissions,
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
                    Text(if (endpoint.available) stringResource(R.string.remote_endpoint_on) else stringResource(R.string.remote_endpoint_off))
                    OutlinedButton(onClick = { controller.pair(endpoint, permissions) }, enabled = state.authenticated && state.native.available && endpoint.available && !state.loading && state.pairing == null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.remote_connect_device))
                    }
                    if (controller.canBindTrusted(endpoint, permissions)) {
                        OutlinedButton(onClick = {
                            trustTarget = endpoint; trustPassword = ""; trustMfa = ""; trustMfaRequired = false
                        }, enabled = state.authenticated && state.native.available && !state.loading && state.pairing == null, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.remote_trust))
                        }
                    }
                }
            }
        }
    }
    if (sessionPanel != null && state.sessionId != null) {
        ModalBottomSheet(onDismissRequest = { sessionPanel = null }, containerColor = Color(0xFF232739)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 22.dp, end = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (sessionPanel) {
                    "keyboard" -> {
                        Text(stringResource(R.string.remote_keyboard_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (controller.canUse("input.keyboard")) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Esc" to 41, "Tab" to 43, "Enter" to 40, "⌫" to 42).forEach { (label, usage) ->
                                OutlinedButton(enabled = state.inputEnabled && controller.canUse("input.keyboard"), onClick = {
                                    runCatching { controller.key(usage, true); controller.key(usage, false) }
                                        .onFailure { runCatching { controller.releaseControl() }; localError = "RD_INPUT_FAILED" }
                                }) { Text(label) }
                            }
                        }
                        if (controller.canUse("input.text")) OutlinedTextField(inputText, { if (it.toByteArray().size <= P.TEXT_BYTES) inputText = it },
                            enabled = state.inputEnabled, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.remote_text_label)) })
                        if (controller.canUse("input.text")) Button(enabled = inputText.isNotEmpty() && state.textStatus != "pending" && state.inputEnabled, onClick = {
                            runCatching { controller.submitText(inputText); inputText = "" }.onFailure { localError = "RD_INPUT_FAILED" }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_send_text)) }
                        state.textStatus?.let { status -> Text(when (status) {
                            "pending" -> stringResource(R.string.remote_text_pending)
                            "confirmed" -> stringResource(R.string.remote_text_confirmed)
                            "unconfirmed" -> stringResource(R.string.remote_text_unconfirmed)
                            else -> stringResource(R.string.remote_text_failed)
                        }, style = MaterialTheme.typography.bodySmall) }
                    }
                    "display" -> {
                        Text(stringResource(R.string.remote_display), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.remote_display_switch_note), style = MaterialTheme.typography.bodySmall)
                        state.displays.forEachIndexed { index, display ->
                            FilterChip(selected = display.id == state.display?.id, enabled = state.phase == "active", onClick = {
                                runCatching { controller.selectDisplay(requireNotNull(display.id)); sessionPanel = null }
                                    .onFailure { localError = "RD_DISPLAY_UNAVAILABLE" }
                            }, label = {
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(display.name ?: stringResource(R.string.remote_display_number, index + 1))
                                    Text(displayCaption(display), style = MaterialTheme.typography.bodySmall)
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        }
                        Text(stringResource(R.string.remote_zoom_percent, (zoom * 100).toInt()))
                        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f)
                        TextButton(onClick = { zoom = 1f }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_zoom_fit)) }
                        Text(stringResource(R.string.remote_zoom_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    "audio" -> {
                        Text(stringResource(R.string.remote_audio_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (controller.canUse("audio.system") && controller.audioTransportReady()) {
                            OutlinedButton(enabled = "audio.system" !in state.pendingFeatures, onClick = {
                                runCatching { controller.requestFeature("audio.system", !state.audioEnabled) }
                                    .onFailure { localError = "RD_AUDIO_UNAVAILABLE" }
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (state.audioEnabled) R.string.remote_audio_mute else R.string.remote_audio_request))
                            }
                            Text(stringResource(if ("audio.system" in state.pendingFeatures) R.string.remote_feature_pending
                                else if (state.audioEnabled) R.string.remote_audio_playing else R.string.remote_audio_note))
                        } else Text(stringResource(R.string.remote_audio_unavailable), style = MaterialTheme.typography.bodySmall)
                    }
                    "clipboard" -> {
                        Text(stringResource(R.string.remote_clipboard_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (state.clipboardEnabled) stringResource(R.string.remote_clipboard_on) else stringResource(R.string.remote_clipboard_off))
                        Text(stringResource(R.string.remote_clipboard_note), style = MaterialTheme.typography.bodySmall)
                    }
                    "files" -> {
                        Text(stringResource(R.string.remote_files_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (controller.canUse("files.send") && controller.filesTransportReady()) {
                            OutlinedButton(enabled = !state.filePreparing, onClick = {
                                pickerSession = state.sessionId; picker.launch(arrayOf("*/*"))
                            }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.remote_files_choose))
                            }
                            if (selected.isNotEmpty()) {
                                Text(stringResource(R.string.remote_files_selected, selected.joinToString { it.name }), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = !state.filePreparing && "files.send" !in state.pendingFeatures, onClick = {
                                    runCatching { controller.beginFileSend(selected); selected = emptyList() }.onFailure { localError = "RD_FILE_TRANSPORT_UNAVAILABLE" }
                                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_files_send)) }
                                TextButton(onClick = { selected = emptyList() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.remote_files_cancel))
                                }
                            }
                        }
                        if (controller.canUse("files.receive") && controller.filesTransportReady()) {
                            OutlinedButton(enabled = "files.receive" !in state.pendingFeatures, onClick = {
                                runCatching { controller.requestFeature("files.receive", !state.filesReceiveEnabled) }
                                    .onFailure { localError = "RD_FILE_TRANSPORT_UNAVAILABLE" }
                            }) { Text(stringResource(if (state.filesReceiveEnabled) R.string.remote_files_stop_receiving else R.string.remote_files_allow_receiving)) }
                        }
                        if (!controller.filesTransportReady()) Text(stringResource(R.string.remote_files_unavailable), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.remote_files_save_note), style = MaterialTheme.typography.bodySmall)
                        if (state.filePreparing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.remote_files_preparing)) }
                        state.fileTransfers.forEach { item ->
                            HorizontalDivider()
                            Text(item.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(fileStateLabel(item.status)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.remote_files_progress, item.offset, item.size), style = MaterialTheme.typography.bodySmall)
                            if (item.status == "progress") LinearProgressIndicator(progress = {
                                if (item.size == 0L) 0f else (item.offset.toDouble() / item.size).toFloat().coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth())
                            if (!item.outgoing && item.status == "offer") OutlinedButton(
                                enabled = state.filesReceiveEnabled && controller.canUse("files.receive"), onClick = {
                                    runCatching { controller.acceptFile(item.id) }.onFailure { localError = "RD_FILE_WRITE_FAILED" }
                                }) { Text(stringResource(R.string.remote_files_accept)) }
                            if (item.status == "ready_to_save") OutlinedButton(onClick = {
                                saveSession = state.sessionId; saveFileId = item.id; savePicker.launch(item.name)
                            }) { Text(stringResource(R.string.remote_files_save)) }
                            if (!item.terminal || item.status == "ready_to_save") TextButton(onClick = { controller.cancelFile(item.id) }) {
                                Text(stringResource(R.string.remote_files_cancel_transfer))
                            }
                            item.error?.let { Text(stringResource(R.string.remote_files_retry), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.remote_session_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(state.hostName ?: stringResource(R.string.remote_host_fallback))
                        Text(stringResource(stageLabel(remoteStage(false, true, true, state.phase, state.error, false, true))))
                        state.connectionPhase?.let { Text(stringResource(phaseLabel(it))) }
                        state.failureAction?.takeIf { it != "none" }?.let { Text(stringResource(actionLabel(failureActionKey(it)))) }
                        state.display?.let { display ->
                            Text(displayCaption(display))
                            if (!display.hasMetrics) Text(stringResource(R.string.remote_scale_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.remote_udp_note), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = state.phase == "active", onClick = {
                            runCatching { controller.retryConnection(); sessionPanel = null }
                                .onFailure { localError = "RD_RECONNECT_UNAVAILABLE" }
                        }) { Text(stringResource(R.string.remote_reconnect)) }
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
        AlertDialog(onDismissRequest = { authenticate = false }, title = { Text(stringResource(R.string.remote_verify_title)) },
            text = { Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (mfaRequired) OutlinedTextField(mfa, { mfa = it }, label = { Text(stringResource(R.string.remote_mfa)) }, singleLine = true)
                if (state.error != null && state.error != "MFA_REQUIRED") Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(enabled = password.isNotEmpty() && !state.loading && (!mfaRequired || mfa.isNotBlank()), onClick = {
                controller.authenticate(password, mfa)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.remote_verify_action)) } },
            dismissButton = { TextButton(onClick = { authenticate = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
    }
    trustTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!state.loading) { trustTarget = null; trustPassword = ""; trustMfa = ""; trustMfaRequired = false } },
            title = { Text(stringResource(R.string.remote_trust)) },
            text = { Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.name)
                Text(stringResource(R.string.remote_trust_detail))
                OutlinedTextField(trustPassword, { trustPassword = it }, label = { Text(stringResource(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (trustMfaRequired) OutlinedTextField(trustMfa, { trustMfa = it }, label = { Text(stringResource(R.string.remote_mfa)) }, singleLine = true)
                state.error?.let { if (it != "MFA_REQUIRED") Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled = trustPassword.isNotEmpty() && !state.loading && (!trustMfaRequired || trustMfa.isNotBlank()),
                onClick = { controller.bindTrusted(target, permissions, trustPassword, trustMfa); trustMfa = "" }) {
                Text(stringResource(R.string.remote_trust_action))
            } },
            dismissButton = { TextButton(onClick = { trustTarget = null; trustPassword = ""; trustMfa = ""; trustMfaRequired = false }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            } })
    }
    state.pairing?.let { pending ->
        val assisted = pending.host.assistInviteId != null
        AlertDialog(onDismissRequest = { controller.cancelPairing() }, title = { Text(stringResource(if (assisted) R.string.remote_pairing_verify else R.string.remote_pairing_wait)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pending.host.name)
                Text(pending.code ?: stringResource(if (assisted) R.string.remote_pairing_check else R.string.remote_pairing_host))
                Text(when {
                    assisted -> stringResource(R.string.remote_pairing_auto)
                    pending.transcript["mode"] == JsonPrimitive("persistent") -> stringResource(R.string.remote_pairing_persistent)
                    else -> stringResource(R.string.remote_pairing_secure)
                })
            } },
            confirmButton = { TextButton(onClick = { controller.cancelPairing() }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
    }
}

private fun fileStateLabel(status: String): Int = when (status) {
    "offer" -> R.string.remote_files_waiting
    "progress" -> R.string.remote_files_transferring
    "complete" -> R.string.remote_files_delivered
    "ready_to_save" -> R.string.remote_files_verified
    "saving" -> R.string.remote_files_saving
    "saved" -> R.string.remote_files_saved
    "cancelled" -> R.string.remote_files_cancelled
    else -> R.string.remote_files_failed
}

private fun phaseLabel(phase: String): Int = when (phase) {
    "waiting_for_approval" -> R.string.remote_phase_approval
    "direct_connect" -> R.string.remote_phase_direct
    "active" -> R.string.remote_phase_active
    "recovering" -> R.string.remote_phase_recovering
    "ending" -> R.string.remote_phase_ending
    "ended" -> R.string.remote_phase_ended
    else -> R.string.remote_phase_unknown
}

private fun actionLabel(action: String): Int = when (action) {
    "check_udp_path" -> R.string.remote_action_udp
    "retry_session" -> R.string.remote_action_retry
    "request_permission" -> R.string.remote_action_permission
    "reauthenticate" -> R.string.remote_action_reauthenticate
    "enable_host" -> R.string.remote_action_enable_host
    "request_grant" -> R.string.remote_action_grant
    "wait_for_host" -> R.string.remote_action_wait_host
    "wait_for_approval" -> R.string.remote_action_wait_approval
    "enable_unattended" -> R.string.remote_action_unattended
    "reduce_permissions" -> R.string.remote_action_reduce
    "new_session" -> R.string.remote_action_new
    "reenroll" -> R.string.remote_action_reenroll
    "switch_display" -> R.string.remote_action_display
    else -> R.string.remote_action_unknown
}

private fun stageLabel(stage: RemoteStage): Int = when (stage) {
    RemoteStage.LOADING -> R.string.remote_stage_loading
    RemoteStage.UNAVAILABLE -> R.string.remote_stage_unavailable
    RemoteStage.READY -> R.string.remote_stage_ready
    RemoteStage.AUTHENTICATION -> R.string.remote_stage_auth
    RemoteStage.APPROVAL -> R.string.remote_stage_approval
    RemoteStage.DIRECT_UDP -> R.string.remote_stage_udp
    RemoteStage.CONNECTED -> R.string.remote_stage_connected
    RemoteStage.PAUSED -> R.string.remote_stage_paused
    RemoteStage.FAILED -> R.string.remote_stage_failed
}

private fun modeLabel(mode: ConnectMode): Int = when (mode) {
    ConnectMode.APPROVAL -> R.string.remote_mode_approval
    ConnectMode.ONE_TIME -> R.string.remote_mode_once
    ConnectMode.FIXED -> R.string.remote_mode_fixed
    ConnectMode.UNATTENDED -> R.string.remote_mode_unattended
}

@Composable
private fun RemoteSessionView(
    state: RemoteViewState,
    controller: RemoteController,
    localError: String?,
    landscape: Boolean,
    requested: Set<String>,
    onLandscape: () -> Unit,
    onBack: () -> Unit,
    onPanel: (String) -> Unit,
    onControlError: (String) -> Unit,
    zoom: Float,
    onZoom: (Float) -> Unit,
) {
    val backdrop = Color(0xFF171A27)
    val toolbar = Color(0xFF202434)
    val muted = Color(0xFFADB4CC)
    val connected = state.phase == "active"
    val authorized = P.permissions.filter { controller.canUse(it) }.toSet()
    val controls = visibleSessionControls(authorized, state.display != null)
    val stage = remoteStage(false, true, true, state.phase, localError ?: state.error, false, true)
    Column(Modifier.fillMaxSize().background(backdrop).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Color(0xFF151923)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_session_close), contentDescription = stringResource(R.string.remote_end_back)) }
            Column(Modifier.weight(1f)) {
                Text(state.hostName ?: stringResource(R.string.remote_host_fallback), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.remote_udp_label), color = muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(stringResource(stageLabel(stage)),
                color = if (connected) Color(0xFF72D4AD) else Color(0xFFF3CA7C), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(backdrop), contentAlignment = Alignment.Center) {
            val aspect = state.display?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 16f / 9f
            val canvasWidth = minOf(maxWidth, maxHeight * aspect)
            val canvasHeight = canvasWidth / aspect
            Box(Modifier.width(canvasWidth).height(canvasHeight).clip(RoundedCornerShape(7.dp)).background(Color.Black)) {
                AndroidView(factory = { viewContext -> RemoteSurfaceView(viewContext, controller, onZoom) },
                    update = { it.display(state.display); it.zoom(zoom) }, modifier = Modifier.fillMaxSize())
                if (!connected) Text(stringResource(when (state.phase) {
                    "switching_display" -> R.string.remote_switching_display
                    "reconnecting" -> R.string.remote_reconnecting
                    else -> R.string.remote_connecting_overlay
                }),
                    Modifier.align(Alignment.Center).background(Color(0xCC151923), RoundedCornerShape(9.dp)).padding(14.dp),
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            if (connected) Text(
                if (SessionControl.POINTER in controls && state.inputEnabled) stringResource(R.string.remote_input_hint)
                else stringResource(R.string.remote_view_only),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), color = muted, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onPanel("info") }, modifier = Modifier.size(48.dp).background(toolbar, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(R.drawable.ic_session_info), contentDescription = stringResource(R.string.remote_info), modifier = Modifier.size(19.dp))
                }
                IconButton(onClick = onLandscape, modifier = Modifier.size(48.dp).background(toolbar, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(R.drawable.ic_session_rotate), contentDescription = stringResource(if (landscape) R.string.remote_portrait else R.string.remote_landscape), modifier = Modifier.size(19.dp))
                }
            }
            (localError ?: state.error)?.let { error ->
                Text(error, Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0xFF512834), RoundedCornerShape(8.dp)).padding(8.dp),
                    color = Color(0xFFFFC6CF), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(toolbar).horizontalScroll(rememberScrollState()).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (SessionControl.POINTER in controls) RemoteSessionTool(R.drawable.ic_session_mouse, stringResource(R.string.remote_mouse), state.inputEnabled, Modifier.width(72.dp), connected && "input.pointer" in requested) {
                if (state.inputEnabled) runCatching { controller.releaseControl() }.onFailure { onControlError("RD_CONTROL_NOT_READY") }
                else runCatching { controller.requestControl() }.onFailure { onControlError("RD_CONTROL_NOT_READY") }
            }
            if (SessionControl.KEYBOARD in controls || SessionControl.UNICODE in controls) RemoteSessionTool(R.drawable.ic_session_keyboard, stringResource(R.string.remote_keyboard), false, Modifier.width(72.dp), true) { onPanel("keyboard") }
            if (SessionControl.CLIPBOARD in controls) RemoteSessionTool(R.drawable.ic_action_copy, stringResource(R.string.remote_clipboard), state.clipboardEnabled, Modifier.width(72.dp), true) { onPanel("clipboard") }
            if (SessionControl.DISPLAY in controls) RemoteSessionTool(R.drawable.ic_session_display, stringResource(R.string.remote_display), false, Modifier.width(72.dp), connected) { onPanel("display") }
            if (SessionControl.SYSTEM_AUDIO in controls || SessionControl.MICROPHONE in controls) RemoteSessionTool(R.drawable.ic_session_audio, stringResource(R.string.remote_audio), false, Modifier.width(72.dp), true) { onPanel("audio") }
            if (SessionControl.FILES in controls) RemoteSessionTool(R.drawable.ic_session_file, stringResource(R.string.remote_files), false, Modifier.width(72.dp), true) { onPanel("files") }
            RemoteSessionTool(R.drawable.ic_session_close, stringResource(R.string.remote_end), false, Modifier.width(72.dp), true, Color(0xFFFF9EAF), onBack)
        }
    }
}

@Composable
private fun RemoteSessionTool(icon: Int, label: String, selected: Boolean, modifier: Modifier, enabled: Boolean,
    tint: Color = Color(0xFFDDE2F2), onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.size(48.dp).background(if (selected) Color(0xFF3D416B) else Color.Transparent, RoundedCornerShape(10.dp))) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(21.dp), tint = if (enabled) tint else tint.copy(alpha = .45f))
        }
        Text(label, color = if (selected) Color(0xFFB9B7FF) else tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun permissionLabel(permission: String): String = when (permission) {
    "view" -> stringResource(R.string.remote_perm_view)
    "input.keyboard" -> stringResource(R.string.remote_perm_keyboard)
    "input.pointer" -> stringResource(R.string.remote_perm_pointer)
    "input.text" -> stringResource(R.string.remote_perm_text)
    "audio.system" -> stringResource(R.string.remote_perm_system_audio)
    "audio.microphone" -> stringResource(R.string.remote_perm_mic)
    "clipboard.read" -> stringResource(R.string.remote_perm_clip_read)
    "clipboard.write" -> stringResource(R.string.remote_perm_clip_write)
    "files.send" -> stringResource(R.string.remote_perm_files_send)
    "files.receive" -> stringResource(R.string.remote_perm_files_receive)
    else -> permission
}
