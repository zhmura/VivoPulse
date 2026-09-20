package com.vivopulse.feature.capture.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import com.vivopulse.signal.PulseLog
import com.vivopulse.signal.AppLogger

/**
 * Camera mode for dual-camera operation.
 */
enum class CameraMode {
    CONCURRENT,           // Both cameras simultaneously
    SAFE_MODE_SEQUENTIAL, // Sequential fallback
    SAFE_MODE_REDUCED,    // Reduced resolution concurrent
    SAFE_MODE_ANALYSIS_ONLY // YUV-only concurrent (no preview)
}

/**
 * Device capability report for dual-camera operation.
 */
data class DeviceCapabilities(
    val hasConcurrentSupport: Boolean,
    val concurrentCameraIds: Set<Set<String>>,
    val frontCameraId: String?,
    val backCameraId: String?,
    val maxFrontResolution: Size?,
    val maxBackResolution: Size?,
    val recommendedMode: CameraMode,
    val supportsAntiFlicker: Boolean,
    val supportsAeLock: Boolean,
    val supportsAwbLock: Boolean,
    val supportsManualFocus: Boolean,  // Gap C: back camera supports manual focus distance
    val concurrentPair: Pair<String, String>?,
    val deviceInfo: String
)

/**
 * Probes device camera capabilities for dual-site PPG.
 * 
 * **MVP Requirements Validation:**
 * - **FR-C1 (Capability Check):** Deterministically checks `PackageManager.FEATURE_CAMERA_CONCURRENT`.
 * - **NFR-C1 (Compatibility):** Filters cameras to find valid Front/Back pairs (ignoring auxiliary wide/tele lenses).
 * - **FR-C5 (3A Locking):** Probes for `CONTROL_AE_LOCK_AVAILABLE` and `CONTROL_AWB_LOCK_AVAILABLE`.
 * 
 * **Functional Goal:**
 * - Provide a single source of truth for "What can this phone do?".
 * - Drive the `CameraBindingHelper` fallback logic with precise capability flags.
 */
class DeviceProbe(private val context: Context) {
    private val tag = PulseLog.HW
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    /**
     * Probe device capabilities and recommend camera mode.
     */
    fun probe(): DeviceCapabilities {
        val hasConcurrent = checkConcurrentSupport()
        AppLogger.log(tag, "Concurrent support check: $hasConcurrent")
        
        val concurrentIds = getConcurrentCameraIds()
        AppLogger.log(tag, "Concurrent IDs found: $concurrentIds")
        
        // Find front and back cameras
        val (frontId, backId) = findFrontAndBackCameras()
        AppLogger.log(tag, "Cameras found - Front: $frontId, Back: $backId")
        
        // Get max resolutions
        val maxFrontRes = frontId?.let { getMaxResolution(it) }
        val maxBackRes = backId?.let { getMaxResolution(it) }
        
        // Check 3A capabilities
        val (antiFlicker, aeLock, awbLock) = check3ACapabilities(frontId, backId)
        
        // Gap C: Check manual focus support on back camera
        val manualFocus = checkManualFocusSupport(backId)
        
        // Recommend mode and pair
        val (mode, pair) = recommendMode(hasConcurrent, concurrentIds, frontId, backId)
        
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        
        Log.i(tag, "Device probe complete: $deviceInfo")
        Log.i(tag, "Concurrent support: $hasConcurrent, Recommended mode: $mode, ManualFocus: $manualFocus")
        if (pair != null) {
            Log.i(tag, "Concurrent pair found: ${pair.first} + ${pair.second}")
        }
        
        return DeviceCapabilities(
            hasConcurrentSupport = hasConcurrent,
            concurrentCameraIds = concurrentIds,
            frontCameraId = frontId,
            backCameraId = backId,
            maxFrontResolution = maxFrontRes,
            maxBackResolution = maxBackRes,
            recommendedMode = mode,
            supportsAntiFlicker = antiFlicker,
            supportsAeLock = aeLock,
            supportsAwbLock = awbLock,
            supportsManualFocus = manualFocus,
            concurrentPair = pair,
            deviceInfo = deviceInfo
        )
    }
    
    /**
     * Check if device supports concurrent camera operation.
     */
    private fun checkConcurrentSupport(): Boolean {
        // Check feature flag
        val hasFeature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        } else {
            false
        }
        
        if (!hasFeature) {
            Log.w(tag, "FEATURE_CAMERA_CONCURRENT not supported")
            return false
        }
        
        return true
    }
    
    /**
     * Get concurrent camera ID sets (API 30+).
     */
    private fun getConcurrentCameraIds(): Set<Set<String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptySet()
        }
        
        return try {
            val concurrentIds = cameraManager.concurrentCameraIds
            concurrentIds.map { it.toSet() }.toSet()
        } catch (e: Exception) {
            Log.e(tag, "Error getting concurrent camera IDs", e)
            emptySet()
        }
    }
    
    /**
     * Find front and back camera IDs.
     */
    private fun findFrontAndBackCameras(): Pair<String?, String?> {
        var frontId: String? = null
        var backId: String? = null
        
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        if (frontId == null) frontId = cameraId
                    }
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        if (backId == null) backId = cameraId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error finding cameras", e)
        }
        
        return Pair(frontId, backId)
    }
    
    /**
     * Get maximum resolution for a camera.
     */
    private fun getMaxResolution(cameraId: String): Size? {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Get largest YUV output size
            val sizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            sizes?.maxByOrNull { it.width * it.height }
        } catch (e: Exception) {
            Log.e(tag, "Error getting max resolution for camera $cameraId", e)
            null
        }
    }
    
    /**
     * Check 3A capabilities for both cameras.
     */
    private fun check3ACapabilities(frontId: String?, @Suppress("UNUSED_PARAMETER") backId: String?): Triple<Boolean, Boolean, Boolean> {
        var antiFlicker = false
        var aeLock = false
        var awbLock = false
        
        try {
            // Check front camera
            frontId?.let { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                
                val aeAvailable = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                aeLock = aeAvailable?.contains(CameraMetadata.CONTROL_AE_MODE_ON) == true
                
                val awbAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                awbLock = awbAvailable?.contains(CameraMetadata.CONTROL_AWB_MODE_AUTO) == true
                
                val antibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                antiFlicker = antibandingModes?.isNotEmpty() == true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking 3A capabilities", e)
        }
        
        return Triple(antiFlicker, aeLock, awbLock)
    }

    /**
     * Gap C: Check if back camera supports manual focus distance control.
     * Returns true if LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0 (fixed-focus returns 0 or null).
     */
    private fun checkManualFocusSupport(backId: String?): Boolean {
        if (backId == null) return false
        return try {
            val chars = cameraManager.getCameraCharacteristics(backId)
            val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val supported = minFocusDist != null && minFocusDist > 0.0f
            Log.i(tag, "Manual focus check (back=$backId): minFocusDist=$minFocusDist, supported=$supported")
            supported
        } catch (e: Exception) {
            Log.e(tag, "Error checking manual focus support", e)
            false
        }
    }
    
    /**
     * Recommend camera mode based on capabilities.
     * Returns Mode and specific Concurrent Pair (frontId, backId) if applicable.
     */
    private fun recommendMode(
        hasConcurrent: Boolean,
        concurrentIds: Set<Set<String>>,
        defaultFrontId: String?,
        defaultBackId: String?
    ): Pair<CameraMode, Pair<String, String>?> {
        // 0. Check for Emulator
        if (isEmulator()) {
            Log.w(tag, "Emulator detected. Forcing SAFE_MODE_SEQUENTIAL.")
            AppLogger.log(tag, "Emulator detected. Forcing SAFE_MODE_SEQUENTIAL.")
            return Pair(CameraMode.SAFE_MODE_SEQUENTIAL, null)
        }

        // 1. If no concurrent support, use sequential
        if (!hasConcurrent || concurrentIds.isEmpty()) {
            Log.i(tag, "Recommending SAFE_MODE_SEQUENTIAL (no concurrent support)")
            return Pair(CameraMode.SAFE_MODE_SEQUENTIAL, null)
        }
        
        // 2. Try to find a pair matching Default Front ("1") + Default Back ("0")
        if (defaultFrontId != null && defaultBackId != null) {
            val hasDefaultPair = concurrentIds.any { set ->
                set.contains(defaultFrontId) && set.contains(defaultBackId)
            }
            if (hasDefaultPair) {
                Log.i(tag, "Recommending CONCURRENT (default front+back supported)")
                return Pair(CameraMode.CONCURRENT, Pair(defaultFrontId, defaultBackId))
            }
        }
        
        // 3. Try to find ANY valid Front + Back pair (navigating physical IDs)
        // We iterate through all concurrent sets
        for (set in concurrentIds) {
            // Check if set has a front-facing and a back-facing camera
            var front: String? = null
            var back: String? = null
            
            for (id in set) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) front = id
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) back = id
                } catch (e: Exception) {
                    // Ignore
                }
            }
            
            if (front != null && back != null) {
                Log.i(tag, "Recommending CONCURRENT (found physical pair: $front + $back)")
                return Pair(CameraMode.CONCURRENT, Pair(front, back))
            }
        }
        
        // 4. Fallback to sequential
        Log.i(tag, "Recommending SAFE_MODE_SEQUENTIAL (no valid front+back pair found)")
        return Pair(CameraMode.SAFE_MODE_SEQUENTIAL, null)
    }
    
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }
}
