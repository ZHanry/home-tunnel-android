@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.zhanry.hometunnel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zhanry.hometunnel.R
import io.github.zhanry.hometunnel.model.ConnectionCapabilities
import io.github.zhanry.hometunnel.model.ManagedDevice
import io.github.zhanry.hometunnel.model.TunnelDiagnostic
import io.github.zhanry.hometunnel.model.diagnosticFailureKey
import io.github.zhanry.hometunnel.model.ProxyKind
import io.github.zhanry.hometunnel.model.TunnelConnection
import io.github.zhanry.hometunnel.ui.tunnel.TUNNEL_TEMPLATES
import io.github.zhanry.hometunnel.ui.tunnel.TunnelFailureKind
import io.github.zhanry.hometunnel.ui.tunnel.TunnelTemplateId
import io.github.zhanry.hometunnel.ui.tunnel.WizardFields
import io.github.zhanry.hometunnel.ui.tunnel.WizardStep
import io.github.zhanry.hometunnel.ui.tunnel.assignedPort
import io.github.zhanry.hometunnel.ui.tunnel.baselineFields
import io.github.zhanry.hometunnel.ui.tunnel.classifyTunnelFailure
import io.github.zhanry.hometunnel.ui.tunnel.exportWizardState
import io.github.zhanry.hometunnel.ui.tunnel.importWizardState
import io.github.zhanry.hometunnel.ui.tunnel.templateById
import io.github.zhanry.hometunnel.ui.tunnel.templatePermitted
import io.github.zhanry.hometunnel.ui.tunnel.validateWizard
import io.github.zhanry.hometunnel.ui.tunnel.withTemplate
import io.github.zhanry.hometunnel.ui.tunnel.wizardChanged

internal data class ConnectionEdit(
    val value: TunnelConnection,
    val isNew: Boolean,
    val baseline: TunnelConnection = value,
    val step: Int = 0,
    val fields: WizardFields = baselineFields(value),
) {
    fun exportState(): List<String> = exportWizardState(isNew, step, value, baseline, fields)

    fun changed(): Boolean = wizardChanged(fields, baselineFields(baseline))
}

internal fun importConnectionEdit(saved: List<String>): ConnectionEdit? {
    val imported = importWizardState(saved) ?: return null
    return ConnectionEdit(imported.value, imported.isNew, imported.baseline, imported.step, imported.fields)
}

internal val ConnectionEditSaver = object : Saver<ConnectionEdit?, String> {
    override fun SaverScope.save(value: ConnectionEdit?): String? =
        value?.exportState()?.joinToString("\u0001")

    override fun restore(value: String): ConnectionEdit? = importConnectionEdit(value.split("\u0001"))
}

@Composable
private fun Modifier.visibleEditorField(): Modifier = fillMaxWidth().keepFocusedFieldVisible()

@Composable
internal fun ConnectionEditor(
    edit: ConnectionEdit,
    devices: List<ManagedDevice>,
    capabilities: ConnectionCapabilities,
    busy: Boolean,
    error: String?,
    reported: TunnelConnection? = null,
    waitingForServer: Boolean = false,
    reportUnconfirmed: Boolean = false,
    onEdit: (ConnectionEdit) -> Unit,
    onReloadLatest: () -> Unit,
    onRefreshReport: () -> Unit = {},
    onCopyAddress: (String) -> Unit = {},
    onDone: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (TunnelConnection) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val template = templateById(edit.fields.templateId)
    val unknown = edit.value.kind == ProxyKind.UNKNOWN
    val activeIds = devices.filter { it.status == "active" }.map { it.id }.toSet()
    val issues = validateWizard(edit.fields, capabilities, edit.isNew, activeIds, unknown)
    val step = WizardStep.entries[edit.step.coerceIn(0, 3)]
    val canSave = issues.isEmpty()
    val showingResult = waitingForServer || reported != null || reportUnconfirmed
    val parsedPort = edit.fields.port.toIntOrNull()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = {
        if (!busy) {
            if (showingResult) onDone()
            else if (edit.changed()) confirmDiscard = true else onDismiss()
        }
    }
    BackHandler(onBack = requestClose)
    Box(Modifier.fillMaxSize()) {
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (edit.isNew) R.string.add_connection else R.string.edit_connection)) },
                    navigationIcon = {
                        IconButton(onClick = requestClose, enabled = !busy, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_action_back), contentDescription = stringResource(R.string.wizard_back))
                        }
                    },
                )
            },
            bottomBar = {
                // Leave the short landscape viewport available to the focused field.
                // The system Back/IME action restores the navigation controls.
                if (!keyboardVisible) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (edit.step > 0) {
                        TextButton(onClick = { onEdit(edit.copy(step = edit.step - 1)) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.wizard_back))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showingResult) {
                            OutlinedButton(onClick = onRefreshReport, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.wizard_result_refresh))
                            }
                            Button(onClick = onDone, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.wizard_result_done))
                            }
                        } else OutlinedButton(onClick = requestClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                        if (!showingResult && edit.step < 3) {
                            Button(
                                onClick = { onEdit(edit.copy(step = edit.step + 1)) },
                                enabled = !busy && issues.none { it.step == step },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.wizard_next)) }
                        }
                        if (!showingResult) Button(
                            onClick = {
                                val chosen = template ?: return@Button
                                val port = parsedPort ?: return@Button
                                onSave(edit.value.copy(
                                    deviceId = edit.fields.deviceId,
                                    proxyType = chosen.transport.wireName,
                                    applicationProtocol = chosen.applicationProtocol,
                                    name = edit.fields.name.trim(),
                                    subdomain = if (chosen.raw) edit.value.subdomain else edit.fields.subdomain.trim(),
                                    localScheme = if (chosen.raw) edit.value.localScheme else edit.fields.scheme,
                                    localHost = edit.fields.host.trim(),
                                    localPort = port,
                                    enabled = edit.fields.enabled,
                                ))
                            },
                            enabled = canSave && !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(stepTitle(step)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                if (error != null) {
                    val kind = classifyTunnelFailure(error)
                    Text(stringResource(failureText(kind)), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (kind == TunnelFailureKind.OTHER) Text(error, color = MaterialTheme.colorScheme.error)
                    if (kind == TunnelFailureKind.CONFLICT) {
                        OutlinedButton(onClick = onReloadLatest, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.reload_latest))
                        }
                    }
                }
                if (unknown) WarningCard(stringResource(R.string.unknown_type_warning))
                when {
                    showingResult -> ResultStep(reported, waitingForServer, reportUnconfirmed, devices, onCopyAddress)
                    step == WizardStep.DEVICE -> DeviceStep(edit, devices, capabilities, busy, unknown, onEdit)
                    step == WizardStep.TARGET -> TargetStep(edit, busy, unknown, onEdit)
                    step == WizardStep.ACCESS -> AccessStep(edit, template?.raw == true, busy, unknown, onEdit)
                    else -> ReviewStep(edit, devices, template, onDelete, busy, unknown, onEdit)
                }
            }
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.discard_title)) },
                text = { Text(stringResource(R.string.discard_detail)) },
                confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.discard)) } },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.keep_editing)) } },
            )
        }
    }
}

@Composable
private fun DeviceStep(
    edit: ConnectionEdit,
    devices: List<ManagedDevice>,
    capabilities: ConnectionCapabilities,
    busy: Boolean,
    unknown: Boolean,
    onEdit: (ConnectionEdit) -> Unit,
) {
    if (edit.isNew) {
        Text(stringResource(R.string.choose_device), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.wizard_template), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TUNNEL_TEMPLATES.forEach { template ->
                val allowed = templatePermitted(template, capabilities)
                FilterChip(
                    selected = edit.fields.templateId == template.id.name,
                    enabled = !busy && !unknown && allowed,
                    onClick = { onEdit(edit.copy(fields = withTemplate(edit.fields, template))) },
                    label = { Text(stringResource(templateLabel(template.id))) },
                )
            }
        }
        if (TUNNEL_TEMPLATES.any { !templatePermitted(it, capabilities) }) {
            Text(stringResource(R.string.wizard_template_denied), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionLabel(stringResource(R.string.editor_identity))
    val choices = if (edit.isNew) devices.filter { it.status == "active" } else devices.filter { it.id == edit.fields.deviceId }
    choices.forEach { device ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                selected = edit.fields.deviceId == device.id,
                onClick = { if (edit.isNew) onEdit(edit.copy(fields = edit.fields.copy(deviceId = device.id))) },
                enabled = !busy && edit.isNew,
                role = Role.RadioButton,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = edit.fields.deviceId == device.id, onClick = null, enabled = !busy && edit.isNew)
            Spacer(Modifier.width(12.dp))
            Text(device.name, modifier = Modifier.weight(1f))
            Text(stringResource(if (device.online && device.status == "active") R.string.status_online else R.string.status_offline), style = MaterialTheme.typography.labelSmall)
        }
    }
    OutlinedTextField(
        value = edit.fields.name,
        onValueChange = { onEdit(edit.copy(fields = edit.fields.copy(name = it))) },
        modifier = Modifier.visibleEditorField(),
        enabled = !busy && !unknown,
        label = { Text(stringResource(R.string.connection_name)) },
        singleLine = true,
    )
}

@Composable
private fun TargetStep(edit: ConnectionEdit, busy: Boolean, unknown: Boolean, onEdit: (ConnectionEdit) -> Unit) {
    SectionLabel(stringResource(R.string.editor_destination))
    Text(stringResource(R.string.target_device_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = edit.fields.host,
        onValueChange = { onEdit(edit.copy(fields = edit.fields.copy(host = it))) },
        modifier = Modifier.visibleEditorField(),
        enabled = !busy && !unknown,
        label = { Text(stringResource(R.string.local_host)) },
        singleLine = true,
    )
    val port = edit.fields.port.toIntOrNull()
    OutlinedTextField(
        value = edit.fields.port,
        onValueChange = { onEdit(edit.copy(fields = edit.fields.copy(port = it.filter(Char::isDigit).take(5)))) },
        modifier = Modifier.visibleEditorField(),
        enabled = !busy && !unknown,
        label = { Text(stringResource(R.string.local_port)) },
        isError = edit.fields.port.isNotEmpty() && port !in 1..65535,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun AccessStep(
    edit: ConnectionEdit,
    raw: Boolean,
    busy: Boolean,
    unknown: Boolean,
    onEdit: (ConnectionEdit) -> Unit,
) {
    SectionLabel(stringResource(R.string.wizard_review_access))
    if (raw) {
        WarningCard(stringResource(R.string.wizard_port_server))
        val assigned = assignedPort(edit.value.remotePort)
        Text(
            if (assigned == null) stringResource(R.string.wizard_port_pending)
            else stringResource(R.string.wizard_port_assigned, assigned),
        )
    } else {
        Text(stringResource(R.string.wizard_scheme), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("http", "https").forEach { scheme ->
                AssistChip(
                    onClick = { if (!busy && !unknown) onEdit(edit.copy(fields = edit.fields.copy(scheme = scheme))) },
                    label = { Text(scheme) },
                    leadingIcon = {
                        if (edit.fields.scheme == scheme) {
                            Icon(painterResource(R.drawable.ic_action_shield), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
        val valid = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$").matches(edit.fields.subdomain)
        OutlinedTextField(
            value = edit.fields.subdomain,
            onValueChange = { onEdit(edit.copy(fields = edit.fields.copy(subdomain = it.lowercase()))) },
            modifier = Modifier.visibleEditorField(),
            enabled = !busy && !unknown,
            label = { Text(stringResource(R.string.public_subdomain)) },
            isError = edit.fields.subdomain.isNotEmpty() && !valid,
            singleLine = true,
        )
    }
}

@Composable
private fun diagnosticText(diagnostic: TunnelDiagnostic?): String = when (diagnosticFailureKey(diagnostic)) {
    null -> stringResource(R.string.wizard_result_diagnostic)
    "none" -> stringResource(R.string.wizard_diagnostic_none)
    "dns" -> stringResource(R.string.wizard_diagnostic_dns)
    "tls" -> stringResource(R.string.wizard_diagnostic_tls)
    "target_unreachable" -> stringResource(R.string.wizard_diagnostic_target_unreachable)
    "permission" -> stringResource(R.string.wizard_diagnostic_permission)
    "sync" -> stringResource(R.string.wizard_diagnostic_sync)
    "port_unavailable" -> stringResource(R.string.wizard_diagnostic_port_unavailable)
    "udp_unreachable" -> stringResource(R.string.wizard_diagnostic_udp_unreachable)
    else -> stringResource(R.string.wizard_diagnostic_unknown)
}

@Composable
private fun ResultStep(
    reported: TunnelConnection?,
    waiting: Boolean,
    unconfirmed: Boolean,
    devices: List<ManagedDevice>,
    onCopy: (String) -> Unit,
) {
    when {
        waiting -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.wizard_result_waiting), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        reported == null -> Text(stringResource(if (unconfirmed) R.string.wizard_result_unconfirmed else R.string.wizard_result_waiting))
        else -> {
            val address = reported.publicDisplayEndpoint
            Text(stringResource(R.string.wizard_result_state, reported.state), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            reported.lastErrorCode?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.wizard_result_error, it), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.wizard_result_address), fontWeight = FontWeight.Bold)
            if (address.isBlank()) Text(stringResource(R.string.wizard_result_no_address))
            else {
                Text(address)
                OutlinedButton(onClick = { onCopy(address) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_action_copy), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_address))
                }
            }
            val device = devices.find { it.id == reported.deviceId }
            if (device != null && (!device.online || device.status != "active")) WarningCard(stringResource(R.string.wizard_device_offline))
            Text(stringResource(R.string.wizard_result_not_health))
            Text(diagnosticText(reported.diagnostic), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewStep(
    edit: ConnectionEdit,
    devices: List<ManagedDevice>,
    template: io.github.zhanry.hometunnel.ui.tunnel.TunnelTemplate?,
    onDelete: (() -> Unit)?,
    busy: Boolean,
    unknown: Boolean,
    onEdit: (ConnectionEdit) -> Unit,
) {
    val device = devices.find { it.id == edit.fields.deviceId }
    Text(device?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
    if (device != null && (!device.online || device.status != "active")) {
        WarningCard(stringResource(R.string.wizard_device_offline))
    }
    Text(stringResource(templateLabel(template?.id ?: TunnelTemplateId.HTTP)))
    Text(stringResource(R.string.wizard_review_local))
    Text("${edit.fields.host}:${edit.fields.port}")
    Text(stringResource(R.string.wizard_review_access))
    val assigned = assignedPort(edit.value.remotePort)
    Text(
        if (template?.raw == true) {
            if (assigned == null) stringResource(R.string.wizard_port_pending) else stringResource(R.string.wizard_port_assigned, assigned)
        } else {
            edit.fields.subdomain
        },
    )
    if (!edit.isNew) Text(edit.value.state)
    Text(stringResource(R.string.wizard_review_health), color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
        Checkbox(
            checked = edit.fields.enabled,
            onCheckedChange = { onEdit(edit.copy(fields = edit.fields.copy(enabled = it))) },
            enabled = !busy && !unknown,
        )
        Text(stringResource(R.string.enabled))
    }
    onDelete?.let {
        OutlinedButton(onClick = it, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_action_delete), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.delete_connection))
        }
    }
}

private fun stepTitle(step: WizardStep): Int = when (step) {
    WizardStep.DEVICE -> R.string.wizard_step_device
    WizardStep.TARGET -> R.string.wizard_step_target
    WizardStep.ACCESS -> R.string.wizard_step_access
    WizardStep.REVIEW -> R.string.wizard_step_review
}

internal fun templateLabel(id: TunnelTemplateId): Int = when (id) {
    TunnelTemplateId.HTTP -> R.string.template_http
    TunnelTemplateId.HTTPS -> R.string.template_https
    TunnelTemplateId.TCP -> R.string.template_tcp
    TunnelTemplateId.UDP -> R.string.template_udp
    TunnelTemplateId.NAS -> R.string.template_nas
    TunnelTemplateId.HOME_ASSISTANT -> R.string.template_home_assistant
    TunnelTemplateId.IMMICH -> R.string.template_immich
    TunnelTemplateId.JELLYFIN -> R.string.template_jellyfin
    TunnelTemplateId.SSH -> R.string.template_ssh
    TunnelTemplateId.RDP -> R.string.template_rdp
    TunnelTemplateId.RTSP -> R.string.template_rtsp
}

private fun failureText(kind: TunnelFailureKind): Int = when (kind) {
    TunnelFailureKind.PERMISSION -> R.string.wizard_error_permission
    TunnelFailureKind.PORT -> R.string.wizard_error_port
    TunnelFailureKind.SYNC -> R.string.wizard_error_sync
    TunnelFailureKind.OFFLINE -> R.string.wizard_error_offline
    TunnelFailureKind.CONFLICT -> R.string.wizard_error_conflict
    TunnelFailureKind.OTHER -> R.string.status_error
}
