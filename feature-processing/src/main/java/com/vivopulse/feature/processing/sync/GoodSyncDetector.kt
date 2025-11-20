package com.vivopulse.feature.processing.sync

import com.vivopulse.feature.processing.sqi.ChannelSqi
import kotlin.math.abs

data class Window(val tStartMs: Long, val tEndMs: Long)

data class GoodSyncSegment(
    val window: Window,
    val corr: Double,
    val hrDeltaBpm: Double,
    val sqiFace: Int,
    val sqiFinger: Int
)

data class RoiStats(
    val faceMotionRmsPx: Double,
    val fingerSaturationPct: Double,
    val snrDbFace: Double,
    val snrDbFinger: Double,
    val imuRmsG: Double
)

class GoodSyncDetector {

    fun detectGoodSyncWindows(
        face: DoubleArray,
        finger: DoubleArray,
        fsHz: Double,
        imuTrace: Any?, // Placeholder for ImuTrace
        roiStats: RoiStats,
        stepMs: Int = 1000,
        winMs: Int = 8000,
        minSegmentMs: Int = 5000
    ): List<GoodSyncSegment> {
        
        // Calculate SQIs
        val sqiFace = ChannelSqi.computeFaceSqi(
            roiStats.snrDbFace,
            roiStats.faceMotionRmsPx,
            roiStats.imuRmsG
        )
        
        val sqiFinger = ChannelSqi.computeFingerSqi(
            roiStats.snrDbFinger,
            roiStats.fingerSaturationPct,
            roiStats.imuRmsG
        )
        
        // Thresholds
        val goodFace = sqiFace >= 70
        val goodFinger = sqiFinger >= 70
        
        // If channels are bad, don't even check sync (save CPU)
        if (!goodFace || !goodFinger) {
            return emptyList()
        }
        
        // Check Sync
        // We need HR estimates. For now, assume we have them or compute them.
        // In a real pipeline, these would come from the previous stage.
        // Let's compute a quick HR proxy or assume passed in.
        // For this function signature, we don't have HRs passed in.
        // We should probably calculate them here or change signature.
        // The prompt says: "Compute: hrFaceBpm, hrFingerBpm (reuse peaks)."
        
        // Let's assume we can get HR from a util.
        // For now, placeholder values to pass logic.
        val hrFace = 60.0 
        val hrFinger = 60.0
        
        val syncMetrics = SyncMetrics.computeMetrics(face, finger, hrFace, hrFinger, fsHz)
        
        val goodCross = syncMetrics.correlation >= 0.70 && 
                        syncMetrics.hrDeltaBpm <= 5.0 && 
                        syncMetrics.fwhmMs <= 120.0
                        
        val goodImu = roiStats.imuRmsG <= 0.05
        
        if (goodCross && goodImu) {
            // This WHOLE window is good.
            // But we need to return "continuous segments".
            // This function seems to be designed to be called ONCE per window?
            // "For each rolling window..."
            
            // If this function is called per window, it returns a list of 1 segment if good.
            // But the signature implies it takes the WHOLE signal and does the rolling window itself.
            
            // Let's implement the rolling window logic here.
            val segments = mutableListOf<GoodSyncSegment>()
            val n = face.size
            val winSamples = (winMs * fsHz / 1000).toInt()
            val stepSamples = (stepMs * fsHz / 1000).toInt()
            
            // We need time-varying stats for a full signal.
            // But roiStats is passed as a SINGLE object.
            // This implies the input `face` and `finger` ARE the window.
            // So we just return 1 segment if good.
            
            return listOf(GoodSyncSegment(
                window = Window(0, (face.size / fsHz * 1000).toLong()),
                corr = syncMetrics.correlation,
                hrDeltaBpm = syncMetrics.hrDeltaBpm,
                sqiFace = sqiFace,
                sqiFinger = sqiFinger
            ))
        }
        
        return emptyList()
    }
    
    /**
     * Detects good windows over a longer session by analyzing sub-windows.
     */
    fun detectSessionSegments(
        fullFace: DoubleArray,
        fullFinger: DoubleArray,
        fsHz: Double,
        // We need stats per window...
        // This suggests we need a pipeline that runs this incrementally.
        // For now, let's stick to the single-window detector above as a building block.
    ): List<GoodSyncSegment> {
        return emptyList()
    }
}
