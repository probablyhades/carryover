package com.akfaiz.carryover

import android.content.Context
import androidx.core.content.edit

/** App preferences (the sync engine keeps its own settings in its data dir). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("carryover", Context.MODE_PRIVATE)

    /** Minutes between background syncs of everything (WorkManager's minimum is 15). */
    var intervalMinutes: Int
        get() = sp.getInt("interval", 15)
        set(v) = sp.edit { putInt("interval", v) }

    /** Watch the save folders and sync a change within about a minute. */
    var watch: Boolean
        get() = sp.getBoolean("watch", true)
        set(v) = sp.edit { putBoolean("watch", v) }

    /** Notify after each successful upload or download. */
    var notifySuccess: Boolean
        get() = sp.getBoolean("notifySuccess", true)
        set(v) = sp.edit { putBoolean("notifySuccess", v) }

    /** The last problem reported for a profile, so it is notified once, not on every sync. */
    fun lastProblem(key: String): String = sp.getString("problem:$key", "").orEmpty()

    fun setLastProblem(key: String, problem: String) = sp.edit {
        if (problem.isEmpty()) remove("problem:$key") else putString("problem:$key", problem)
    }

    /** A cloud remote is chosen (the watcher only runs then). */
    var cloudReady: Boolean
        get() = sp.getBoolean("cloudReady", false)
        set(v) = sp.edit { putBoolean("cloudReady", v) }

    /** The device name was set from the phone's model (once, on first start). */
    var deviceNamed: Boolean
        get() = sp.getBoolean("deviceNamed", false)
        set(v) = sp.edit { putBoolean("deviceNamed", v) }

    /** When background syncing last ran (epoch ms), shown in the app. */
    var lastBackgroundSync: Long
        get() = sp.getLong("lastBackgroundSync", 0)
        set(v) = sp.edit { putLong("lastBackgroundSync", v) }
}
