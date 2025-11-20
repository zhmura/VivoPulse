package com.vivopulse.feature.capture.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size

/**
 * Camera mode for dual-camera operation.
 */
enum class CameraMode {
    CONCURRENT,           // Both cameras simultaneously
    SAFE_MODE_SEQUENTIAL, // Sequential fallback
    SAFE_MODE_REDUCED     // Reduced resolution concurrent
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
    val deviceInfo: String
)

/**
 * Probes device camera capabilities for dual-site PPG.
 * 
 * Checks:
 * - Concurrent camera support (API 30+)
 * - Available camera IDs and characteristics
 * - Stream configuration support
 * - 3A capabilities
 */
class DeviceProbe(private val context: Context) {
    private val tag = "DeviceProbe"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    /**
     * Probe device capabilities and recommend camera mode.
     */
    fun probe(): DeviceCapabilities {
        val hasConcurrent = checkConcurrentSupport()
        val concurrentIds = getConcurrentCameraIds()
        
        // Find front and back cameras
        val (frontId, backId) = findFrontAndBackCameras()
        
        // Get max resolutions
        val maxFrontRes = frontId?.let { getMaxResolution(it) }
        val maxBackRes = backId?.let { getMaxResolution(it) }
        
        // Check 3A capabilities
        val (antiFlicker, aeLock, awbLock) = check3ACapabilities(frontId, backId)
        
        // Recommend mode
        val mode = recommendMode(hasConcurrent, concurrentIds, frontId, backId)
        
        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        
        Log.i(tag, "Device probe complete: $deviceInfo")
        Log.i(tag, "Concurrent support: $hasConcurrent, Recommended mode: $mode")
        
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
    private fun check3ACapabilities(frontId: String?, backId: String?): Triple<Boolean, Boolean, Boolean> {
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
     * Recommend camera mode based on capabilities.
     */
    private fun recommendMode(
        hasConcurrent: Boolean,
        concurrentIds: Set<Set<String>>,
        frontId: String?,
        backId: String?
    ): CameraMode {
        // If no concurrent support, use sequential
        if (!hasConcurrent || concurrentIds.isEmpty()) {
            Log.i(tag, "Recommending SAFE_MODE_SEQUENTIAL (no concurrent support)")
            return CameraMode.SAFE_MODE_SEQUENTIAL
        }
        
        // Check if our front/back pair is in concurrent sets
        if (frontId != null && backId != null) {
            val hasPair = concurrentIds.any { set ->
                set.contains(frontId) && set.contains(backId)
            }
            
            if (hasPair) {
                Log.i(tag, "Recommending CONCURRENT (front+back supported)")
                return CameraMode.CONCURRENT
            }
        }
        
        // Fallback to sequential
        Log.i(tag, "Recommending SAFE_MODE_SEQUENTIAL (front+back not in concurrent sets)")
        return CameraMode.SAFE_MODE_SEQUENTIAL
    }
}
