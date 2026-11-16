package com.akfaiz.carryover

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarryoverApp(vm: MainViewModel, openProfile: MutableState<String?>) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val key = openProfile.value
    val selected = key?.let { k ->
        state.games.firstNotNullOfOrNull { g -> g.profiles.find { it.key == k }?.let { g to it } }
    }
    if (selected != null) {
        BackHandler { openProfile.value = null }
        ProfileScreen(selected.first, selected.second, state, vm, snackbar) { openProfile.value = null }
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(if (tab == 0) R.string.app_name else R.string.tab_settings)) },
                    actions = {
                        if (tab == 0) {
                            IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.refresh))
                            }
                        }
                    },
                )
                if (state.loading || state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                NavItem(tab == 0, Icons.Filled.SportsEsports, stringResource(R.string.tab_games)) { tab = 0 }
                NavItem(tab == 1, Icons.Filled.Settings, stringResource(R.string.tab_settings)) { tab = 1 }
            }
        },
        floatingActionButton = {
            if (tab == 0 && state.info?.cloudConfigured == true) {
                ExtendedFloatingActionButton(
                    onClick = { if (state.busy == null) vm.sync() },
                    icon = { Icon(Icons.Filled.Sync, null) },
                    text = { Text(stringResource(R.string.sync_all)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (tab) {
            0 -> GamesScreen(state, padding, onOpenSettings = { tab = 1 }) { openProfile.value = it }
            else -> SettingsScreen(state, vm, padding)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

/** Re-reads permission state each time the app comes back (e.g. from Android's settings). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    return tick
}

@Composable
private fun GamesScreen(state: UiState, padding: PaddingValues, onOpenSettings: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val hasStorage = remember(tick) { Storage.hasAccess(ctx) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val info = state.info
        if (info != null && !info.cloudConfigured) {
            item {
                Banner(stringResource(R.string.banner_cloud_title), stringResource(R.string.banner_cloud_text), stringResource(R.string.open_settings), onOpenSettings)
            }
        }
        if (!hasStorage) {
            item {
                Banner(stringResource(R.string.banner_storage_title), stringResource(R.string.banner_storage_text), stringResource(R.string.open_settings), onOpenSettings)
            }
        }
        state.error?.let { err ->
            item { Banner(stringResource(R.string.banner_error_title), err, null, null) }
        }
        if (state.games.isEmpty() && !state.loading) {
            item {
                Text(
                    stringResource(R.string.no_games),
                    Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Emulated games are what a phone syncs; PC games come after, in case one has an Android version.
        val (emulated, other) = state.games.filter { it.profiles.isNotEmpty() }
            .partition { g -> g.profiles.any { it.emulatorId.isNotEmpty() } }
        items(emulated, key = { it.id }) { game -> GameCard(game, onOpen) }
        if (other.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.other_games)) }
            items(other, key = { it.id }) { game -> GameCard(game, onOpen) }
        }
    }
}

@Composable
private fun GameCard(game: Game, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                game.name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            game.profiles.forEach { p -> ProfileRow(p) { onOpen(p.key) } }
        }
    }
}

@Composable
fun profileTitle(p: Profile): String =
    if (p.emulatorId.isEmpty()) stringResource(R.string.profile_native) else p.emulatorName

@Composable
private fun ProfileRow(p: Profile, onClick: () -> Unit) {
    val (text, warn) = statusText(p)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(profileTitle(p), style = MaterialTheme.typography.bodyLarge)
            Text(
                text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** A profile's status line, and whether it needs attention. */
@Composable
fun statusText(p: Profile): Pair<String, Boolean> {
    val s = p.status
    return when {
        !p.ready -> stringResource(R.string.status_setup) to false
        s.conflict -> stringResource(R.string.status_conflict) to true
        s.lastError.isNotEmpty() -> stringResource(R.string.status_failed, s.lastError) to true
        s.lastSyncAt.isNotEmpty() -> {
            val ago = relativeTime(s.lastSyncAt)
            when (s.lastAction) {
                "push" -> stringResource(R.string.status_uploaded, ago)
                "pull" -> stringResource(R.string.status_downloaded, ago)
                else -> stringResource(R.string.status_checked, ago)
            } to false
        }
        else -> stringResource(R.string.status_never) to false
    }
}

fun relativeTime(iso: String): String = try {
    DateUtils.getRelativeTimeSpanString(Instant.parse(iso).toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
} catch (_: Exception) {
    iso
}

@Composable
fun Banner(title: String, text: String, action: String?, onAction: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(game: Game, profile: Profile, state: UiState, vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) } // "local" or "cloud"
    val busy = state.busy != null
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(game.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(profileTitle(profile), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val (text, warn) = statusText(profile)
                Text(text, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (profile.status.conflict) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.conflict_text))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { confirm = "local" }, enabled = !busy) { Text(stringResource(R.string.keep_phone)) }
                                OutlinedButton(onClick = { confirm = "cloud" }, enabled = !busy) { Text(stringResource(R.string.keep_cloud)) }
                            }
                        }
                    }
                }
            }
            items(profile.slots, key = { it.key }) { slot ->
                SlotCard(slot, profile, enabled = !busy) { path -> vm.setPath(game, profile, slot, path) }
            }
            if (profile.setupError.isNotEmpty() && !profile.ready) {
                item { Text(profile.setupError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Button(
                    onClick = { vm.sync(profile.key) },
                    enabled = profile.ready && !busy && !profile.status.conflict && state.info?.cloudConfigured == true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Sync, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.sync_now))
                }
            }
        }
    }
    confirm?.let { side ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (side == "local") R.string.keep_phone else R.string.keep_cloud)) },
            text = { Text(stringResource(if (side == "local") R.string.confirm_keep_phone else R.string.confirm_keep_cloud)) },
            // The safe choice comes first and is the dismiss action.
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.sync(profile.key, side)
                }) { Text(stringResource(R.string.overwrite)) }
            },
        )
    }
}

@Composable
private fun SlotCard(slot: Slot, profile: Profile, enabled: Boolean, onSet: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var badPick by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = Storage.treeUriToPath(uri)
        if (path == null) badPick = true else onSet(path)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(slot.label, style = MaterialTheme.typography.titleSmall)
            if (slot.include.isNotEmpty()) {
                Text(stringResource(R.string.slot_files, slot.include.joinToString()), style = MaterialTheme.typography.bodySmall)
            }
            slot.elsewhere.forEach { h ->
                Text(
                    stringResource(R.string.slot_elsewhere, h.device, h.path),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (slot.path.isEmpty()) {
                Text(stringResource(R.string.slot_not_set), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(slot.resolved.ifEmpty { slot.path }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            val shown = slot.resolved.ifEmpty { slot.path }
            if (slot.error.isNotEmpty()) {
                Text(slot.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (shown.isNotEmpty() && Storage.isPrivateAppFolder(shown)) {
                Text(stringResource(R.string.slot_private_folder), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pick.launch(null) }, enabled = enabled) { Text(stringResource(R.string.choose_folder)) }
                OutlinedButton(onClick = { editing = true }, enabled = enabled) { Text(stringResource(R.string.type_path)) }
                if (slot.path.isNotEmpty()) {
                    TextButton(onClick = { onSet("") }, enabled = enabled) { Text(stringResource(R.string.clear)) }
                }
            }
        }
    }
    if (editing) {
        PathDialog(slot.path, Storage.suggestions(profile.emulatorName), onDismiss = { editing = false }) {
            editing = false
            onSet(it)
        }
    }
    if (badPick) {
        AlertDialog(
            onDismissRequest = { badPick = false },
            text = { Text(stringResource(R.string.bad_folder)) },
            confirmButton = { TextButton(onClick = { badPick = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun PathDialog(initial: String, suggestions: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.type_path)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.folder)) })
                Text(stringResource(R.string.path_hint), style = MaterialTheme.typography.bodySmall)
                suggestions.forEach { s -> SuggestionChip(onClick = { text = s }, label = { Text(s) }) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.save)) } },
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SpacerSmall() = Spacer(Modifier.height(8.dp))
