package com.vivopulse.app.util

import android.content.Context
import android.content.Intent
import android.os.Process
import com.vivopulse.app.ui.ErrorDisplayActivity
import com.vivopulse.signal.AppLogger
import kotlin.system.exitProcess

/**
 * Global crash handler to catch uncaught exceptions and display a user-friendly error screen.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        // Log critical error
        AppLogger.error("CrashHandler", "Uncaught exception in thread ${t.name}", e)
        
        try {
            // Launch Error Display Activity
            val intent = Intent(context, ErrorDisplayActivity::class.java).apply {
                putExtra("error", e.message)
                putExtra("stacktrace", android.util.Log.getStackTraceString(e))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
            
            // Kill current process
            Process.killProcess(Process.myPid())
            exitProcess(10)
            
        } catch (error: Exception) {
            // If failed to handle, fall back to default
            AppLogger.error("CrashHandler", "Failed to launch error activity", error)
            defaultHandler?.uncaughtException(t, e)
        }
    }
    
    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }
    }
}
