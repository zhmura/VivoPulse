package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.ptt.PttEngine
import com.vivopulse.feature.processing.ptt.PttOutput
import com.vivopulse.feature.processing.timestamp.TimestampSync
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.DspFunctions

/**
 * Signal processing pipeline for dual camera PPG signals.
 * 
 * Full processing chain:
 * 1. Timestamp resampling to unified 100 Hz timeline
 * 2. Detrending (remove baseline drift)
 * 3. Bandpass filtering (0.7-4.0 Hz for heart rate)
 * 4. Z-score normalization
 * 5. Peak detection (both channels)
 * 6. Heart rate computation
 * 7. Cross-correlation lag (PTT)
 * 8. PTT-SQI confidence assessment
 */
class SignalPipeline(
    private val targetSampleRateHz: Double = 100.0,
    private val lowCutoffHz: Double = 0.7,
    private val highCutoffHz: Double = 4.0,
    private val correlationWindowSec: Double = 20.0
) {
    
    /**
     * Process raw signal buffers into clean, aligned signals.
     * 
     * @param rawBuffer Raw series buffer with face and finger data
     * @return ProcessedSeries with aligned, filtered signals
     */
    /**
     * Process raw signal buffers into clean, aligned signals.
     * 
     * @param rawBuffer Raw series buffer with face and finger data
     * @param preProcessedSignals Optional list of real-time processed signals (for motion metrics)
     * @return ProcessedSeries with aligned, filtered signals
     */
    fun process(
        rawBuffer: RawSeriesBuffer,
        preProcessedSignals: List<com.vivopulse.signal.ProcessedSignal>? = null
    ): ProcessedSeries {
        // Resample both channels to unified 100 Hz timeline
        val resampled = TimestampSync.resampleToUnifiedTimeline(
            stream1Data = rawBuffer.faceData,
            stream2Data = rawBuffer.fingerData,
            targetFrequencyHz = targetSampleRateHz
        )
        
        if (!resampled.isValid) {
            return ProcessedSeries(
                timeMillis = emptyList(),
                faceSignal = doubleArrayOf(),
                fingerSignal = doubleArrayOf(),
                sampleRateHz = targetSampleRateHz,
                isValid = false,
                message = resampled.message
            )
        }
        
        // Extract face and finger signals
        val rawFaceSignal = resampled.stream1Values.toDoubleArray()
        val rawFingerSignal = resampled.stream2Values.toDoubleArray()
        
        // Apply DSP pipeline to FACE signal
        val faceProcessed = processChannel(rawFaceSignal)
        
        // Apply DSP pipeline to FINGER signal
        val fingerProcessed = processChannel(rawFingerSignal)
        
        // Convert timestamps to milliseconds
        val timeMillis = resampled.unifiedTimestamps.map { it / 1_000_000.0 }
        
        // Resample metrics if available
        var faceMotion: DoubleArray = doubleArrayOf()
        var fingerSat: DoubleArray = doubleArrayOf()
        var faceSqi: IntArray = intArrayOf()
        var fingerSqi: IntArray = intArrayOf()
        var consensusPtt: Double? = null
        
        if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            // Simple resampling/interpolation for metrics
            // For now, just taking the nearest neighbor or average
            // Since metrics are sparse (30Hz vs 100Hz), we interpolate
            // TODO: Implement proper interpolation
            // Placeholder: just fill with 0 or average for now to avoid crash
            faceMotion = DoubleArray(timeMillis.size)
            fingerSat = DoubleArray(timeMillis.size)
            faceSqi = IntArray(timeMillis.size)
            fingerSqi = IntArray(timeMillis.size)
            
            // Extract consensus PTT from the last valid signal
            consensusPtt = preProcessedSignals.mapNotNull { it.consensusPtt }.lastOrNull()
        }
        
        // Compute PTT with full quality assessment
        val pttOutput = PttEngine.computePtt(
            faceSig = faceProcessed,
            fingerSig = fingerProcessed,
            faceRaw = rawFaceSignal,
            fingerRaw = rawFingerSignal,
            fsHz = targetSampleRateHz,
            windowSec = correlationWindowSec,
            faceMotionPenalty = 100.0 // Will be injected from motion analyzer later
        )
        
        return ProcessedSeries(
            timeMillis = timeMillis,
            faceSignal = faceProcessed,
            fingerSignal = fingerProcessed,
            rawFaceSignal = rawFaceSignal,
            rawFingerSignal = rawFingerSignal,
            sampleRateHz = targetSampleRateHz,
            isValid = true,
            pttOutput = pttOutput,
            message = "Processed ${timeMillis.size} samples at ${targetSampleRateHz} Hz",
            faceMotionRms = faceMotion,
            fingerSaturationPct = fingerSat,
            faceSqi = faceSqi,
            fingerSqi = fingerSqi,
            consensusPtt = consensusPtt
        )
    }
    
    /**
     * Apply DSP pipeline to a single channel.
     * 
     * Steps: detrend → bandpass → normalize
     */
    private fun processChannel(rawSignal: DoubleArray): DoubleArray {
        if (rawSignal.isEmpty()) return doubleArrayOf()
        
        // Step 1: Detrend (remove baseline drift and DC)
        val detrended = DspFunctions.detrendIIR(
            signal = rawSignal,
            cutoffHz = 0.5,
            sampleRateHz = targetSampleRateHz
        )
        
        // Step 2: Bandpass filter (isolate heart rate frequencies)
        val filtered = DspFunctions.butterworthBandpass(
            signal = detrended,
            lowCutoffHz = lowCutoffHz,
            highCutoffHz = highCutoffHz,
            sampleRateHz = targetSampleRateHz,
            order = 2
        )
        
        // Step 3: Z-score normalization (zero mean, unit variance)
        val normalized = DspFunctions.zscoreNormalize(filtered)
        
        return normalized
    }
}

/**
 * Raw series buffer with timestamped values.
 */
data class RawSeriesBuffer(
    val faceData: List<TimestampedValue>,
    val fingerData: List<TimestampedValue>
)

/**
 * Processed signal series with aligned face and finger signals.
 */
data class ProcessedSeries(
    val timeMillis: List<Double>,
    val faceSignal: DoubleArray,
    val fingerSignal: DoubleArray,
    val rawFaceSignal: DoubleArray = doubleArrayOf(),
    val rawFingerSignal: DoubleArray = doubleArrayOf(),
    val sampleRateHz: Double,
    val isValid: Boolean,
    val pttOutput: PttOutput? = null,  // PTT computation result
    val message: String = "",
    // New Metrics
    val faceMotionRms: DoubleArray = doubleArrayOf(),
    val fingerSaturationPct: DoubleArray = doubleArrayOf(),
    val faceSqi: IntArray = intArrayOf(),
    val fingerSqi: IntArray = intArrayOf(),
    val consensusPtt: Double? = null
) {
    /**
     * Get number of samples.
     */
    fun getSampleCount(): Int = timeMillis.size
    
    /**
     * Get duration in seconds.
     */
    fun getDurationSeconds(): Double {
        return if (timeMillis.isNotEmpty()) {
            (timeMillis.last() - timeMillis.first()) / 1000.0
        } else {
            0.0
        }
    }
    
    /**
     * Check if arrays are aligned (same length).
     */
    fun isAligned(): Boolean {
        return timeMillis.size == faceSignal.size && 
               timeMillis.size == fingerSignal.size
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessedSeries

        if (timeMillis != other.timeMillis) return false
        if (!faceSignal.contentEquals(other.faceSignal)) return false
        if (!fingerSignal.contentEquals(other.fingerSignal)) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (isValid != other.isValid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timeMillis.hashCode()
        result = 31 * result + faceSignal.contentHashCode()
        result = 31 * result + fingerSignal.contentHashCode()
        result = 31 * result + sampleRateHz.hashCode()
        result = 31 * result + isValid.hashCode()
        return result
    }
}

