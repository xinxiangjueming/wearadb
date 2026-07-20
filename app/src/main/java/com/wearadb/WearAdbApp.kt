package com.wearadb

import android.app.Application
import com.wearadb.log.WearAdbLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WearAdbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WearAdbLogger.init(this)
        MemoryReceiver.getInstance().initialize(this)
    }
}
