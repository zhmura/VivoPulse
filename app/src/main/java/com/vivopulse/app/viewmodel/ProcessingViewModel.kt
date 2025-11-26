package com.vivopulse.app.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivopulse.app.util.ErrorHandler
import com.vivopulse.app.viewmodel.SharedRecordingState
import com.vivopulse.feature.capture.RecordingResult
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.PttCalculator
import com.vivopulse.feature.processing.PttResult
import com.vivopulse.feature.processing.QualityAssessment
import com.vivopulse.feature.processing.QualityReport
import com.vivopulse.feature.processing.RawSeriesBuffer
import com.vivopulse.feature.processing.SessionSummary
import com.vivopulse.feature.processing.SignalPipeline
import com.vivopulse.feature.processing.wave.WaveFeatures
import com.vivopulse.feature.processing.simulation.SimulatedFrameSource
import com.vivopulse.feature.processing.simulation.SimulationConfig
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.feature.processing.ptt.HeartRate
import com.vivopulse.feature.processing.ptt.PeakDetect
import com.vivopulse.feature.processing.biomarker.BiomarkerComputer
import com.vivopulse.feature.processing.biomarker.BiomarkerPanel
import com.vivopulse.io.ClinicianGradeExporter
import com.vivopulse.io.model.SessionMetadata
import com.vivopulse.io.model.SignalDataPoint
import com.vivopulse.io.model.ExportExtras
import com.vivopulse.io.model.ExportSegment
import com.vivopulse.signal.DspFunctions
import com.vivopulse.signal.PerformanceMetrics
import com.vivopulse.signal.PerformanceReport
import com.vivopulse.signal.SignalQuality
import com.vivopulse.app.trend.VascularTrendStore
import com.vivopulse.app.trend.VascularTrendSummary
import com.vivopulse.app.util.FeatureFlags
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for signal processing screen.
 */
@HiltViewModel
class ProcessingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val tag = "ProcessingViewModel"
    private val signalPipeline = SignalPipeline(targetSampleRateHz = 100.0, walkingModeEnabled = FeatureFlags.isWalkingModeEnabled())
    
    private val _processedSeries = MutableStateFlow<ProcessedSeries?>(null)
    val processedSeries: StateFlow<ProcessedSeries?> = _processedSeries.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _showRawSignal = MutableStateFlow(false)
    val showRawSignal: StateFlow<Boolean> = _showRawSignal.asStateFlow()
    
    private val _pttResult = MutableStateFlow<PttResult?>(null)
    val pttResult: StateFlow<PttResult?> = _pttResult.asStateFlow()
    
    private val _qualityReport = MutableStateFlow<QualityReport?>(null)
    val qualityReport: StateFlow<QualityReport?> = _qualityReport.asStateFlow()
    
    private val _waveProfile = MutableStateFlow<WaveFeatures.VascularWaveProfile?>(null)
    val waveProfile: StateFlow<WaveFeatures.VascularWaveProfile?> = _waveProfile.asStateFlow()

    private val _wavePatternHint = MutableStateFlow<String?>(null)
    val wavePatternHint: StateFlow<String?> = _wavePatternHint.asStateFlow()

    private val _vascularTrend = MutableStateFlow<VascularTrendSummary?>(null)
    val vascularTrend: StateFlow<VascularTrendSummary?> = _vascularTrend.asStateFlow()

    private val _sessionSummary = MutableStateFlow<SessionSummary?>(null)
    val sessionSummary: StateFlow<SessionSummary?> = _sessionSummary.asStateFlow()

    private val _biomarkers = MutableStateFlow<BiomarkerPanel?>(null)
    val biomarkers: StateFlow<BiomarkerPanel?> = _biomarkers.asStateFlow()

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()
    
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()
    
    private val _simulationConfig = MutableStateFlow(SimulationConfig.realistic())
    val simulationConfig: StateFlow<SimulationConfig> = _simulationConfig.asStateFlow()
    
    private val _performanceReport = MutableStateFlow<PerformanceReport?>(null)
    val performanceReport: StateFlow<PerformanceReport?> = _performanceReport.asStateFlow()
    
    private val clinicianExporter = ClinicianGradeExporter(context)
    private val performanceMetrics = PerformanceMetrics()
    private val trendStore = VascularTrendStore(context)
    
    /**
     * Process recorded frames.
     * 
     * Uses real frames if available, otherwise falls back to synthetic data.
     */
    fun processFrames() {
        viewModelScope.launch {
            _isProcessing.value = true
            performanceMetrics.reset()
            performanceMetrics.recordStart()
            
            try {
                val startTime = System.currentTimeMillis()
                
                // Check for real recording
                val recordingResult = SharedRecordingState.lastRecordingResult.value
                
                val processedSeries = withContext(Dispatchers.Default) {
                    if (recordingResult != null && recordingResult.frames.isNotEmpty()) {
                        // Process real frames
                        val signals = SharedRecordingState.lastProcessedSignals.value
                        processRealFrames(recordingResult, signals)
                    } else {
                        // Fallback to synthetic data
                        generateSyntheticData()
                    }
                }
                
                val processingTime = System.currentTimeMillis() - startTime
                performanceMetrics.recordProcessingTime(processingTime)
                
                _processedSeries.value = processedSeries
                
                // Compute PTT
                if (processedSeries.isValid) {
                    val ptt = withContext(Dispatchers.Default) {
                        PttCalculator.computePtt(processedSeries)
                    }
                    _pttResult.value = ptt
                    
                    // Assess quality and generate suggestions
                    val quality = withContext(Dispatchers.Default) {
                        QualityAssessment.assessQuality(
                            processedSeries = processedSeries,
                            pttResult = ptt,
                            faceMotionScore = null // TODO: Add actual motion tracking
                        )
                    }
                    _qualityReport.value = quality

                    // Compute vascular wave profile (finger primary)
                    val profile = withContext(Dispatchers.Default) {
                        WaveFeatures.computeProfile(processedSeries)
                    }
                    _waveProfile.value = profile

                    // Compute HR & HRV (finger-primary)
                    val hrResult = withContext(Dispatchers.Default) {
                        val peaks = PeakDetect.detectPeaks(processedSeries.fingerSignal, processedSeries.sampleRateHz)
                        HeartRate.computeHeartRate(peaks)
                    }

                    // Biomarker panel (gated by quality)
                    _biomarkers.value = withContext(Dispatchers.Default) {
                        BiomarkerComputer.compute(
                            series = processedSeries,
                            quality = quality,
                            hrBpm = hrResult.hrBpm
                        )
                    }

                    // Compute wave pattern hint under high confidence and good SQI
                    val hint = computeWavePatternHint(profile, quality)
                    _wavePatternHint.value = hint

                    // Update trend store and compute personal Vascular Trend Index
                    _vascularTrend.value = trendStore.maybeRecordAndSummarize(
                        pttMs = ptt.pttMs,
                        pttConfidencePercent = quality.pttConfidence,
                        combinedSqi = quality.combinedScore,
                        profile = profile
                    )

                    // Build and expose session summary
                    _sessionSummary.value = SessionSummary(
                        pttResult = ptt,
                        pttOutput = null, // Not using PttEngine in this flow (legacy?)
                        waveProfile = profile,
                        heartRate = hrResult,
                        faceSQI = quality.faceSQI,
                        fingerSQI = quality.fingerSQI,
                        combinedSQI = quality.combinedScore,
                        wavePatternHint = hint
                    )
                    
                    // Generate performance report
                    _performanceReport.value = performanceMetrics.getReport()
                }
            } catch (e: OutOfMemoryError) {
                Log.e(tag, "Out of memory during processing", e)
                ErrorHandler.handleProcessingError(context, e)
            } catch (e: Exception) {
                Log.e(tag, "Processing failed", e)
                ErrorHandler.handleProcessingError(context, e)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun computeWavePatternHint(
        profile: WaveFeatures.VascularWaveProfile,
        quality: QualityReport
    ): String? {
        // Gate on high quality
        if (quality.combinedScore < 70.0 || quality.pttConfidence < 70.0) return null
        val rise = profile.meanRiseTimeMs ?: return null
        val refl = profile.meanReflectionRatio ?: return null
        // Heuristic, non-diagnostic: shorter rise + lower reflection => more_elastic_like
        return when {
            rise <= 120.0 && refl <= 0.90 -> "more_elastic_like"
            rise >= 180.0 && refl >= 1.05 -> "more_stiff_like"
            else -> "uncertain"
        }
    }
    
    /**
     * Toggle between raw and filtered signal display.
     */
    fun toggleRawFiltered() {
        _showRawSignal.value = !_showRawSignal.value
    }
    
    /**
     * Export session data to ZIP file.
     */
    fun exportData() {
        val series = _processedSeries.value
        val ptt = _pttResult.value
        val quality = _qualityReport.value
        
        if (series == null || ptt == null || quality == null) {
            ErrorHandler.showError(context, "No data to export")
            return
        }
        
        // Check storage availability
        if (!ErrorHandler.hasSufficientStorage(context, requiredMB = 10)) {
            ErrorHandler.showError(context, "Insufficient storage space. Please free up at least 10 MB.")
            return
        }
        
        viewModelScope.launch {
            _isExporting.value = true
            _exportPath.value = null
            
            try {
                val path = withContext(Dispatchers.IO) {
                    // Get recording stats if available
                    val recordingStats = SharedRecordingState.lastRecordingResult.value?.stats
                    val faceFps = recordingStats?.faceStats?.averageFps ?: 30f
                    val fingerFps = recordingStats?.fingerStats?.averageFps ?: 30f
                    
                    // Create metadata
                    val metadata = SessionMetadata(
                        appVersion = "1.0.0",
                        deviceManufacturer = android.os.Build.MANUFACTURER,
                        deviceModel = android.os.Build.MODEL,
                        androidVersion = android.os.Build.VERSION.RELEASE,
                        sessionId = UUID.randomUUID().toString(),
                        startTimestamp = System.currentTimeMillis() - (series.getDurationSeconds() * 1000).toLong(),
                        endTimestamp = System.currentTimeMillis(),
                        durationSeconds = series.getDurationSeconds(),
                        sampleRateHz = series.sampleRateHz,
                        sampleCount = series.getSampleCount(),
                        faceSQI = quality.faceSQI.score,
                        fingerSQI = quality.fingerSQI.score,
                        combinedSQI = quality.combinedScore,
                        pttMs = ptt.pttMs,
                        pttCorrelation = ptt.correlationScore,
                        pttStabilityMs = ptt.stabilityMs,
                        pttConfidence = quality.pttConfidence,
                        pttQuality = ptt.getQuality().name,
                        faceFps = faceFps,
                        fingerFps = fingerFps,
                        driftMsPerSecond = 0.0, // Drift calculation requires clock sync analysis
                        harmonicSummaryFace = series.mainHarmonicsFace,
                        harmonicSummaryFinger = series.mainHarmonicsFinger
                    )
                    
                    // Find peaks for marking
                    val facePeaks = SignalQuality.findPeaks(
                        series.faceSignal,
                        minDistance = (series.sampleRateHz * 0.4).toInt()
                    ).toSet()
                    
                    val fingerPeaks = SignalQuality.findPeaks(
                        series.fingerSignal,
                        minDistance = (series.sampleRateHz * 0.4).toInt()
                    ).toSet()
                    
                    // Create signal data points
                    val faceData = series.timeMillis.indices.map { i ->
                        SignalDataPoint(
                            timeMs = series.timeMillis[i],
                            rawValue = series.rawFaceSignal.getOrNull(i) ?: 0.0,
                            filteredValue = series.faceSignal[i],
                            isPeak = i in facePeaks
                        )
                    }
                    
                    val fingerData = series.timeMillis.indices.map { i ->
                        SignalDataPoint(
                            timeMs = series.timeMillis[i],
                            rawValue = series.rawFingerSignal.getOrNull(i) ?: 0.0,
                            filteredValue = series.fingerSignal[i],
                            isPeak = i in fingerPeaks
                        )
                    }
                    
                    // Create segments (session level)
                    val segment = ExportSegment(
                        startTimeS = 0.0,
                        endTimeS = series.getDurationSeconds(),
                        pttMs = ptt.pttMs,
                        correlation = ptt.correlationScore,
                        sqiFace = quality.faceSQI.score.toDouble(),
                        sqiFinger = quality.fingerSQI.score.toDouble(),
                        pttMeanRaw = ptt.pttMs,
                        pttMeanDenoised = series.pttOutputDenoised?.pttMs,
                        harmonicsFace = series.mainHarmonicsFace,
                        harmonicsFinger = series.mainHarmonicsFinger
                    )
                    val segments = listOf(segment)
                    
                    // Build extras
                    val extras = ExportExtras(
                        vascularWaveProfile = _waveProfile.value?.let { wp ->
                            mapOf(
                                "meanRiseTimeMs" to wp.meanRiseTimeMs,
                                "meanPeakTimeMs" to wp.meanPeakTimeMs,
                                "meanReflectionRatio" to wp.meanReflectionRatio,
                                "dicroticPresenceScore" to wp.dicroticPresenceScore
                            )
                        },
                        vascularTrendSummary = _vascularTrend.value?.let { vt ->
                            mapOf(
                                "index" to vt.index,
                                "deltaPttMs" to vt.deltaPttMs,
                                "deltaRiseTimeMs" to vt.deltaRiseTimeMs,
                                "deltaReflectionRatio" to vt.deltaReflectionRatio
                            )
                        },
                        biomarkerPanel = _biomarkers.value?.let { bm ->
                            mapOf(
                                "hrBpm" to bm.hrBpm,
                                "rmssdMs" to bm.rmssdMs,
                                "sdnnMs" to bm.sdnnMs,
                                "respiratoryModulationDetected" to bm.respiratoryModulationDetected
                            )
                        },
                        reactivityProtocol = null // Optionally set from protocol screen context if available
                    )
                    
                    // Export
                    clinicianExporter.exportSession(
                        metadata = metadata,
                        faceSignal = faceData,
                        fingerSignal = fingerData,
                        thermalTimeline = null,
                        threeAState = null,
                        includeTimeFrequency = FeatureFlags.ENABLE_TF_EXPORT,
                        segments = segments,
                        extras = extras
                    )
                }
                
                _exportPath.value = path
            } catch (e: Exception) {
                Log.e(tag, "Export failed", e)
                ErrorHandler.handleStorageError(context, e)
            } finally {
                _isExporting.value = false
            }
        }
    }
    
    /**
     * Clear export path (after showing toast).
     */
    fun clearExportPath() {
        _exportPath.value = null
    }
    
    /**
     * Update simulation configuration.
     */
    fun updateSimulationConfig(config: SimulationConfig) {
        _simulationConfig.value = config
    }
    
    /**
     * Process real captured frames.
     * 
     * Extracts luma time series from frames and processes through pipeline.
     */
    private fun processRealFrames(
        recordingResult: RecordingResult,
        preProcessedSignals: List<com.vivopulse.signal.ProcessedSignal>? = null
    ): ProcessedSeries {
        // Separate face and finger frames
        val faceFrames = recordingResult.frames.filter { it.source == Source.FACE && it.hasLuma() }
        val fingerFrames = recordingResult.frames.filter { it.source == Source.FINGER && it.hasLuma() }
        
        Log.d(tag, "processRealFrames: ${faceFrames.size} face frames, ${fingerFrames.size} finger frames")
        
        // Check if we have data from at least one channel
        if (faceFrames.isEmpty() && fingerFrames.isEmpty()) {
            Log.w(tag, "No luma data available in any channel, falling back to synthetic")
            return generateSyntheticData()
        }
        
        // For sequential mode: if one channel is empty, generate synthetic for that channel
        val faceData = if (faceFrames.isNotEmpty()) {
            faceFrames.map { frame ->
                TimestampedValue(
                    timestampNs = frame.timestampNs,
                    value = frame.faceLuma ?: 0.0
                )
            }
        } else {
            Log.w(tag, "No face frames, generating synthetic face data")
            // Generate synthetic face data matching finger timeline
            val fingerTimes = fingerFrames.map { it.timestampNs }
            fingerTimes.mapIndexed { i, ts ->
                TimestampedValue(
                    timestampNs = ts,
                    value = 128.0 + 10.0 * kotlin.math.sin(i * 0.1) // Simple sine wave
                )
            }
        }
        
        val fingerData = if (fingerFrames.isNotEmpty()) {
            fingerFrames.map { frame ->
                TimestampedValue(
                    timestampNs = frame.timestampNs,
                    value = frame.fingerLuma ?: 0.0
                )
            }
        } else {
            Log.w(tag, "No finger frames, generating synthetic finger data")
            // Generate synthetic finger data matching face timeline
            val faceTimes = faceFrames.map { it.timestampNs }
            faceTimes.mapIndexed { i, ts ->
                TimestampedValue(
                    timestampNs = ts,
                    value = 128.0 + 10.0 * kotlin.math.sin(i * 0.1 + 0.5) // Offset sine wave
                )
            }
        }
        
        val rawBuffer = RawSeriesBuffer(faceData, fingerData)
        
        // Process through pipeline with pre-processed signals
        return signalPipeline.process(rawBuffer, preProcessedSignals)
    }
    
    /**
     * Generate synthetic PPG data using simulated frame source.
     * 
     * Uses current simulation configuration.
     */
    private fun generateSyntheticData(): ProcessedSeries {
        Log.w(tag, "generateSyntheticData(): using simulated PPG for both channels")
        // Use simulated frame source with current config
        val simulator = SimulatedFrameSource(_simulationConfig.value)
        val rawBuffer = simulator.generateSignals()
        
        // Process through same pipeline as real data
        return signalPipeline.process(rawBuffer)
    }
}
