package com.vivopulse.signal

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * File-based logger for remote issue investigation.
 * Writes logs to Documents/vivopulse/ for easy access via file managers.
 * Fallback to app-specific external storage if Documents is unavailable.
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val LOG_DIR_NAME = "vivopulse"
    private const val MAX_LOG_FILES = 10
    
    private var logFile: File? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var isInitialized = false
    private var appVersion: String = "unknown"

    /**
     * Initialize the logger with optional app version.
     * Creates log file at Documents/vivopulse/<version>_<datetime>.log
     * 
     * @param context Application context
     * @param version App version string (e.g., "1.0.0")
     */
    fun init(context: Context, version: String = "unknown") {
        if (isInitialized) return
        
        appVersion = version
        
        // Log to logcat immediately so we know init was called
        Log.i(TAG, "AppLogger.init() called with version=$version")
        
        try {
            // Try app-specific storage FIRST (no permissions needed, always works)
            var logDir: File? = null
            var logLocation = "unknown"
            
            // Option 1: App-specific external storage (visible in Android/data/com.vivopulse.app/files/logs/)
            val appExternalDir = context.getExternalFilesDir(null)
            if (appExternalDir != null) {
                logDir = File(appExternalDir, "logs")
                logLocation = "app-external"
            }
            
            // Option 2: Fallback to internal storage (not visible to user, but always works)
            if (logDir == null || !ensureDirectoryExists(logDir)) {
                logDir = File(context.filesDir, "logs")
                logLocation = "app-internal"
            }
            
            if (!ensureDirectoryExists(logDir)) {
                Log.e(TAG, "FATAL: Failed to create any log directory")
                return
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${appVersion}_$timestamp.log"
            logFile = File(logDir, filename)
            
            // Try to write a test line immediately
            try {
                logFile?.writeText("Log file created at ${dateFormat.format(Date())}\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write initial log line", e)
                return
            }
            
            cleanOldLogs(logDir)
            
            isInitialized = true
            
            // Log startup info
            log(TAG, "=".repeat(60))
            log(TAG, "VivoPulse Logger initialized ($logLocation)")
            log(TAG, "Log file: ${logFile?.absolutePath}")
            log(TAG, "App version: $appVersion")
            log(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            log(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            log(TAG, "Time: ${dateFormat.format(Date())}")
            log(TAG, "=".repeat(60))
            
            Log.i(TAG, "AppLogger initialized successfully at: ${logFile?.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize logger", e)
        }
    }
    
    /**
     * Get Documents/vivopulse/ directory path.
     */
    private fun getDocumentsLogDir(): File? {
        return try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
            File(documentsDir, LOG_DIR_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access Documents directory", e)
            null
        }
    }
    
    /**
     * Ensure directory exists, creating it if necessary.
     */
    private fun ensureDirectoryExists(dir: File): Boolean {
        return try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.canWrite()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create directory: ${dir.absolutePath}", e)
            false
        }
    }

    /**
     * Log an info-level message.
     */
    fun log(tag: String, message: String) {
        writeLog("INFO", tag, message)
        Log.d(tag, message)
    }
    
    /**
     * Log a warning-level message.
     */
    fun warn(tag: String, message: String) {
        writeLog("WARN", tag, message)
        Log.w(tag, message)
    }

    /**
     * Log an error-level message with optional exception.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val stackTrace = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        writeLog("ERROR", tag, "$message$stackTrace")
        Log.e(tag, message, throwable)
    }
    
    /**
     * Write a log entry to file.
     */
    private fun writeLog(level: String, tag: String, message: String) {
        if (!isInitialized || logFile == null) return
        
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp [$level] [$tag] $message\n"
        
        executor.execute {
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.append(logLine)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }
    
    /**
     * Get the current log file path.
     */
    fun getLogFilePath(): String? = logFile?.absolutePath
    
    /**
     * Get the log file content.
     */
    fun getLogContent(): String = try {
        logFile?.readText() ?: "Log file not available."
    } catch (e: Exception) {
        "Error reading log: ${e.message}"
    }

    /**
     * Clean old log files, keeping only the most recent ones.
     */
    private fun cleanOldLogs(logDir: File) {
        executor.execute {
            try {
                val files = logDir.listFiles { file -> 
                    file.isFile && file.name.endsWith(".log")
                }?.sortedBy { it.lastModified() } ?: return@execute
                
                if (files.size > MAX_LOG_FILES) {
                    for (i in 0 until files.size - MAX_LOG_FILES) {
                        val deleted = files[i].delete()
                        if (deleted) {
                            Log.d(TAG, "Deleted old log: ${files[i].name}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean old logs", e)
            }
        }
    }
}
