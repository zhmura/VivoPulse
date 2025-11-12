package com.vivopulse.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VivoPulseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize any required libraries here
    }
}


