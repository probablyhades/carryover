package com.akfaiz.carryover

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Answers a conflict from its notification: keep this phone's saves or the cloud's. */
class ConflictReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val resolve = intent.getStringExtra(EXTRA_RESOLVE) ?: return
        Notifier.cancel(ctx, key)
        SyncWorker.syncNow(ctx, key, resolve)
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_RESOLVE = "resolve"

        fun intent(ctx: Context, key: String, resolve: String): PendingIntent {
            val i = Intent(ctx, ConflictReceiver::class.java)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_RESOLVE, resolve)
            return PendingIntent.getBroadcast(ctx, (key + resolve).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
