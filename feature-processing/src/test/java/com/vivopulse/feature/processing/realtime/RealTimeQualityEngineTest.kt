package com.vivopulse.feature.processing.realtime

import com.vivopulse.signal.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeQualityEngineTest {

    @Test
    fun addSample_emitsStatePeriodically() {
        val engine = RealTimeQualityEngine(
            bufferSeconds = 5.0,
            windowSeconds = 2.0,
            updateIntervalMs = 100
        )
        
        // Add samples at 60fps to ensure > 60 samples in 2s window
        val startTs = 1000000000L // 1s in ns
        var state: RealTimeQualityState? = null
        
        for (i in 0..200) {
            val ts = startTs + i * 16666666L // 60fps
            val sample = SignalSample(
                timestampNs = ts,
                faceMeanLuma = 100.0 + 10.0 * kotlin.math.sin(i * 0.1),
                fingerMeanLuma = 100.0 + 10.0 * kotlin.math.sin(i * 0.1 + 0.5),
                faceMotionRmsPx = 0.1,
                fingerSaturationPct = 0.01,
                torchEnabled = true
            )
            
            val result = engine.addSample(sample)
            if (result != null) {
                state = result
            }
        }
        
        assertNotNull("Should emit state", state)
        assertEquals(QualityStatus.GREEN, state?.face?.status)
        assertEquals(QualityStatus.GREEN, state?.finger?.status)
    }

    @Test
    fun addSample_detectsImuMotion() {
        val engine = RealTimeQualityEngine(
            bufferSeconds = 5.0,
            windowSeconds = 2.0,
            updateIntervalMs = 100
        )
        
        val startTs = 1000000000L
        var state: RealTimeQualityState? = null
        
        for (i in 0..200) {
            val ts = startTs + i * 16666666L // 60fps
            val sample = SignalSample(
                timestampNs = ts,
                faceMeanLuma = 100.0,
                fingerMeanLuma = 100.0,
                faceMotionRmsPx = 0.1,
                fingerSaturationPct = 0.01,
                imuRmsG = 0.2, // High IMU motion (> 0.05)
                torchEnabled = true
            )
            
            val result = engine.addSample(sample)
            if (result != null) {
                state = result
            }
        }
        
        assertNotNull("Should emit state", state)
        // IMU affects both channels
        assertTrue("Face status should reflect IMU motion", state!!.face.status != QualityStatus.GREEN)
        assertTrue("Finger status should reflect IMU motion", state!!.finger.status != QualityStatus.GREEN)
        assertTrue("Diagnostics should contain motion warning", 
            state!!.face.diagnostics.any { it.contains("device motion", ignoreCase = true) })
    }
}
