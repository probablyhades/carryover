package com.akfaiz.carryover

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** An error answer of the sync engine ({"ok":false,"error":...}). */
class AgentException(message: String) : Exception(message)

/**
 * Runs the Go sync engine (cmd/carryover-agent), shipped as libcarryover.so.
 * Every command is its own process and answers with one JSON object on
 * stdout; "watch" keeps running and prints one object per line.
 */
object Agent {
    private const val TIMEOUT_MINUTES = 30L

    /** Serializes commands that change the data dir (set-path, configure, ...). */
    private val writeLock = Any()

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libcarryover.so")

    /** The data dir: settings, library, rclone.conf, sync state and logs. */
    fun home(ctx: Context) = File(ctx.filesDir, "carryover")

    fun logFile(ctx: Context) = File(home(ctx), "logs/carryover.log")

    /** Starts the engine with [args]; the caller reads stdout and waits. */
    fun start(ctx: Context, vararg args: String): Process {
        val home = home(ctx).apply { mkdirs() }
        val pb = ProcessBuilder(listOf(binary(ctx).absolutePath) + args)
            .directory(home)
            // stderr repeats the log file (logs/carryover.log): keep only the last run.
            .redirectError(File(ctx.cacheDir, "agent-stderr.txt"))
        pb.environment().apply {
            put("CARRYOVER_HOME", home.absolutePath)
            put("HOME", ctx.filesDir.absolutePath)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("EXTERNAL_STORAGE", @Suppress("DEPRECATION") Environment.getExternalStorageDirectory().absolutePath)
            put("CARRYOVER_DNS", dnsServers(ctx))
        }
        return pb.start()
    }

    /** Runs a command and returns its answer; throws [AgentException] on {"ok":false}. */
    suspend fun run(ctx: Context, vararg args: String): JSONObject = withContext(Dispatchers.IO) {
        runBlocking(ctx, *args)
    }

    /** [run] for callers already off the main thread (workers). */
    fun runBlocking(ctx: Context, vararg args: String): JSONObject {
        val mutates = args.firstOrNull() in setOf("configure", "import-rclone", "set-path")
        return if (mutates) synchronized(writeLock) { exec(ctx, args) } else exec(ctx, args)
    }

    private fun exec(ctx: Context, args: Array<out String>): JSONObject {
        val p = start(ctx, *args)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroy()
            throw AgentException("the sync engine did not answer")
        }
        val line = out.trim().lineSequence().lastOrNull { it.isNotBlank() }
            ?: throw AgentException("the sync engine stopped without an answer (exit code ${p.exitValue()})")
        val json = try {
            JSONObject(line)
        } catch (e: Exception) {
            throw AgentException("unexpected answer from the sync engine: ${line.take(200)}")
        }
        if (!json.optBoolean("ok", false)) {
            throw AgentException(json.optString("error", "unknown error"))
        }
        return json
    }

    /** The active network's DNS servers, comma-separated (Go can't ask Android). */
    fun dnsServers(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return ""
        val net = cm.activeNetwork ?: return ""
        val lp = cm.getLinkProperties(net) ?: return ""
        val servers = lp.dnsServers.mapNotNull { it.hostAddress }.toMutableList()
        // With Private DNS, the plain servers may refuse port 53; the engine
        // then falls back to public resolvers after these.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp.isPrivateDnsActive) {
            servers += listOf("1.1.1.1", "8.8.8.8")
        }
        return servers.joinToString(",")
    }
}
