package com.wearadb

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WearAdbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MemoryReceiver.getInstance().initialize(this)
    }
}
