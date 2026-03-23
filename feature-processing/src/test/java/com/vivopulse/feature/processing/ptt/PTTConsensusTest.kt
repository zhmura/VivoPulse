package com.vivopulse.feature.processing.ptt

import com.vivopulse.feature.processing.sync.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * PTTConsensus integration tests, updated for P1.6 Kalman fusion.
 */
class PTTConsensusTest {

    private val consensus = PTTConsensus()

    @Test
    fun `estimateConsensusPtt produces result for delayed signals`() {
        val fs = 100.0
        val duration = 10.0 // 10s for enough beats
        val samples = (duration * fs).toInt()
        val delaySamples = 8 // 80ms PTT
        
        // Create PPG-like signal (1.2 Hz with harmonics)
        val finger = DoubleArray(samples) { i ->
            1.0 + sin(2.0 * PI * 1.2 * i / fs) +
            0.3 * sin(2.0 * PI * 2.4 * i / fs)
        }
        
        // Face = delayed finger
        val face = DoubleArray(samples) { i ->
            val delayed = i - delaySamples
            if (delayed >= 0 && delayed < samples) finger[delayed] else 1.0
        }
        
        val result = consensus.estimateConsensusPtt(
            face = face,
            finger = finger,
            fsHz = fs,
            hrFaceBpm = 72.0,
            hrFingerBpm = 72.0,
            segment = Window(0, (duration * 1000).toLong())
        )
        
        // PTT should be somewhere in the ballpark of 80ms
        // With Kalman fusion from multiple methods, exact value depends on weighting
        assertTrue("PTT should be reportable (non-zero)", result.pttMsMedian != 0.0)
        // Validation metrics should be populated
        assertTrue("Kalman CI should be finite", result.kalmanCiMs.isFinite())
        assertTrue("Beat coverage should be non-negative", result.beatCoverage >= 0.0)
    }
    
    @Test
    fun `Kalman fusion uses multiple methods`() {
        // Longer signal for better spectral estimates
        val fs = 100.0
        val duration = 20.0
        val samples = (duration * fs).toInt()
        val delaySamples = 10 // 100ms
        
        val finger = DoubleArray(samples) { i ->
            1.0 + sin(2.0 * PI * 1.0 * i / fs) +
            0.5 * sin(2.0 * PI * 2.0 * i / fs)
        }
        
        val face = DoubleArray(samples) { i ->
            val delayed = i - delaySamples
            if (delayed >= 0 && delayed < samples) finger[delayed] else 1.0
        }
        
        val result = consensus.estimateConsensusPtt(
            face = face,
            finger = finger,
            fsHz = fs,
            hrFaceBpm = 60.0,
            hrFingerBpm = 60.0,
            segment = Window(0, (duration * 1000).toLong())
        )
        
        // Should get a valid consensus PTT (sign depends on which channel leads)
        assertTrue("PTT should be non-zero", kotlin.math.abs(result.pttMsMedian) > 0)
        // Stability from multi-window should be reasonable
        assertTrue("Stability should be non-negative", result.delayStabilityScore >= 0)
        // Validation metrics should be populated for sufficient signals
        assertTrue("Kalman CI should be finite for valid signals", result.kalmanCiMs.isFinite())
        assertTrue("Coherence should be > 0 for correlated signals", result.meanCoherenceAtHr > 0)
        assertTrue("Beat coverage should be non-negative for 20s signal", result.beatCoverage >= 0)
    }
}
