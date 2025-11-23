package com.vivopulse.feature.processing.ptt

import com.vivopulse.feature.processing.sync.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PTTConsensusTest {

    private val consensus = PTTConsensus()

    @Test
    fun `estimateConsensusPtt aligns XCorr and Foot methods`() {
        val fs = 100.0
        val duration = 2.0
        val samples = (duration * fs).toInt()
        val pttLagSamples = 10 // 100ms
        
        // Create pulse train
        val face = DoubleArray(samples) { i ->
            val t = i / fs
            if (t > 0.5 && t < 0.6) 1.0 else 0.0
        }
        
        // Finger delayed by 100ms
        val finger = DoubleArray(samples) { i ->
            val t = i / fs
            if (t > 0.6 && t < 0.7) 1.0 else 0.0
        }
        
        val result = consensus.estimateConsensusPtt(
            face = face,
            finger = finger,
            fsHz = fs,
            segment = Window(0, 2000)
        )
        
        // XCorr should find ~100ms
        // Foot-to-foot might be tricky with square pulses but derivative logic handles upstroke
        // Let's check agreement
        
        // Actually, SyncMetrics (XCorr) and Foot detect should both yield ~100ms
        // Agreement should be close to 0
        
        // Note: Square pulse derivative is sharp. Upstroke at 0.5s (Face) and 0.6s (Finger).
        // Foot detector looks for local min before upstroke.
        // For square pulse: 0 0 0 1 1
        // Diff: 0 0 0.5 0
        // Max slope is 0.5 (or 1.0 depending on logic).
        
        // Due to simplicity of test signal, exact millisecond match might vary slightly
        // But it should be within 20ms agreement
        
        assertEquals(100.0, result.pttMsMedian, 20.0)
        assertTrue(result.methodAgreeMs <= 20.0)
    }
    
    @Test
    fun `detectFeet uses slope threshold`() {
        // This test indirectly verifies detectFeet logic via estimateConsensusPtt
        
        // Create small noise vs big pulse
        val fs = 100.0
        val samples = 200
        val faceSignal = DoubleArray(samples)
        val fingerSignal = DoubleArray(samples)
        
        // Small noise upstrokes
        for (i in 0 until 50) {
            faceSignal[i] = Math.sin(i * 0.5) * 0.01
            fingerSignal[i] = Math.sin(i * 0.5) * 0.01
        }
        
        // Big pulse upstroke at index 100 for Face
        for (i in 100 until 120) faceSignal[i] = (i - 100) * 1.0
        
        // Delayed pulse for Finger (100ms delay = 10 samples) at index 110
        for (i in 110 until 130) fingerSignal[i] = (i - 110) * 1.0
        
        val result = consensus.estimateConsensusPtt(
            face = faceSignal,
            finger = fingerSignal,
            fsHz = fs,
            segment = Window(0, 2000)
        )
        
        // Should detect 1 beat with ~100ms lag
        assertTrue("Should detect at least 1 beat", result.nBeats >= 1)
        assertEquals(100.0, result.pttMsMedian, 10.0)
    }
}

