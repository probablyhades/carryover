package com.akfaiz.carryover

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val info: Info? = null,
    val games: List<Game> = emptyList(),
    val loading: Boolean = false,
    /** What is running now ("Syncing…"), or null. */
    val busy: String? = null,
    /** A one-off message for the snackbar. */
    val message: String? = null,
    /** Set when the engine could not start or the cloud library could not be read. */
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh(offline = true)
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    /** Runs [block] with a busy indicator; errors become a snackbar message. */
    private fun task(busy: String, block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = busy) }
        try {
            block()
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        } finally {
            _state.update { it.copy(busy = null) }
        }
    }

    /** Reloads the device info and the games (merging the cloud library unless [offline]). */
    fun refresh(offline: Boolean = false) = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        try {
            var info = Info.parse(Agent.run(ctx, "info"))
            val prefs = Prefs(ctx)
            if (!prefs.deviceNamed) {
                // The engine names the device after its host name, "localhost" on Android.
                if (info.deviceName in setOf("", "localhost", "device")) {
                    info = Info.parse(Agent.run(ctx, "configure", "--device-name", defaultDeviceName()))
                }
                prefs.deviceNamed = true
            }
            prefs.cloudReady = info.cloudConfigured
            val args = if (offline || !info.cloudConfigured) arrayOf("games", "--offline") else arrayOf("games")
            val answer = Agent.run(ctx, *args)
            val games = answer.optJSONArray("games").objects().map(Game::parse)
            _state.update {
                it.copy(info = info, games = games, error = answer.optString("libraryError").ifEmpty { null })
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private fun defaultDeviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    fun configure(deviceName: String? = null, remote: String? = null, folder: String? = null) =
        task(str(R.string.busy_saving)) {
            val args = buildList {
                add("configure")
                deviceName?.let { addAll(listOf("--device-name", it)) }
                remote?.let { addAll(listOf("--remote", it)) }
                folder?.let { addAll(listOf("--folder", it)) }
            }
            val info = Info.parse(Agent.run(ctx, *args.toTypedArray()))
            _state.update { it.copy(info = info) }
            Prefs(ctx).cloudReady = info.cloudConfigured
            if (remote != null || folder != null) {
                onCloudChanged()
            }
        }

    /** Copies the chosen rclone.conf to the cache and imports its remotes. */
    fun importRclone(uri: Uri) = task(str(R.string.busy_importing)) {
        val tmp = File(ctx.cacheDir, "import-rclone.conf")
        try {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw AgentException(str(R.string.error_read_file))
            }
            val answer = Agent.run(ctx, "import-rclone", tmp.absolutePath)
            val imported = answer.optJSONArray("imported").strings()
            val skipped = answer.optJSONArray("skipped").strings()
            say(
                when {
                    imported.isEmpty() -> str(R.string.import_none, skipped.joinToString())
                    skipped.isEmpty() -> str(R.string.import_done, imported.joinToString())
                    else -> str(R.string.import_some, imported.joinToString(), skipped.joinToString())
                },
            )
        } finally {
            // The file holds the cloud tokens: don't leave a copy behind.
            withContext(Dispatchers.IO) { tmp.delete() }
        }
        val info = Info.parse(Agent.run(ctx, "info"))
        _state.update { it.copy(info = info) }
        Prefs(ctx).cloudReady = info.cloudConfigured
        onCloudChanged()
    }

    private fun onCloudChanged() {
        WatchService.apply(ctx)
        WatchService.restart(ctx)
        refresh()
    }

    fun testConnection() = task(str(R.string.busy_testing)) {
        Agent.run(ctx, "test")
        say(str(R.string.test_ok))
    }

    fun setPath(game: Game, profile: Profile, slot: Slot, path: String) = task(str(R.string.busy_saving)) {
        val args = mutableListOf("set-path", "--game", game.id, "--slot", slot.key, "--path", path)
        if (profile.emulatorId.isNotEmpty()) args += listOf("--emulator", profile.emulatorId)
        val answer = Agent.run(ctx, *args.toTypedArray())
        val updated = answer.optJSONObject("game")?.let(Game::parse)
        if (updated != null) {
            _state.update { s -> s.copy(games = s.games.map { if (it.id == updated.id) updated else it }) }
        }
        answer.optString("publishError").takeIf { it.isNotEmpty() }?.let { say(str(R.string.publish_failed, it)) }
        WatchService.restart(ctx)
    }

    /** Syncs one profile ([key]) or every ready one, now, showing the outcome. */
    fun sync(key: String? = null, resolve: String? = null) = task(str(R.string.busy_syncing)) {
        val args = buildList {
            add("sync")
            add(key ?: "--all")
            resolve?.let { addAll(listOf("--resolve", it)) }
        }
        val answer = Agent.run(ctx, *args.toTypedArray())
        val results = SyncResult.parseAll(answer)
        results.forEach { r ->
            // Settle earlier conflict/error notices.
            if (r.action in setOf("push", "pull", "none")) {
                Notifier.cancel(ctx, r.key)
                Prefs(ctx).setLastProblem(r.key, "")
            }
        }
        say(describe(results))
        refresh(offline = true)
    }

    private fun describe(results: List<SyncResult>): String {
        if (results.isEmpty()) return str(R.string.sync_nothing_ready)
        if (results.size == 1) {
            val r = results[0]
            return when (r.action) {
                "push", "pull" -> "${r.name}: ${r.summary}"
                "none" -> str(R.string.sync_up_to_date, r.name)
                "conflict" -> str(R.string.notify_conflict, r.name)
                "skipped" -> "${r.name}: ${r.reason}"
                else -> "${r.name}: ${r.error}"
            }
        }
        val moved = results.count { it.action == "push" || it.action == "pull" }
        val problems = results.count { it.action == "conflict" || it.action == "error" }
        return if (problems == 0) str(R.string.sync_all_done, moved) else str(R.string.sync_all_problems, moved, problems)
    }
}
