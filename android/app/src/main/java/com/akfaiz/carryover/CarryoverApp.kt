package com.akfaiz.carryover

import android.app.Application

class CarryoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        SyncWorker.schedule(this)
    }
}
