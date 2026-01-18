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
import com.vivopulse.signal.PosExtractor
import com.vivopulse.signal.ProcessedSignal

/**
 * Signal processing pipeline for dual camera PPG signals.
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
        val denoisedHarmonics: HarmonicFeatureExtractor.HarmonicFeatures?,
        val posSignal: DoubleArray? = null
    )

    fun process(
        rawBuffer: RawSeriesBuffer,
        preProcessedSignals: List<ProcessedSignal>? = null
    ): ProcessedSeries {
        Log.d(tag, "Starting pipeline process. Raw buffer size: ${rawBuffer.faceData.size} (face), ${rawBuffer.fingerData.size} (finger)")
        
        var avgFaceSqi = 100
        var avgFingerSqi = 100
        if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            avgFaceSqi = preProcessedSignals.map { it.faceSqi }.average().toInt()
            avgFingerSqi = preProcessedSignals.map { it.fingerSqi }.average().toInt()
        }
        
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
        
        val rawFaceSignal = resampled.stream1Values.toDoubleArray()
        val rawFingerSignal = resampled.stream2Values.toDoubleArray()
        
        val timeMillis = resampled.unifiedTimestamps.map { it / 1_000_000.0 }
        
        var faceMotion: DoubleArray = doubleArrayOf()
        var fingerSat: DoubleArray = doubleArrayOf()
        var imuRms: DoubleArray = doubleArrayOf()
        var faceSqi: IntArray = intArrayOf()
        var fingerSqi: IntArray = intArrayOf()
        var consensusPtt: Double? = null
        
        if (rawBuffer.faceMotion != null && rawBuffer.fingerSaturation != null && resampled.isValid) {
            // Priority 1: Use raw buffer metrics if available (resampled to unified timeline)
            val interpMotion = TimestampSync.interpolateStream(rawBuffer.faceMotion, resampled.unifiedTimestamps)
            val interpSat = TimestampSync.interpolateStream(rawBuffer.fingerSaturation, resampled.unifiedTimestamps)
            val interpImu = if (rawBuffer.imuRms != null) {
                TimestampSync.interpolateStream(rawBuffer.imuRms, resampled.unifiedTimestamps)
            } else {
                List(timeMillis.size) { 0.0 }
            }
            
            faceMotion = interpMotion.toDoubleArray()
            fingerSat = interpSat.toDoubleArray()
            imuRms = interpImu.toDoubleArray()
            
            // Calculate simplistic SQI for now or reuse preProcessed if available
            // If preProcessed passed, we might want to still use its SQI?
            if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
                 val sourceSize = preProcessedSignals.size
                 val targetSize = timeMillis.size
                 faceSqi = IntArray(targetSize)
                 fingerSqi = IntArray(targetSize)
                 for (i in 0 until targetSize) {
                    val sourceIndex = (i.toDouble() / targetSize * sourceSize).toInt().coerceIn(0, sourceSize - 1)
                    val signal = preProcessedSignals[sourceIndex]
                    faceSqi[i] = signal.faceSqi
                    fingerSqi[i] = signal.fingerSqi
                 }
                 consensusPtt = preProcessedSignals.mapNotNull { it.consensusPtt }.lastOrNull()
            } else {
                 faceSqi = IntArray(timeMillis.size) { avgFaceSqi }
                 fingerSqi = IntArray(timeMillis.size) { avgFingerSqi }
            }
            
        } else if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            // Priority 2: Use preProcessedSignals (Legacy path)
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
        
        val motionFeatures = if (walkingModeEnabled && imuRms.isNotEmpty()) {
            imuAnalyzer.analyze(imuRms, targetSampleRateHz)
        } else {
            ImuMotionAnalyzer.MotionFeatures(null, 0.0, false)
        }
        
        val rawFaceRgbList = rawBuffer.faceRgb?.let { interpolateRgb(it, resampled.unifiedTimestamps) }
        val faceResult = processChannel(rawFaceSignal, avgFaceSqi, motionFeatures, rawFaceRgbList)
        
        val rawFingerRgbList = rawBuffer.fingerRgb?.let { interpolateRgb(it, resampled.unifiedTimestamps) }
        val fingerResult = processChannel(rawFingerSignal, avgFingerSqi, motionFeatures, rawFingerRgbList)
        
        val pttOutput = PttEngine.computePtt(
            faceSig = faceResult.mainSignal,
            fingerSig = fingerResult.mainSignal,
            faceRaw = rawFaceSignal,
            fingerRaw = rawFingerSignal,
            fsHz = targetSampleRateHz,
            windowSec = correlationWindowSec,
            faceMotionPenalty = 100.0
        )
        
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
            denoisedHarmonicsFinger = fingerResult.denoisedHarmonics,
            rawFaceRgb = rawFaceRgbList,
            rawFingerRgb = rawFingerRgbList,
            faceSignalPos = faceResult.posSignal,
            fingerSignalPos = fingerResult.posSignal
        )
    }

    private fun interpolateRgb(
        rawRgb: List<Pair<Long, Triple<Double, Double, Double>>>?,
        timestamps: List<Long>
    ): List<Triple<Double, Double, Double>>? {
        if (rawRgb == null || timestamps.isEmpty()) return null

        val rStream = rawRgb.map { TimestampedValue(it.first, it.second.first) }
        val gStream = rawRgb.map { TimestampedValue(it.first, it.second.second) }
        val bStream = rawRgb.map { TimestampedValue(it.first, it.second.third) }

        val rInterp = TimestampSync.interpolateStream(rStream, timestamps)
        val gInterp = TimestampSync.interpolateStream(gStream, timestamps)
        val bInterp = TimestampSync.interpolateStream(bStream, timestamps)

        return rInterp.indices.map { i ->
            Triple(rInterp[i], gInterp[i], bInterp[i])
        }
    }
    
    private fun processChannel(
        rawSignal: DoubleArray, 
        sqi: Int = 100, 
        motionFeatures: ImuMotionAnalyzer.MotionFeatures? = null,
        rgbTrace: List<Triple<Double, Double, Double>>? = null
    ): ChannelResult {
        if (rawSignal.isEmpty()) return ChannelResult(
            doubleArrayOf(), null, 
            HarmonicFeatureExtractor.HarmonicFeatures.empty(), null, null
        )
        
        val detrended = DspFunctions.detrendIIR(rawSignal, 0.5, targetSampleRateHz)
        
        var mainFiltered = DspFunctions.butterworthBandpass(
            signal = detrended,
            lowCutoffHz = lowCutoffHz,
            highCutoffHz = highCutoffHz,
            sampleRateHz = targetSampleRateHz,
            order = 4
        )
        
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
        
        if (motionFeatures != null && walkingModeEnabled) {
            mainFiltered = StepNotchFilter.apply(mainFiltered, motionFeatures, targetSampleRateHz)
            if (denoisedFiltered != null) {
                denoisedFiltered = StepNotchFilter.apply(denoisedFiltered, motionFeatures, targetSampleRateHz)
            }
        }
        
        val mainNormalized = DspFunctions.zscoreNormalize(mainFiltered)
        val denoisedNormalized = if (denoisedFiltered != null) DspFunctions.zscoreNormalize(denoisedFiltered) else null
        
        val mainHarmonics = HarmonicFeatureExtractor.extractHarmonicFeatures(mainNormalized, targetSampleRateHz)
        val denoisedHarmonics = if (denoisedNormalized != null) {
            HarmonicFeatureExtractor.extractHarmonicFeatures(denoisedNormalized, targetSampleRateHz)
        } else {
            null
        }
        
        var posSignal: DoubleArray? = null
        if (rgbTrace != null && rgbTrace.isNotEmpty()) {
            val r = rgbTrace.map { it.first }.toDoubleArray()
            val g = rgbTrace.map { it.second }.toDoubleArray()
            val b = rgbTrace.map { it.third }.toDoubleArray()
            
            val rawPos = PosExtractor.computePosSignal(r, g, b, targetSampleRateHz)
            
            val detrendedPos = DspFunctions.detrendIIR(rawPos, 0.5, targetSampleRateHz)
            val filteredPos = DspFunctions.butterworthBandpass(
                signal = detrendedPos, 
                lowCutoffHz = lowCutoffHz, 
                highCutoffHz = highCutoffHz, 
                sampleRateHz = targetSampleRateHz
            )
            posSignal = DspFunctions.zscoreNormalize(filteredPos)
        }
        
        return ChannelResult(mainNormalized, denoisedNormalized, mainHarmonics, denoisedHarmonics, posSignal)
    }
}

data class RawSeriesBuffer(
    val faceData: List<TimestampedValue>,
    val fingerData: List<TimestampedValue>,
    val faceRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val fingerRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val faceMotion: List<TimestampedValue>? = null,
    val fingerSaturation: List<TimestampedValue>? = null,
    val imuRms: List<TimestampedValue>? = null,
    val faceRoi: List<Pair<Long, android.graphics.Rect>>? = null
)

data class ProcessedSeries(
    val timeMillis: List<Double>,
    val faceSignal: DoubleArray,
    val fingerSignal: DoubleArray,
    val faceSignalDenoised: DoubleArray? = null,
    val fingerSignalDenoised: DoubleArray? = null,
    val rawFaceSignal: DoubleArray = doubleArrayOf(),
    val rawFingerSignal: DoubleArray = doubleArrayOf(),
    val sampleRateHz: Double,
    val isValid: Boolean,
    val pttOutput: PttOutput? = null,
    val pttOutputDenoised: PttOutput? = null,
    val message: String = "",
    val faceMotionRms: DoubleArray = doubleArrayOf(),
    val fingerSaturationPct: DoubleArray = doubleArrayOf(),
    val imuRmsG: DoubleArray = doubleArrayOf(),
    val faceSqi: IntArray = intArrayOf(),
    val fingerSqi: IntArray = intArrayOf(),
    val consensusPtt: Double? = null,
    val mainHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
    val mainHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
    val denoisedHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val denoisedHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val faceSignalPos: DoubleArray? = null,
    val fingerSignalPos: DoubleArray? = null,
    val rawFaceRgb: List<Triple<Double, Double, Double>>? = null,
    val rawFingerRgb: List<Triple<Double, Double, Double>>? = null,
    val faceRoi: List<android.graphics.Rect?>? = null
) {
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
