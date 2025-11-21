package com.vivopulse.feature.processing.coach

import com.vivopulse.feature.processing.coach.SmartCaptureCoach
import org.junit.Assert.*
import org.junit.Test

class CoachTipsTests {
    
    @Test
    fun `low light scenario shows lighting tip`() {
        val coach = SmartCaptureCoach()
        
        // Simulate low light: low luma variance
        val lowLumas = wave(base = 50.0, amplitude = 1.0)
        val fingerLumas = wave(base = 120.0, amplitude = 5.0)
        
        coach.update(
            faceLumas = lowLumas,
            fingerLumas = fingerLumas,
            motionMagnitude = 1.0,
            saturationPercent = 0.0,
            torchEnabled = true
        )
        
        val faceSqi = coach.faceSqi.value
        val tip = coach.topTip.value
        
        assertTrue("Face SQI should be low due to poor variance", faceSqi < 50)
        assertNotNull("Tip should be provided", tip)
        assertTrue("Tip should mention lighting", 
            tip?.contains("lighting", ignoreCase = true) == true ||
            tip?.contains("light", ignoreCase = true) == true)
    }
    
    @Test
    fun `motion scenario applies penalty and shows tip`() {
        val coach = SmartCaptureCoach()
        
        val faceLumas = wave(base = 150.0, amplitude = 20.0) // Good variance
        val fingerLumas = wave(base = 120.0, amplitude = 15.0)
        
        coach.update(
            faceLumas = faceLumas,
            fingerLumas = fingerLumas,
            motionMagnitude = 8.0, // High motion
            saturationPercent = 0.0,
            torchEnabled = true
        )
        
        val tip = coach.topTip.value
        
        assertNotNull("Tip should be provided for high motion", tip)
        assertTrue("Tip should mention steadiness", 
            tip?.contains("steady", ignoreCase = true) == true ||
            tip?.contains("Hold", ignoreCase = true) == true)
    }
    
    @Test
    fun `saturation scenario shows pressure tip`() {
        val coach = SmartCaptureCoach()
        
        val faceLumas = wave(base = 150.0, amplitude = 20.0)
        val fingerLumas = wave(base = 240.0, amplitude = 2.0) // High, saturating
        
        coach.update(
            faceLumas = faceLumas,
            fingerLumas = fingerLumas,
            motionMagnitude = 1.0,
            saturationPercent = 8.0, // >5% saturated
            torchEnabled = true
        )
        
        val tip = coach.topTip.value
        
        assertNotNull("Tip should be provided for saturation", tip)
        assertTrue("Tip should mention pressure", 
            tip?.contains("pressure", ignoreCase = true) == true)
    }
    
    @Test
    fun `good conditions enable start recording after 2 seconds`() {
        val coach = SmartCaptureCoach()
        
        val goodFaceLumas = wave(base = 150.0, amplitude = 25.0)
        val goodFingerLumas = wave(base = 120.0, amplitude = 20.0)
        
        // First update
        coach.update(
            faceLumas = goodFaceLumas,
            fingerLumas = goodFingerLumas,
            motionMagnitude = 1.0,
            saturationPercent = 1.0,
            torchEnabled = true
        )
        
        assertFalse("Should not enable immediately", coach.canStartRecording.value)
        
        // Simulate 2 seconds passing with continued good quality
        Thread.sleep(2100)
        
        coach.update(
            faceLumas = goodFaceLumas,
            fingerLumas = goodFingerLumas,
            motionMagnitude = 1.0,
            saturationPercent = 1.0,
            torchEnabled = true
        )
        
        assertTrue("Should enable after 2s of good quality", coach.canStartRecording.value)
    }

    private fun wave(base: Double, amplitude: Double, size: Int = 30, freq: Double = 0.2): List<Double> {
        return List(size) { idx ->
            base + amplitude * kotlin.math.sin(idx * freq)
        }
    }
}
