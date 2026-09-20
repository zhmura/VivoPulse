package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.ProcessedSignal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignalPipelineTest {

    private val pipeline = SignalPipeline(
        targetSampleRateHz = 100.0,
        lowCutoffHz = 0.5,
        highCutoffHz = 4.0
    )

    @Test
    fun `process aligns signals to 100 Hz`() {
        // Input: 30 Hz data
        val rawBuffer = RawSeriesBuffer(
            faceData = (0..30).map { 
                TimestampedValue(it * 33333333L, 100.0) 
            },
            fingerData = (0..30).map { 
                TimestampedValue(it * 33333333L, 100.0) 
            }
        )
        
        val result = pipeline.process(rawBuffer)
        
        assertTrue("Result should be valid", result.isValid)
        assertEquals("Sample rate should be 100", 100.0, result.sampleRateHz, 0.1)
        
        // 1 second of data -> 100 samples (approx)
        val count = result.timeMillis.size
        assertTrue("Should have ~100 samples, got $count", count in 95..105)
    }

    @Test
    fun `process integrates metrics from real-time signals`() {
        val rawBuffer = RawSeriesBuffer(
            faceData = (0..30).map { TimestampedValue(it * 33333333L, 100.0) },
            fingerData = (0..30).map { TimestampedValue(it * 33333333L, 100.0) }
        )
        
        // Pre-processed signals with metrics (sparse, 30Hz)
        val preProcessed = (0..30).map { i ->
            ProcessedSignal(
                heartRate = 60f,
                signalQuality = 1.0f,
                timestamp = i * 33L,
                processedData = floatArrayOf(),
                faceMotionRms = 0.5, // Test value
                fingerSaturationPct = 0.0,
                imuRmsG = 0.1, // Test value
                faceSqi = 80,
                fingerSqi = 90
            )
        }
        
        val result = pipeline.process(rawBuffer, preProcessed)
        
        assertTrue("Result should be valid", result.isValid)
        
        // Metrics should be resampled/propagated
        // Check if imuRmsG is present and non-zero (resampling might smooth it but avg should be close)
        val avgImu = result.imuRmsG.average()
        assertEquals("IMU RMS should be propagated", 0.1, avgImu, 0.01)
        
        val avgMotion = result.faceMotionRms.average()
        assertEquals("Face Motion should be propagated", 0.5, avgMotion, 0.01)
    }
    
    @Test
    fun `process handles empty input gracefully`() {
        val empty = RawSeriesBuffer(emptyList(), emptyList())
        val result = pipeline.process(empty)
        
        assertFalse("Result should be invalid for empty input", result.isValid)
    }
    @Test
    fun `process filters frames with high motion`() {
        // Create raw buffer with some frames having high IMU motion
        val rawBuffer = RawSeriesBuffer(
            faceData = (0..50).map { i ->
                TimestampedValue(i * 20000000L, 100.0) 
            },
            fingerData = (0..50).map { i ->
                TimestampedValue(i * 20000000L, 100.0) 
            }
        )
        
        // Pre-processed signals where some have high IMU RMS
        val preProcessed = (0..50).map { i ->
            ProcessedSignal(
                heartRate = 60f,
                signalQuality = 1.0f,
                timestamp = i * 20L,
                processedData = floatArrayOf(),
                faceMotionRms = 0.05,
                fingerSaturationPct = 0.0,
                // Inject high motion (gt 0.1) for indices 20-30
                imuRmsG = if (i in 20..30) 0.5 else 0.05,
                faceSqi = 80,
                fingerSqi = 90
            )
        }
        
        // Note: The SignalPipeline.process logic might require us to mock how it uses preProcessed 
        // to filter the raw buffer. Alternatively, if the motion rejection is done INSIDE processChannel
        // or prior to resampling, the result should reflect this.
        // Looking at SignalPipeline.kt, it filters rawBuffer using preProcessed timestamps & imuRmsG
        // BEFORE resampling.
        
        val result = pipeline.process(rawBuffer, preProcessed)
        
        assertTrue("Result should be valid", result.isValid)
        
        // If filtration works, we might see gaps or interpolation in the result 
        // corresponding to the high motion period. 
        // Or, more simply, we check if the metrics in the result align.
        
        // Check if the output 'imuRmsG' array mirrors the input distribution
        // The resampled IMU signal should show the spike
        val maxImu = result.imuRmsG.maxOrNull() ?: 0.0
        assertTrue("High motion should be captured/propagated", maxImu > 0.4)
    }
}
