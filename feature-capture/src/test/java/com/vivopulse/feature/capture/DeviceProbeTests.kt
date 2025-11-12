package com.vivopulse.feature.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivopulse.feature.capture.device.CaptureMode
import com.vivopulse.feature.capture.device.DeviceProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceProbeTests {
    
    @Test
    fun deviceProbe_returnsValidMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceProbe(context)
        
        val capabilities = probe.probeCapabilities()
        
        assertNotNull("Capabilities should not be null", capabilities)
        assertTrue("Mode should be one of valid modes", 
            capabilities.mode in listOf(
                CaptureMode.CONCURRENT,
                CaptureMode.DOWNGRADED_RES,
                CaptureMode.SEQUENTIAL,
                CaptureMode.UNSUPPORTED
            ))
        assertNotNull("Message should be provided", capabilities.message)
    }
    
    @Test
    fun deviceProbe_exportsCapabilitiesJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceProbe(context)
        
        val path = probe.exportCapabilities()
        
        // Export may fail in test environment, but shouldn't crash
        if (path != null) {
            assertTrue("Export path should exist", path.isNotEmpty())
            assertTrue("Should be JSON file", path.endsWith(".json"))
        }
    }
    
    @Test
    fun fallbackStrategy_progressesThroughLevels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val strategy = FallbackCaptureStrategy(context)
        
        // Initial state
        assertEquals(FallbackCaptureStrategy.FallbackLevel.NONE, strategy.getCurrentLevel())
        
        // First fallback
        val fallback1 = strategy.getNextFallback()
        assertNotNull("First fallback should exist", fallback1)
        assertEquals(CaptureMode.DOWNGRADED_RES, fallback1?.mode)
        
        // Second fallback
        val fallback2 = strategy.getNextFallback()
        assertNotNull("Second fallback should exist", fallback2)
        assertEquals(CaptureMode.SEQUENTIAL, fallback2?.mode)
        
        // Third fallback
        val fallback3 = strategy.getNextFallback()
        assertNull("Third fallback should be null (exhausted)", fallback3)
    }
}



