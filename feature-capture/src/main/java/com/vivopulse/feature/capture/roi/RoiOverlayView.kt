package com.vivopulse.feature.capture.roi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/**
 * Overlay view to visualize ROI on camera preview.
 * 
 * Color coding:
 * - Green: Stable ROI
 * - Yellow: Re-acquiring
 * - Red: Lost
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var faceRoi: FaceRoi? = null
    
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        textSize = 32f
        isAntiAlias = true
    }
    
    /**
     * Update ROI to display.
     */
    fun updateRoi(roi: FaceRoi?) {
        faceRoi = roi
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val roi = faceRoi ?: return
        
        // Set color based on state
        val color = when (roi.state) {
            RoiState.STABLE -> Color.GREEN
            RoiState.RE_ACQUIRING -> Color.YELLOW
            RoiState.LOST -> Color.RED
        }
        
        paint.color = color
        textPaint.color = color
        
        // Scale ROI rect to view size
        val scaledRect = scaleRectToView(roi.rect)
        
        // Draw ROI rectangle
        canvas.drawRect(scaledRect, paint)
        
        // Draw corner markers for better visibility
        drawCornerMarkers(canvas, scaledRect, paint)
        
        // Draw state label
        val stateText = when (roi.state) {
            RoiState.STABLE -> "ROI: Stable"
            RoiState.RE_ACQUIRING -> "ROI: Re-acquiring..."
            RoiState.LOST -> "ROI: Lost"
        }
        
        canvas.drawText(
            stateText,
            scaledRect.left.toFloat(),
            scaledRect.top - 10f,
            textPaint
        )
        
        // Draw confidence indicator
        if (roi.state != RoiState.LOST) {
            val confidenceText = "Conf: ${(roi.confidence * 100).toInt()}%"
            canvas.drawText(
                confidenceText,
                scaledRect.left.toFloat(),
                scaledRect.bottom + 30f,
                textPaint
            )
        }
    }
    
    /**
     * Draw corner markers for better ROI visibility.
     */
    private fun drawCornerMarkers(canvas: Canvas, rect: Rect, paint: Paint) {
        val markerLength = 20f
        
        // Top-left
        canvas.drawLine(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.left + markerLength,
            rect.top.toFloat(),
            paint
        )
        canvas.drawLine(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.left.toFloat(),
            rect.top + markerLength,
            paint
        )
        
        // Top-right
        canvas.drawLine(
            rect.right.toFloat(),
            rect.top.toFloat(),
            rect.right - markerLength,
            rect.top.toFloat(),
            paint
        )
        canvas.drawLine(
            rect.right.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.top + markerLength,
            paint
        )
        
        // Bottom-left
        canvas.drawLine(
            rect.left.toFloat(),
            rect.bottom.toFloat(),
            rect.left + markerLength,
            rect.bottom.toFloat(),
            paint
        )
        canvas.drawLine(
            rect.left.toFloat(),
            rect.bottom.toFloat(),
            rect.left.toFloat(),
            rect.bottom - markerLength,
            paint
        )
        
        // Bottom-right
        canvas.drawLine(
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            rect.right - markerLength,
            rect.bottom.toFloat(),
            paint
        )
        canvas.drawLine(
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            rect.right.toFloat(),
            rect.bottom - markerLength,
            paint
        )
    }
    
    /**
     * Scale ROI rectangle from camera coordinates to view coordinates.
     * 
     * Assumes camera preview is scaled to fill view.
     */
    private fun scaleRectToView(cameraRect: Rect): Rect {
        // For now, assume 1:1 mapping
        // In production, would need to account for preview scaling/cropping
        return cameraRect
    }
}

