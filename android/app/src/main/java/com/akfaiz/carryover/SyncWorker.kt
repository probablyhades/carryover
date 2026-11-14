package com.akfaiz.carryover

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Syncs in the background: everything on a schedule (picks up saves another
 * device uploaded, and anything the watcher missed), or one profile on demand
 * (a conflict answered from a notification).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val key = inputData.getString(KEY_PROFILE)
        val resolve = inputData.getString(KEY_RESOLVE)
        val args = buildList {
            add("sync")
            if (key == null) add("--all") else add(key)
            if (resolve != null) addAll(listOf("--resolve", resolve))
        }
        try {
            val answer = Agent.runBlocking(applicationContext, *args.toTypedArray())
            Notifier.report(applicationContext, SyncResult.parseAll(answer))
            if (key == null) Prefs(applicationContext).lastBackgroundSync = System.currentTimeMillis()
            Result.success()
        } catch (e: AgentException) {
            // "no cloud remote chosen" and the like: nothing to retry until the user acts.
            Log.w(TAG, "sync failed: ${e.message}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CarryoverSync"
        private const val KEY_PROFILE = "profile"
        private const val KEY_RESOLVE = "resolve"
        private const val PERIODIC = "periodic-sync"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** (Re)schedules the periodic sync with the interval from the preferences. */
        fun schedule(ctx: Context) {
            val minutes = Prefs(ctx).intervalMinutes.coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Syncs one profile ([key]) or everything (null) now, in the background. */
        fun syncNow(ctx: Context, key: String? = null, resolve: String? = null) {
            val data = workDataOf(KEY_PROFILE to key, KEY_RESOLVE to resolve)
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now:${key ?: "all"}", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
