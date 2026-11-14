package com.akfaiz.carryover

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val tick = rememberResumeTick()
    val info = state.info
    val busy = state.busy != null

    val importConf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importRclone(uri)
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // This device
        SectionTitle(stringResource(R.string.section_device))
        var name by remember(info?.deviceName) { mutableStateOf(info?.deviceName.orEmpty()) }
        OutlinedTextField(
            name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.device_name)) },
            supportingText = { Text(stringResource(R.string.device_name_hint)) },
        )
        Button(onClick = { vm.configure(deviceName = name.trim()) }, enabled = !busy && name.isNotBlank() && name.trim() != info?.deviceName) {
            Text(stringResource(R.string.save))
        }

        // Cloud storage
        SectionTitle(stringResource(R.string.section_cloud))
        Text(stringResource(R.string.cloud_explain), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { importConf.launch(arrayOf("*/*")) }, enabled = !busy) {
            Text(stringResource(R.string.import_rclone))
        }
        val remotes = info?.remotes.orEmpty()
        var remote by remember(info?.remote) { mutableStateOf(info?.remote.orEmpty()) }
        var folder by remember(info?.folder) { mutableStateOf(info?.folder.orEmpty()) }
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && remotes.isNotEmpty() }) {
            OutlinedTextField(
                value = remote.ifEmpty { stringResource(if (remotes.isEmpty()) R.string.no_remotes else R.string.choose_remote) },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.remote)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                remotes.forEach { r ->
                    DropdownMenuItem(
                        text = { Text("${r.name} (${r.type})") },
                        onClick = {
                            remote = r.name
                            open = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            folder, { folder = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.folder)) },
            supportingText = { Text(stringResource(R.string.folder_hint)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.configure(remote = remote, folder = folder.trim()) },
                enabled = !busy && remote.isNotEmpty() && (remote != info?.remote || folder.trim() != info.folder),
            ) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { vm.testConnection() }, enabled = !busy && info?.cloudConfigured == true) {
                Text(stringResource(R.string.test_connection))
            }
        }

        // Permissions
        SectionTitle(stringResource(R.string.section_permissions))
        val hasStorage = remember(tick) { Storage.hasAccess(ctx) }
        PermissionRow(stringResource(R.string.perm_storage), stringResource(R.string.perm_storage_text), hasStorage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.startActivity(Storage.accessIntent(ctx))
            } else {
                askLegacyStorage.launch(Storage.legacyPermissions)
            }
        }
        val canNotify = remember(tick) { Notifier.canNotify(ctx) }
        PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_text), canNotify) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val battery = remember(tick) { Storage.ignoresBatteryOptimizations(ctx) }
        PermissionRow(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_text), battery) {
            ctx.startActivity(Storage.batteryIntent(ctx))
        }

        // Background sync
        SectionTitle(stringResource(R.string.section_background))
        var watch by remember { mutableStateOf(prefs.watch) }
        SwitchRow(stringResource(R.string.watch_switch), stringResource(R.string.watch_switch_text), watch) {
            watch = it
            prefs.watch = it
            WatchService.apply(ctx)
        }
        var notify by remember { mutableStateOf(prefs.notifySuccess) }
        SwitchRow(stringResource(R.string.notify_switch), stringResource(R.string.notify_switch_text), notify) {
            notify = it
            prefs.notifySuccess = it
        }
        Text(stringResource(R.string.interval), style = MaterialTheme.typography.bodyLarge)
        var interval by remember { mutableIntStateOf(prefs.intervalMinutes) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60, 180).forEach { m ->
                FilterChip(
                    selected = interval == m,
                    onClick = {
                        interval = m
                        prefs.intervalMinutes = m
                        SyncWorker.schedule(ctx)
                        WatchService.restart(ctx)
                    },
                    label = { Text(if (m < 60) stringResource(R.string.minutes, m) else stringResource(R.string.hours, m / 60)) },
                )
            }
        }
        val last = remember(tick, state.busy) { prefs.lastBackgroundSync }
        Text(
            if (last == 0L) {
                stringResource(R.string.last_background_never)
            } else {
                stringResource(R.string.last_background, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)))
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // About
        SectionTitle(stringResource(R.string.section_about))
        Text(stringResource(R.string.version, info?.version ?: "?"), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.log_location, Agent.logFile(ctx).absolutePath),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(title: String, text: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text(stringResource(R.string.granted), color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = onGrant) { Text(stringResource(R.string.allow)) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
