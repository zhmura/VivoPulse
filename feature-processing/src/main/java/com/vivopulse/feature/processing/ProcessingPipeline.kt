package com.vivopulse.feature.processing

import com.vivopulse.signal.ProcessedSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Raw frame data for processing.
 * Lightweight representation to avoid circular dependencies.
 */
data class RawFrameData(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray
)

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
    
    override fun process(frames: Flow<RawFrameData>): Flow<ProcessedSignal> = flow {
        status = ProcessingStatus.PROCESSING
        // TODO: Implement processing pipeline
        // 1. Extract ROI from camera frames
        // 2. Extract PPG signal from pixel intensities
        // 3. Apply signal processing (filtering, FFT, etc.)
        // 4. Calculate heart rate and other metrics
        status = ProcessingStatus.COMPLETED
    }
    
    override fun getStatus(): ProcessingStatus = status
}

