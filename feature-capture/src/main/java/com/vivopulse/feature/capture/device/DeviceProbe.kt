package com.vivopulse.feature.capture.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device fitness probe for camera capability detection.
 * 
 * Determines optimal capture mode based on hardware capabilities:
 * - CONCURRENT: Full concurrent dual camera @ high resolution
 * - DOWNGRADED_RES: Concurrent but lower resolution
 * - SEQUENTIAL: Sequential capture (face then finger)
 */
class DeviceProbe(private val context: Context) {
    
    private val tag = "DeviceProbe"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    /**
     * Probe device capabilities and determine capture mode.
     * 
     * @return DeviceCapabilities with recommended mode and details
     */
    @SuppressLint("NewApi")
    fun probeCapabilities(): DeviceCapabilities {
        try {
            // Get concurrent camera IDs (API 28+)
            val concurrentCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cameraManager.concurrentCameraIds?.toList() ?: emptyList()
            } else {
                emptyList()
            }
            
            val cameraIds = cameraManager.cameraIdList
            
            // Find front and back cameras
            val frontCamera = cameraIds.find { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            }
            
            val backCamera = cameraIds.find { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
            
            if (frontCamera == null || backCamera == null) {
                return DeviceCapabilities(
                    mode = CaptureMode.UNSUPPORTED,
                    message = "Missing front or back camera",
                    concurrentSupported = false
                )
            }
            
            // Get hardware levels
            val frontLevel = getHardwareLevel(frontCamera)
            val backLevel = getHardwareLevel(backCamera)
            
            // Get supported preview sizes
            val frontSizes = getSupportedPreviewSizes(frontCamera)
            val backSizes = getSupportedPreviewSizes(backCamera)
            
            // Check concurrent support
            val concurrentSupported = concurrentCameraIds.any { ids ->
                ids.contains(frontCamera) && ids.contains(backCamera)
            }
            
            // Determine mode
            val mode = determineCaptureMode(
                concurrentSupported = concurrentSupported,
                frontLevel = frontLevel,
                backLevel = backLevel,
                frontSizes = frontSizes,
                backSizes = backSizes
            )
            
            return DeviceCapabilities(
                mode = mode,
                frontCameraId = frontCamera,
                backCameraId = backCamera,
                frontHardwareLevel = frontLevel,
                backHardwareLevel = backLevel,
                frontPreviewSizes = frontSizes,
                backPreviewSizes = backSizes,
                concurrentSupported = concurrentSupported,
                concurrentCameraIds = concurrentCameraIds,
                message = getCapabilityMessage(mode)
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to probe capabilities", e)
            return DeviceCapabilities(
                mode = CaptureMode.SEQUENTIAL,
                message = "Probe failed, using safe mode: ${e.message}",
                concurrentSupported = false
            )
        }
    }
    
    /**
     * Get hardware support level.
     */
    private fun getHardwareLevel(cameraId: String): String {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        
        return when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * Get supported preview sizes ≥720p.
     */
    private fun getSupportedPreviewSizes(cameraId: String): List<Size> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        val sizes = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        
        // Filter for ≥720p and reasonable aspect ratios
        return sizes.filter { size ->
            size.height >= 720 && (size.width.toDouble() / size.height) in 0.5..2.0
        }.sortedByDescending { it.width * it.height }
    }
    
    /**
     * Determine optimal capture mode.
     */
    private fun determineCaptureMode(
        concurrentSupported: Boolean,
        frontLevel: String,
        backLevel: String,
        frontSizes: List<Size>,
        backSizes: List<Size>
    ): CaptureMode {
        // Check for concurrent support
        if (!concurrentSupported) {
            Log.w(tag, "Concurrent cameras not supported")
            return CaptureMode.SEQUENTIAL
        }
        
        // Check hardware levels
        if (frontLevel == "LEGACY" || backLevel == "LEGACY") {
            Log.w(tag, "LEGACY hardware level detected")
            return CaptureMode.DOWNGRADED_RES
        }
        
        // Check for adequate preview sizes
        val has720pFront = frontSizes.any { it.height >= 720 }
        val has720pBack = backSizes.any { it.height >= 720 }
        
        if (!has720pFront || !has720pBack) {
            Log.w(tag, "Insufficient preview sizes")
            return CaptureMode.DOWNGRADED_RES
        }
        
        // All checks passed
        return CaptureMode.CONCURRENT
    }
    
    /**
     * Get capability message for UI.
     */
    private fun getCapabilityMessage(mode: CaptureMode): String {
        return when (mode) {
            CaptureMode.CONCURRENT -> "✅ Concurrent cameras supported"
            CaptureMode.DOWNGRADED_RES -> "⚠️ Using reduced resolution for compatibility"
            CaptureMode.SEQUENTIAL -> "⚠️ Using sequential capture (Safe Mode)"
            CaptureMode.UNSUPPORTED -> "❌ Device not supported"
        }
    }
    
    /**
     * Export device capabilities to JSON.
     * 
     * @return JSON file path
     */
    fun exportCapabilities(): String? {
        try {
            val capabilities = probeCapabilities()
            val json = buildCapabilitiesJson(capabilities)
            
            val exportDir = File(context.getExternalFilesDir(null), "device_info")
            exportDir.mkdirs()
            
            val file = File(exportDir, "device_capabilities.json")
            file.writeText(json.toString(2))
            
            Log.d(tag, "Capabilities exported: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to export capabilities", e)
            return null
        }
    }
    
    /**
     * Build capabilities JSON.
     */
    private fun buildCapabilitiesJson(cap: DeviceCapabilities): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.SDK_INT)
                put("android_release", Build.VERSION.RELEASE)
            })
            
            put("capture_mode", cap.mode.name)
            put("concurrent_supported", cap.concurrentSupported)
            put("message", cap.message)
            
            put("cameras", JSONObject().apply {
                put("front", JSONObject().apply {
                    put("id", cap.frontCameraId ?: "")
                    put("hardware_level", cap.frontHardwareLevel ?: "")
                    put("preview_sizes", JSONArray().apply {
                        cap.frontPreviewSizes.take(5).forEach { size ->
                            put("${size.width}x${size.height}")
                        }
                    })
                })
                put("back", JSONObject().apply {
                    put("id", cap.backCameraId ?: "")
                    put("hardware_level", cap.backHardwareLevel ?: "")
                    put("preview_sizes", JSONArray().apply {
                        cap.backPreviewSizes.take(5).forEach { size ->
                            put("${size.width}x${size.height}")
                        }
                    })
                })
            })
            
            if (cap.concurrentCameraIds.isNotEmpty()) {
                put("concurrent_camera_sets", JSONArray().apply {
                    cap.concurrentCameraIds.forEach { ids ->
                        put(JSONArray(ids.toList()))
                    }
                })
            }
        }
    }
}

/**
 * Device capabilities result.
 */
data class DeviceCapabilities(
    val mode: CaptureMode,
    val frontCameraId: String? = null,
    val backCameraId: String? = null,
    val frontHardwareLevel: String? = null,
    val backHardwareLevel: String? = null,
    val frontPreviewSizes: List<Size> = emptyList(),
    val backPreviewSizes: List<Size> = emptyList(),
    val concurrentSupported: Boolean,
    val concurrentCameraIds: List<Set<String>> = emptyList(),
    val message: String
)

/**
 * Capture mode enum.
 */
enum class CaptureMode {
    CONCURRENT,        // Full concurrent dual camera
    DOWNGRADED_RES,    // Concurrent with lower resolution
    SEQUENTIAL,        // Sequential capture (safe mode)
    UNSUPPORTED        // Device cannot run app
}



