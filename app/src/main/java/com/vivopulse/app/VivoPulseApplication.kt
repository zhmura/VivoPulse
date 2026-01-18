package com.vivopulse.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VivoPulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize logging and crash handling
        com.vivopulse.signal.AppLogger.init(this, BuildConfig.VERSION_NAME)
        com.vivopulse.app.util.CrashHandler.init(this)
    }
}


