package com.vivopulse.app.util

import android.content.Context
import android.widget.Toast

/**
 * Centralized error handling for VivoPulse.
 */
object ErrorHandler {
    
    /**
     * Handle camera-related errors.
     */
    fun handleCameraError(context: Context, error: Throwable): String {
        val message = when (error) {
            is SecurityException -> 
                "Camera permission denied. Please grant camera permission in Settings."
            is IllegalStateException -> 
                "Camera initialization failed. Please restart the app."
            else -> {
                when {
                    error.message?.contains("in use", ignoreCase = true) == true ->
                        "Camera is being used by another app. Please close other camera apps."
                    error.message?.contains("disabled", ignoreCase = true) == true ->
                        "Camera is disabled by device policy."
                    error.message?.contains("disconnected", ignoreCase = true) == true ->
                        "Camera disconnected. Please restart the app."
                    else ->
                        "Camera error: ${error.message ?: "Unknown error"}"
                }
            }
        }
        
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        return message
    }
    
    /**
     * Handle storage-related errors.
     */
    fun handleStorageError(context: Context, error: Throwable): String {
        val message = when {
            error.message?.contains("No space left", ignoreCase = true) == true -> 
                "Insufficient storage space. Please free up space and try again."
            error.message?.contains("Permission denied", ignoreCase = true) == true -> 
                "Storage permission denied. Check app permissions in Settings."
            error.message?.contains("Read-only", ignoreCase = true) == true -> 
                "Storage is read-only. Cannot save files."
            else -> 
                "Storage error: ${error.message}"
        }
        
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        return message
    }
    
    /**
     * Handle processing errors.
     */
    fun handleProcessingError(context: Context, error: Throwable): String {
        val message = when {
            error is OutOfMemoryError -> 
                "Out of memory. Try recording a shorter session."
            error.message?.contains("timeout", ignoreCase = true) == true -> 
                "Processing timed out. Try with shorter recording."
            else -> 
                "Processing error: ${error.message}"
        }
        
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        return message
    }
    
    /**
     * Handle ML Kit errors.
     */
    fun handleMLKitError(context: Context, error: Throwable): String {
        val message = when {
            error.message?.contains("model not downloaded", ignoreCase = true) == true -> 
                "Face detection model not available. Check internet connection and restart app."
            error.message?.contains("timeout", ignoreCase = true) == true -> 
                "Face detection timed out. Try again."
            else -> 
                "Face detection error: ${error.message}"
        }
        
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        return message
    }
    
    /**
     * Check if device has sufficient storage.
     * 
     * @param requiredMB Required storage in megabytes
     * @return true if sufficient storage available
     */
    fun hasSufficientStorage(context: Context, requiredMB: Long = 100): Boolean {
        val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
        val freeSpace = filesDir.freeSpace / (1024 * 1024) // Convert to MB
        return freeSpace >= requiredMB
    }
    
    /**
     * Show generic error toast.
     */
    fun showError(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

