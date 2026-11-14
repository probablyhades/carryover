package com.akfaiz.carryover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the watcher again after a reboot or an app update (WorkManager keeps its own schedule). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SyncWorker.schedule(ctx)
            WatchService.apply(ctx)
        }
    }
}
