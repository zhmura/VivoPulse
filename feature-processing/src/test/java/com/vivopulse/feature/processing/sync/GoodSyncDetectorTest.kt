package com.vivopulse.feature.processing.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodSyncDetectorTest {

    private val detector = GoodSyncDetector()

    @Test
    fun `detectGoodSyncWindows identifies clear synchronized signals`() {
        // Generate 8s of sharp pulses at 100 Hz to satisfy FWHM < 120ms check
        val fs = 100.0
        val duration = 8.0
        val samples = (duration * fs).toInt()
        
        // 75 BPM = 0.8s period
        val period = 0.8
        
        val face = DoubleArray(samples) { i -> 
            val t = i / fs
            val cycle = t % period
            // Gaussian pulse: exp(-((t - center)^2) / (2*sigma^2))
            // center at 0.4s. sigma=0.05s -> width ~117ms?
            // Using narrower pulse:
            val dt = cycle - period/2
            Math.exp(-400.0 * dt * dt)
        }
        val finger = face.clone() // Perfect sync

        val roiStats = RoiStats(
            faceMotionRmsPx = 0.1,
            fingerSaturationPct = 0.01,
            snrDbFace = 15.0,
            snrDbFinger = 20.0,
            imuRmsG = 0.01
        )

        val segments = detector.detectGoodSyncWindows(
            face = face,
            finger = finger,
            fsHz = fs,
            imuTrace = null,
            roiStats = roiStats
        )

        assertEquals(1, segments.size)
        assertTrue(segments[0].corr > 0.9)
    }

    @Test
    fun `detectGoodSyncWindows rejects noisy signals`() {
        val fs = 100.0
        val duration = 8.0
        val samples = (duration * fs).toInt()
        val face = DoubleArray(samples) { Math.random() } // Pure noise
        val finger = DoubleArray(samples) { Math.random() }

        val roiStats = RoiStats(
            faceMotionRmsPx = 0.1,
            fingerSaturationPct = 0.01,
            snrDbFace = 2.0, // Low SNR
            snrDbFinger = 3.0,
            imuRmsG = 0.01
        )

        val segments = detector.detectGoodSyncWindows(
            face = face,
            finger = finger,
            fsHz = fs,
            imuTrace = null,
            roiStats = roiStats
        )

        assertEquals(0, segments.size)
    }
    
    @Test
    fun `detectGoodSyncWindows returns empty if SQI is low despite correlation`() {
        // Good correlation but poor image quality (e.g. motion)
        val fs = 100.0
        val duration = 8.0
        val samples = (duration * fs).toInt()
        val face = DoubleArray(samples) { i -> Math.sin(2 * Math.PI * 1.2 * i / fs) }
        val finger = DoubleArray(samples) { i -> Math.sin(2 * Math.PI * 1.2 * i / fs) }

        val roiStats = RoiStats(
            faceMotionRmsPx = 2.0, // High motion
            fingerSaturationPct = 0.01,
            snrDbFace = 15.0,
            snrDbFinger = 20.0,
            imuRmsG = 0.01
        )

        val segments = detector.detectGoodSyncWindows(
            face = face,
            finger = finger,
            fsHz = fs,
            imuTrace = null,
            roiStats = roiStats
        )

        assertEquals(0, segments.size)
    }
}
