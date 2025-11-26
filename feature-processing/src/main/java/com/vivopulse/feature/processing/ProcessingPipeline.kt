package com.vivopulse.feature.processing

import com.vivopulse.signal.ProcessedSignal
import com.vivopulse.signal.RawFrameData
import com.vivopulse.signal.Roi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Processing pipeline orchestrator for VivoPulse.
 * Coordinates the flow from camera frames to processed PPG signals.
 */
interface ProcessingPipeline {
    /**
     * Process a stream of camera frames into PPG signals.
     * @param frames Flow of camera frames
     * @return Flow of processed signals
     */
    fun process(frames: Flow<RawFrameData>): Flow<ProcessedSignal>
    
    /**
     * Get the current processing status.
     * @return Processing status
     */
    fun getStatus(): ProcessingStatus
}

/**
 * Processing status.
 */
enum class ProcessingStatus {
    IDLE,
    PROCESSING,
    COMPLETED,
    ERROR
}

/**
 * Default implementation of ProcessingPipeline.
 */
class DefaultProcessingPipeline : ProcessingPipeline {
    private var status = ProcessingStatus.IDLE
    
    // Components
    private val faceMotionIndex = com.vivopulse.feature.processing.motion.FaceMotionIndex()
    private val fingerSaturationIndex = com.vivopulse.feature.processing.motion.FingerSaturationIndex()
    private val imuMotionGate = com.vivopulse.feature.processing.motion.ImuMotionGate()
    private val snrEstimator = com.vivopulse.feature.processing.signal.SnrEstimator()
    private val goodSyncDetector = com.vivopulse.feature.processing.sync.GoodSyncDetector()
    private val pttConsensus = com.vivopulse.feature.processing.ptt.PTTConsensus()
    
    // Buffers
    private val bufferSize = 300 // 10 seconds at 30fps
    private val faceSignalBuffer = ArrayDeque<Double>(bufferSize)
    private val fingerSignalBuffer = ArrayDeque<Double>(bufferSize)
    private val timestampBuffer = ArrayDeque<Long>(bufferSize)
    
    override fun process(frames: Flow<RawFrameData>): Flow<ProcessedSignal> = flow {
        status = ProcessingStatus.PROCESSING
        
        var previousFrame: ByteArray? = null
        var frameCount = 0L
        
        frames.collect { frame ->
            try {
                frameCount++
                
                // 1. Extract Signals
                val faceVal = extractSignal(frame.yPlane, frame.width, frame.faceRoi)
                val fingerVal = extractSignal(frame.yPlane, frame.width, frame.fingerRoi)
                
                // 2. Compute Instantaneous Metrics
                val faceMotion = if (previousFrame != null && frame.faceRoi != null) {
                    faceMotionIndex.computeMotionRms(frame.yPlane, previousFrame!!, frame.width, frame.height) // Simplified, ideally pass ROI
                } else 0.0
                
                val fingerSat = if (frame.fingerRoi != null) {
                    // Extract finger ROI buffer for saturation check
                    // For efficiency, we might just check the whole frame or a center crop if ROI is null
                    // Here we assume we can pass the whole buffer and ROI logic is inside or we crop
                    // FingerSaturationIndex expects full buffer and dims, we should probably crop it or update the index to take ROI
                    // For now, using a simplified approach:
                    fingerSaturationIndex.computeSaturationPct(frame.yPlane, frame.width, frame.height) 
                } else 0.0
                
                previousFrame = frame.yPlane
                
                // 3. Update Buffers
                if (faceSignalBuffer.size >= bufferSize) faceSignalBuffer.removeFirst()
                if (fingerSignalBuffer.size >= bufferSize) fingerSignalBuffer.removeFirst()
                if (timestampBuffer.size >= bufferSize) timestampBuffer.removeFirst()
                
                faceSignalBuffer.add(faceVal)
                fingerSignalBuffer.add(fingerVal)
                timestampBuffer.add(frame.timestampNs)
                
                // 4. Windowed Analysis (every 30 frames ~ 1 sec)
                // In a real app, we might do this less frequently or on a separate coroutine
                if (faceSignalBuffer.size >= bufferSize && frameCount % 30 == 0L) {
                    val fsHz = 30.0 // Estimate from timestamps
                    
                    val faceArray = faceSignalBuffer.toDoubleArray()
                    val fingerArray = fingerSignalBuffer.toDoubleArray()
                    
                    // SNR
                    val faceSnr = snrEstimator.computeSnrDb(faceArray, fsHz)
                    val fingerSnr = snrEstimator.computeSnrDb(fingerArray, fsHz)
                    
                    // SQI
                    val faceSqi = com.vivopulse.feature.processing.sqi.ChannelSqi.computeFaceSqi(faceSnr, faceMotion, 0.0) // IMU placeholder
                    val fingerSqi = com.vivopulse.feature.processing.sqi.ChannelSqi.computeFingerSqi(fingerSnr, fingerSat, 0.0)
                    
                    // Sync & GoodSync
                    val roiStats = com.vivopulse.feature.processing.sync.RoiStats(
                        faceMotionRmsPx = faceMotion,
                        fingerSaturationPct = fingerSat,
                        snrDbFace = faceSnr,
                        snrDbFinger = fingerSnr,
                        imuRmsG = 0.0 // Placeholder
                    )
                    val goodSyncSegments = goodSyncDetector.detectGoodSyncWindows(
                        faceArray, fingerArray, fsHz, null, roiStats
                    )
                    
                    val isGoodSync = goodSyncSegments.isNotEmpty()
                    
                    // Consensus PTT
                    var ptt: Double? = null
                    if (isGoodSync) {
                        // Use the last segment or average
                        val segment = goodSyncSegments.last()
                        // Placeholder HRs for legacy pipeline
                        val consensus = pttConsensus.estimateConsensusPtt(
                            face = faceArray, 
                            finger = fingerArray, 
                            fsHz = fsHz, 
                            hrFaceBpm = 0.0, 
                            hrFingerBpm = 0.0,
                            segment = segment.window
                        )
                        // Check if consensus is reliable (good agreement and enough beats)
                        if (consensus.nBeats >= 3 && consensus.methodAgreeMs <= 20.0) {
                            ptt = consensus.pttMsMedian
                        }
                    }
                    
                    // Emit Result
                    emit(ProcessedSignal(
                        heartRate = 0f, // Placeholder
                        signalQuality = (faceSqi + fingerSqi) / 200f,
                        timestamp = frame.timestampNs,
                        processedData = floatArrayOf(faceVal.toFloat(), fingerVal.toFloat()),
                        faceMotionRms = faceMotion,
                        fingerSaturationPct = fingerSat,
                        snrDb = (faceSnr + fingerSnr) / 2,
                        faceSqi = faceSqi,
                        fingerSqi = fingerSqi,
                        goodSync = isGoodSync,
                        consensusPtt = ptt
                    ))
                } else {
                    // Emit intermediate result
                    emit(ProcessedSignal(
                        heartRate = 0f,
                        signalQuality = 0f,
                        timestamp = frame.timestampNs,
                        processedData = floatArrayOf(faceVal.toFloat(), fingerVal.toFloat()),
                        faceMotionRms = faceMotion,
                        fingerSaturationPct = fingerSat
                    ))
                }
                
            } catch (e: Exception) {
                status = ProcessingStatus.ERROR
                // Log error
            }
        }
        status = ProcessingStatus.COMPLETED
    }
    
    override fun getStatus(): ProcessingStatus = status
    
    private fun extractSignal(yPlane: ByteArray, width: Int, roi: Roi?): Double {
        if (roi == null) return 0.0
        
        var sum = 0.0
        var count = 0
        
        // Simple mean calculation
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                val idx = y * width + x
                if (idx >= 0 && idx < yPlane.size) {
                    sum += (yPlane[idx].toInt() and 0xFF)
                    count++
                }
            }
        }
        
        return if (count > 0) sum / count else 0.0
    }
}

