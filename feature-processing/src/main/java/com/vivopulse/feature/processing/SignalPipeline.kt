package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.ptt.PttEngine
import com.vivopulse.feature.processing.ptt.PttOutput
import com.vivopulse.feature.processing.timestamp.TimestampSync
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.DspFunctions
import android.util.Log
import com.vivopulse.feature.processing.wavelet.WaveletDenoiser
import com.vivopulse.feature.processing.motion.ImuMotionAnalyzer
import com.vivopulse.feature.processing.motion.StepNotchFilter
import com.vivopulse.signal.HarmonicFeatureExtractor

/**
 * Signal processing pipeline for dual camera PPG signals.
 * 
 * Full processing chain:
 * 1. Timestamp resampling to unified 100 Hz timeline
 * 2. Detrending (remove baseline drift)
 * 3. Wavelet Denoising (conditional for diagnostics)
 * 4. Bandpass filtering (0.7-4.0 Hz for heart rate)
 * 5. Walking Mode: Adaptive Notch Filter (if enabled)
 * 6. Z-score normalization
 * 7. Harmonic Feature Extraction
 * 8. PTT-SQI confidence assessment (on main signal)
 */
class SignalPipeline(
    private val targetSampleRateHz: Double = 100.0,
    private val lowCutoffHz: Double = 0.7,
    private val highCutoffHz: Double = 4.0,
    private val correlationWindowSec: Double = 20.0,
    private val walkingModeEnabled: Boolean = false
) {
    private val tag = "SignalPipeline"
    private val imuAnalyzer = ImuMotionAnalyzer()
    
    data class ChannelResult(
        val mainSignal: DoubleArray,
        val denoisedSignal: DoubleArray?,
        val mainHarmonics: HarmonicFeatureExtractor.HarmonicFeatures,
        val denoisedHarmonics: HarmonicFeatureExtractor.HarmonicFeatures?
    )

    /**
     * Process raw signal buffers into clean, aligned signals.
     */
    fun process(
        rawBuffer: RawSeriesBuffer,
        preProcessedSignals: List<com.vivopulse.signal.ProcessedSignal>? = null
    ): ProcessedSeries {
        Log.d(tag, "Starting pipeline process. Raw buffer size: ${rawBuffer.faceData.size} (face), ${rawBuffer.fingerData.size} (finger)")
        
        // Calculate average SQI from real-time signals to guide processing
        var avgFaceSqi = 100
        var avgFingerSqi = 100
        if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            avgFaceSqi = preProcessedSignals.map { it.faceSqi }.average().toInt()
            avgFingerSqi = preProcessedSignals.map { it.fingerSqi }.average().toInt()
        }
        
        // Resample both channels to unified 100 Hz timeline
        val resampled = TimestampSync.resampleToUnifiedTimeline(
            stream1Data = rawBuffer.faceData,
            stream2Data = rawBuffer.fingerData,
            targetFrequencyHz = targetSampleRateHz
        )
        
        if (!resampled.isValid) {
            Log.w(tag, "Resampling failed: ${resampled.message}")
            return ProcessedSeries(
                timeMillis = emptyList(),
                faceSignal = doubleArrayOf(),
                fingerSignal = doubleArrayOf(),
                sampleRateHz = targetSampleRateHz,
                isValid = false,
                message = resampled.message,
                mainHarmonicsFace = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
                mainHarmonicsFinger = HarmonicFeatureExtractor.HarmonicFeatures.empty()
            )
        }
        
        // Extract face and finger signals
        val rawFaceSignal = resampled.stream1Values.toDoubleArray()
        val rawFingerSignal = resampled.stream2Values.toDoubleArray()
        
        // Convert timestamps to milliseconds
        val timeMillis = resampled.unifiedTimestamps.map { it / 1_000_000.0 }
        
        // Resample metrics
        var faceMotion: DoubleArray = doubleArrayOf()
        var fingerSat: DoubleArray = doubleArrayOf()
        var imuRms: DoubleArray = doubleArrayOf()
        var faceSqi: IntArray = intArrayOf()
        var fingerSqi: IntArray = intArrayOf()
        var consensusPtt: Double? = null
        
        if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            faceMotion = DoubleArray(timeMillis.size)
            fingerSat = DoubleArray(timeMillis.size)
            imuRms = DoubleArray(timeMillis.size)
            faceSqi = IntArray(timeMillis.size)
            fingerSqi = IntArray(timeMillis.size)
            
            val sourceSize = preProcessedSignals.size
            val targetSize = timeMillis.size
            
            for (i in 0 until targetSize) {
                val sourceIndex = (i.toDouble() / targetSize * sourceSize).toInt().coerceIn(0, sourceSize - 1)
                val signal = preProcessedSignals[sourceIndex]
                
                faceMotion[i] = signal.faceMotionRms
                fingerSat[i] = signal.fingerSaturationPct
                imuRms[i] = signal.imuRmsG
                faceSqi[i] = signal.faceSqi
                fingerSqi[i] = signal.fingerSqi
            }
            
            consensusPtt = preProcessedSignals.mapNotNull { it.consensusPtt }.lastOrNull()
        }
        
        // Analyze motion (Walking Mode)
        val motionFeatures = if (walkingModeEnabled && imuRms.isNotEmpty()) {
            imuAnalyzer.analyze(imuRms, targetSampleRateHz)
        } else {
            ImuMotionAnalyzer.MotionFeatures(null, 0.0, false)
        }
        
        // Process FACE channel
        val faceResult = processChannel(rawFaceSignal, avgFaceSqi, motionFeatures)
        
        // Process FINGER channel
        val fingerResult = processChannel(rawFingerSignal, avgFingerSqi, motionFeatures)
        
        // Compute PTT (Main Path - Raw/Standard Bandpass)
        // Note: Phase 3 requires keeping main PTT logic unchanged (i.e. use standard bandpass if that was default)
        // But we integrated Wavelet into "main" path in Phase 2.
        // If we want to be strictly "Read-Only", we should use the "Main" path which is now the one without Wavelet?
        // The previous implementation conditionally applied wavelet. 
        // Here, processChannel returns 'mainSignal' which IS the processed signal (potentially with wavelet if configured).
        // Wait, my previous implementation of processChannel returned ONE array.
        // Now I'm returning ChannelResult with Main and Denoised.
        // I should clarify what "Main" is.
        // To support Phase 3 diagnostics, "Main" should be the standard pipeline (potentially with wavelet if Phase 2 is active).
        // And "Denoised" should be the *explicitly* denoised one if "Main" wasn't?
        // Or "Main" = Bandpass Only. "Denoised" = Wavelet + Bandpass.
        // If Phase 2 is "active", then "Main" = Wavelet + Bandpass.
        // But Phase 3 says "Do not change main PTT output logic yet".
        // So I will set "Main" = Bandpass Only (Standard).
        // And "Denoised" = Wavelet + Bandpass (Experimental).
        
        val pttOutput = PttEngine.computePtt(
            faceSig = faceResult.mainSignal,
            fingerSig = fingerResult.mainSignal,
            faceRaw = rawFaceSignal,
            fingerRaw = rawFingerSignal,
            fsHz = targetSampleRateHz,
            windowSec = correlationWindowSec,
            faceMotionPenalty = 100.0
        )
        
        // Compute Diagnostic PTT (Denoised) if available
        var pttDenoised: PttOutput? = null
        if (faceResult.denoisedSignal != null && fingerResult.denoisedSignal != null) {
             pttDenoised = PttEngine.computePtt(
                faceSig = faceResult.denoisedSignal,
                fingerSig = fingerResult.denoisedSignal,
                faceRaw = rawFaceSignal,
                fingerRaw = rawFingerSignal,
                fsHz = targetSampleRateHz,
                windowSec = correlationWindowSec,
                faceMotionPenalty = 100.0
            )
        }
        
        return ProcessedSeries(
            timeMillis = timeMillis,
            faceSignal = faceResult.mainSignal,
            fingerSignal = fingerResult.mainSignal,
            faceSignalDenoised = faceResult.denoisedSignal,
            fingerSignalDenoised = fingerResult.denoisedSignal,
            rawFaceSignal = rawFaceSignal,
            rawFingerSignal = rawFingerSignal,
            sampleRateHz = targetSampleRateHz,
            isValid = true,
            pttOutput = pttOutput,
            pttOutputDenoised = pttDenoised,
            message = "Processed ${timeMillis.size} samples",
            faceMotionRms = faceMotion,
            fingerSaturationPct = fingerSat,
            imuRmsG = imuRms,
            faceSqi = faceSqi,
            fingerSqi = fingerSqi,
            consensusPtt = consensusPtt,
            mainHarmonicsFace = faceResult.mainHarmonics,
            mainHarmonicsFinger = fingerResult.mainHarmonics,
            denoisedHarmonicsFace = faceResult.denoisedHarmonics,
            denoisedHarmonicsFinger = fingerResult.denoisedHarmonics
        )
    }
    
    private fun processChannel(
        rawSignal: DoubleArray, 
        sqi: Int = 100, 
        motionFeatures: ImuMotionAnalyzer.MotionFeatures? = null
    ): ChannelResult {
        if (rawSignal.isEmpty()) return ChannelResult(
            doubleArrayOf(), null, 
            HarmonicFeatureExtractor.HarmonicFeatures.empty(), null
        )
        
        // 1. Detrend
        val detrended = DspFunctions.detrendIIR(rawSignal, 0.5, targetSampleRateHz)
        
        // Path A: Standard Bandpass (Main)
        var mainFiltered = DspFunctions.butterworthBandpass(
            signal = detrended,
            lowCutoffHz = lowCutoffHz,
            highCutoffHz = highCutoffHz,
            sampleRateHz = targetSampleRateHz,
            order = 4
        )
        
        // Path B: Wavelet Denoised (Diagnostic / Conditional)
        // Apply if SQI is borderline (40-80).
        var denoisedFiltered: DoubleArray? = null
        if (sqi in 40..80) {
            val waveletCleaned = WaveletDenoiser.denoise(detrended, WaveletDenoiser.Config(levels = 4))
            denoisedFiltered = DspFunctions.butterworthBandpass(
                signal = waveletCleaned,
                lowCutoffHz = lowCutoffHz,
                highCutoffHz = highCutoffHz,
                sampleRateHz = targetSampleRateHz,
                order = 4
            )
        }
        
        // Apply Notch (Walking Mode) to both if active
        if (motionFeatures != null && walkingModeEnabled) {
            mainFiltered = StepNotchFilter.apply(mainFiltered, motionFeatures, targetSampleRateHz)
            if (denoisedFiltered != null) {
                denoisedFiltered = StepNotchFilter.apply(denoisedFiltered, motionFeatures, targetSampleRateHz)
            }
        }
        
        // Normalize
        val mainNormalized = DspFunctions.zscoreNormalize(mainFiltered)
        val denoisedNormalized = if (denoisedFiltered != null) DspFunctions.zscoreNormalize(denoisedFiltered) else null
        
        // Harmonics
        val mainHarmonics = HarmonicFeatureExtractor.extractHarmonicFeatures(mainNormalized, targetSampleRateHz)
        val denoisedHarmonics = if (denoisedNormalized != null) {
            HarmonicFeatureExtractor.extractHarmonicFeatures(denoisedNormalized, targetSampleRateHz)
        } else null
        
        return ChannelResult(mainNormalized, denoisedNormalized, mainHarmonics, denoisedHarmonics)
    }
}

data class RawSeriesBuffer(
    val faceData: List<TimestampedValue>,
    val fingerData: List<TimestampedValue>
)

data class ProcessedSeries(
    val timeMillis: List<Double>,
    val faceSignal: DoubleArray, // Main (Standard)
    val fingerSignal: DoubleArray, // Main (Standard)
    val faceSignalDenoised: DoubleArray? = null, // Diagnostic
    val fingerSignalDenoised: DoubleArray? = null, // Diagnostic
    val rawFaceSignal: DoubleArray = doubleArrayOf(),
    val rawFingerSignal: DoubleArray = doubleArrayOf(),
    val sampleRateHz: Double,
    val isValid: Boolean,
    val pttOutput: PttOutput? = null,
    val pttOutputDenoised: PttOutput? = null, // Diagnostic PTT
    val message: String = "",
    val faceMotionRms: DoubleArray = doubleArrayOf(),
    val fingerSaturationPct: DoubleArray = doubleArrayOf(),
    val imuRmsG: DoubleArray = doubleArrayOf(),
    val faceSqi: IntArray = intArrayOf(),
    val fingerSqi: IntArray = intArrayOf(),
    val consensusPtt: Double? = null,
    // New Harmonic Features
    val mainHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures,
    val mainHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures,
    val denoisedHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val denoisedHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null
) {
    // ... methods ...
    fun getSampleCount(): Int = timeMillis.size
    
    fun getDurationSeconds(): Double {
        return if (timeMillis.isNotEmpty()) {
            (timeMillis.last() - timeMillis.first()) / 1000.0
        } else {
            0.0
        }
    }
    
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
