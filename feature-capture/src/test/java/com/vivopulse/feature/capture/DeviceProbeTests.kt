package com.vivopulse.feature.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivopulse.feature.capture.camera.DeviceProbe
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Tests for DeviceProbe camera capability detection.
 */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTests {
    
    @Test
    fun testDeviceProbe_returnsCapabilities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceProbe(context)
        
        val capabilities = probe.probe()
        
        // Basic assertions
        assertNotNull(capabilities)
        assertNotNull(capabilities.deviceInfo)
        assertNotNull(capabilities.recommendedMode)
        
        // Log for manual inspection
        println("Device: ${capabilities.deviceInfo}")
        println("Concurrent support: ${capabilities.hasConcurrentSupport}")
        println("Recommended mode: ${capabilities.recommendedMode}")
    }
    
    @Test
    fun testDeviceProbe_findsCameras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceProbe(context)
        
        val capabilities = probe.probe()
        
        // Most devices should have at least one camera
        assertTrue(capabilities.frontCameraId != null || capabilities.backCameraId != null)
    }
    
    @Test
    fun testDeviceProbe_checksResolutions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceProbe(context)
        
        val capabilities = probe.probe()
        
        // If cameras exist, they should have resolutions
        if (capabilities.frontCameraId != null) {
            assertNotNull(capabilities.maxFrontResolution)
        }
        if (capabilities.backCameraId != null) {
            assertNotNull(capabilities.maxBackResolution)
        }
    }
}
