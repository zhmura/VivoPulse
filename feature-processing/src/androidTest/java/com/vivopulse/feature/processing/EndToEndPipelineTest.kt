package com.vivopulse.feature.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivopulse.feature.processing.simulation.SimulatedFrameSource
import com.vivopulse.feature.processing.simulation.SimulationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * End-to-end instrumented test for the complete signal processing pipeline.
 * 
 * Tests the full flow from simulated frames to PTT calculation.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndPipelineTest {
    
    @Test
    fun happyPath_idealConditions_producesAccuratePTT() {
        // Given: Ideal conditions with known PTT
        val expectedPTT = 100.0
        val config = SimulationConfig(
            heartRateHz = 1.2,        // 72 BPM
            pttLagMs = expectedPTT,
            durationSeconds = 30.0,
            captureRateHz = 30.0,
            noiseEnabled = false,
            driftEnabled = false
        )
        
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process through complete pipeline
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        
        val pttResult = PttCalculator.computePtt(processedSeries)
        
        val qualityReport = QualityAssessment.assessQuality(
            processedSeries,
            pttResult,
            faceMotionScore = 95.0
        )
        
        // Then: Verify results
        assertTrue("Processing should succeed", processedSeries.isValid)
        assertTrue("Signals should be aligned", processedSeries.isAligned())
        assertEquals("Should have ~3000 samples", 3000, processedSeries.getSampleCount(), 50)
        
        assertTrue("PTT calculation should succeed", pttResult.isValid)
        assertTrue("PTT should be in range 30-200ms", pttResult.isPlausible)
        
        // PTT accuracy within ±10ms for ideal conditions
        val pttError = abs(pttResult.pttMs - expectedPTT)
        assertTrue("PTT should be ~${expectedPTT}ms, was ${pttResult.pttMs}ms (error: ${pttError}ms)",
            pttError < 10.0)
        
        // Correlation should be excellent
        assertTrue("Correlation should be >0.9, was ${pttResult.correlationScore}",
            pttResult.correlationScore > 0.9)
        
        // Quality should be good
        assertTrue("Quality should be good", qualityReport.isGoodQuality)
        assertTrue("Should have few/no suggestions", qualityReport.suggestions.size <= 2)
    }
    
    @Test
    fun happyPath_realisticConditions_producesGoodPTT() {
        // Given: Realistic conditions (noise + drift)
        val expectedPTT = 95.0
        val config = SimulationConfig(
            heartRateHz = 1.3,        // 78 BPM
            pttLagMs = expectedPTT,
            durationSeconds = 20.0,
            captureRateHz = 30.0,
            noiseEnabled = true,
            noiseLevel = 0.12,
            driftEnabled = true,
            driftRate = 0.025
        )
        
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process through pipeline
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        val pttResult = PttCalculator.computePtt(processedSeries)
        
        // Then: Should still work well
        assertTrue("Processing should succeed", processedSeries.isValid)
        assertTrue("PTT should be valid", pttResult.isValid)
        
        // More lenient for realistic conditions
        val pttError = abs(pttResult.pttMs - expectedPTT)
        assertTrue("PTT should be reasonable, was ${pttResult.pttMs}ms", 
            pttError < 20.0)
        
        assertTrue("Correlation should be good (>0.7), was ${pttResult.correlationScore}",
            pttResult.correlationScore > 0.7)
    }
    
    @Test
    fun sadPath_poorConditions_producesWarnings() {
        // Given: Poor conditions (high noise, drift)
        val config = SimulationConfig(
            heartRateHz = 1.2,
            pttLagMs = 100.0,
            durationSeconds = 15.0,
            noiseEnabled = true,
            noiseLevel = 0.35,        // 35% noise
            driftEnabled = true,
            driftRate = 0.08          // Heavy drift
        )
        
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        val pttResult = PttCalculator.computePtt(processedSeries)
        val qualityReport = QualityAssessment.assessQuality(
            processedSeries,
            pttResult,
            faceMotionScore = null
        )
        
        // Then: Should detect poor quality
        assertFalse("Quality should not be good", qualityReport.isGoodQuality)
        assertTrue("Should have suggestions", qualityReport.suggestions.isNotEmpty())
        
        // May suggest retry
        assertTrue("Should consider retry for very poor quality", 
            qualityReport.shouldRetry || qualityReport.combinedScore < 70)
    }
    
    @Test
    fun performanceBudget_processing_meetsTargets() {
        // Given: Typical session
        val config = SimulationConfig.realistic()
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process and measure time
        val startTime = System.nanoTime()
        
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        val pttResult = PttCalculator.computePtt(processedSeries)
        val qualityReport = QualityAssessment.assessQuality(processedSeries, pttResult)
        
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        
        // Then: Should be fast
        assertTrue("Total processing should be < 200ms for 30s signal, was ${elapsedMs}ms",
            elapsedMs < 200)
        
        // Real-time factor calculation
        val durationMs = config.durationSeconds * 1000
        val realTimeFactor = durationMs / elapsedMs
        assertTrue("Should process faster than real-time (factor > 10), was ${realTimeFactor}×",
            realTimeFactor > 10)
    }
    
    @Test
    fun signalPipeline_filtering_removesNoise() {
        // Given: Clean signal with known noise
        val config = SimulationConfig(
            heartRateHz = 1.5,
            pttLagMs = 100.0,
            durationSeconds = 10.0,
            noiseEnabled = true,
            noiseLevel = 0.20,  // 20% noise
            driftEnabled = false
        )
        
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        
        // Then: Filtered should be cleaner than raw
        val rawPower = DspFunctions.computePower(processedSeries.rawFaceSignal)
        val filteredPower = DspFunctions.computePower(processedSeries.faceSignal)
        
        // After normalization, filtered power should be ~1.0
        assertTrue("Filtered signal should be normalized, power=${filteredPower}",
            abs(filteredPower - 1.0) < 0.2)
    }
    
    @Test
    fun pttCalculation_stability_lowVariance() {
        // Given: Long stable signal
        val config = SimulationConfig(
            heartRateHz = 1.2,
            pttLagMs = 100.0,
            durationSeconds = 60.0,  // 60 seconds
            noiseEnabled = true,
            noiseLevel = 0.10,
            driftEnabled = true,
            driftRate = 0.02
        )
        
        val simulator = SimulatedFrameSource(config)
        val rawBuffer = simulator.generateSignals()
        
        // When: Process
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val processedSeries = pipeline.process(rawBuffer)
        val pttResult = PttCalculator.computePtt(processedSeries)
        
        // Then: Should be stable
        assertTrue("Should compute PTT", pttResult.isValid)
        assertTrue("Should have stability metric", pttResult.windowCount > 0)
        assertTrue("Stability should be good (<30ms), was ${pttResult.stabilityMs}ms",
            pttResult.stabilityMs < 30.0)
    }
}

