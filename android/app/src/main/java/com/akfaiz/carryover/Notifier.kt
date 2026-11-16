package com.akfaiz.carryover

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Posts the notifications: a sync finished, a conflict needs an answer, a sync failed. */
object Notifier {
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_ATTENTION = "attention"
    const val CHANNEL_WATCH = "watch"
    const val WATCH_NOTIFICATION_ID = 1
    private const val GROUP_SYNC = "com.akfaiz.carryover.SYNC"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SYNC, ctx.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = ctx.getString(R.string.channel_sync_desc) },
                NotificationChannel(CHANNEL_ATTENTION, ctx.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = ctx.getString(R.string.channel_attention_desc) },
                NotificationChannel(CHANNEL_WATCH, ctx.getString(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = ctx.getString(R.string.channel_watch_desc); setShowBadge(false) },
            ),
        )
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, key: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (key != null) i.putExtra(MainActivity.EXTRA_PROFILE, key)
        return PendingIntent.getActivity(ctx, key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun idFor(key: String) = 1000 + (key.hashCode() and 0x7fffffff) % 1_000_000

    /** Reports what a sync did: success (if enabled), conflicts and new errors. */
    fun report(ctx: Context, results: List<SyncResult>) = results.forEach { report(ctx, it) }

    fun report(ctx: Context, r: SyncResult) {
        val prefs = Prefs(ctx)
        val problem = when (r.action) {
            "conflict" -> "conflict"
            "error" -> "error:${r.error}"
            else -> ""
        }
        val newProblem = problem.isNotEmpty() && problem != prefs.lastProblem(r.key)
        if (r.action in setOf("push", "pull", "none", "conflict", "error")) prefs.setLastProblem(r.key, problem)
        if (!canNotify(ctx)) return
        val nm = NotificationManagerCompat.from(ctx)
        when (r.action) {
            "push", "pull" -> {
                nm.cancel(idFor(r.key)) // a conflict or error notice is settled
                if (!prefs.notifySuccess) return
                val title = ctx.getString(if (r.action == "push") R.string.notify_uploaded else R.string.notify_downloaded, r.name)
                val text = buildString {
                    append(r.summary.ifEmpty { ctx.getString(R.string.notify_synced) })
                    if (r.action == "pull") append(" · ").append(ctx.getString(R.string.notify_reload_hint))
                }
                post(ctx, idFor(r.key), NotificationCompat.Builder(ctx, CHANNEL_SYNC)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(ctx))
                    .setGroup(GROUP_SYNC)
                    .setAutoCancel(true))
            }
            "conflict" -> if (newProblem) {
                val text = ctx.getString(R.string.notify_conflict_text)
                post(ctx, idFor(r.key), NotificationCompat.Builder(ctx, CHANNEL_ATTENTION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ctx.getString(R.string.notify_conflict, r.name))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(ctx, r.key))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .addAction(0, ctx.getString(R.string.keep_phone), ConflictReceiver.intent(ctx, r.key, "local"))
                    .addAction(0, ctx.getString(R.string.keep_cloud), ConflictReceiver.intent(ctx, r.key, "cloud"))
                    .setAutoCancel(true))
            }
            "error" -> if (newProblem) {
                post(ctx, idFor(r.key), NotificationCompat.Builder(ctx, CHANNEL_ATTENTION)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(ctx.getString(R.string.notify_failed, r.name))
                    .setContentText(r.error)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(r.error))
                    .setContentIntent(openApp(ctx, r.key))
                    .setAutoCancel(true))
            }
        }
    }

    fun cancel(ctx: Context, key: String) = NotificationManagerCompat.from(ctx).cancel(idFor(key))

    @Suppress("MissingPermission") // checked by canNotify
    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (canNotify(ctx)) NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
