package com.akfaiz.carryover

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Keeps the sync engine's "watch" command running: it checks the save folders
 * every 30 seconds, uploads a change once the save stops changing, and syncs
 * everything every interval. Runs as a foreground service so Android doesn't
 * stop it; the periodic [SyncWorker] covers the time it isn't running.
 */
class WatchService : Service() {
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private var thread: Thread? = null
    private var dns = ""
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifier.WATCH_NOTIFICATION_ID, notification(getString(R.string.watch_starting)), type)
        dns = Agent.dnsServers(this)
        watchNetwork()
        thread = Thread(::loop, "carryover-watch").apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) process?.destroy() // the loop starts a new one
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        netCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        process?.destroy() // SIGTERM: the engine stops cleanly
        thread?.interrupt()
        super.onDestroy()
    }

    /** Restarts the engine when the network's DNS servers change (the engine can't ask Android). */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val now = Agent.dnsServers(this@WatchService)
                if (now.isNotEmpty() && now != dns) {
                    dns = now
                    process?.destroy()
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun loop() {
        var backoff = 5_000L
        while (!stopping) {
            val started = System.currentTimeMillis()
            try {
                val minutes = Prefs(this).intervalMinutes.coerceAtLeast(15)
                val p = Agent.start(this, "watch", "--interval", "30s", "--full-every", "${minutes}m")
                process = p
                p.outputStream.close()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach(::handle) }
                p.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "watch stopped", e)
            }
            if (stopping) break
            // Ran for a while: restart at once (e.g. after a network change); else back off.
            backoff = if (System.currentTimeMillis() - started > 60_000) 1_000 else (backoff * 2).coerceAtMost(300_000)
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun handle(line: String) {
        val ev = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        }
        when (ev.optString("type")) {
            "sync" -> ev.optJSONObject("result")?.let { Notifier.report(this, SyncResult.parse(it)) }
            "cycle" -> {
                if (ev.optBoolean("full")) Prefs(this).lastBackgroundSync = System.currentTimeMillis()
                updateNotification(getString(R.string.watch_checked, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())))
            }
            "error" -> updateNotification(getString(R.string.watch_offline))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, Notifier.CHANNEL_WATCH)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.watch_title))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private var lastText = ""

    @Suppress("MissingPermission")
    private fun updateNotification(text: String) {
        if (text == lastText || !Notifier.canNotify(this)) return
        lastText = text
        androidx.core.app.NotificationManagerCompat.from(this).notify(Notifier.WATCH_NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "CarryoverWatch"
        private const val ACTION_RESTART = "restart"

        /** Starts or stops the watcher to match the preferences. */
        fun apply(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            val prefs = Prefs(ctx)
            if (prefs.watch && prefs.cloudReady) {
                try {
                    ContextCompat.startForegroundService(ctx, i)
                } catch (e: Exception) {
                    // Not allowed from the background on Android 12+: the app
                    // starts it next time it is opened, and the periodic sync runs meanwhile.
                    Log.w(TAG, "can't start the watcher now", e)
                }
            } else {
                ctx.stopService(i)
            }
        }

        /** Restarts the engine (after settings or save folders change). */
        fun restart(ctx: Context) {
            if (!Prefs(ctx).watch || !Prefs(ctx).cloudReady) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).setAction(ACTION_RESTART))
            } catch (e: Exception) {
                Log.w(TAG, "can't restart the watcher now", e)
            }
        }
    }
}
