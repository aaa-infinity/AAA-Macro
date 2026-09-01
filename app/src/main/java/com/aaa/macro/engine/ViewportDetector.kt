package com.aaa.macro.engine

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log

/**
 * Enterprise Viewport & Ultra-Wide Pillarbox Offset Detector.
 *
 * Automatically detects ultra-wide aspect ratios (18:9, 19.5:9, 20:9, 21:9)
 * and calculates exact pillarbox (horizontal) or letterbox (vertical) black-bar margins.
 * Keeps touch coordinates and vision templates aligned with the game's actual render canvas.
 */
class ViewportDetector(
    private var screenWidth: Int = 1920,
    private var screenHeight: Int = 1080,
    private val targetAspectRatio: Float = 16f / 9f // 1.7778
) {
    companion object {
        private const val TAG = "ViewportDetector"
    }

    var pillarboxOffsetLeft: Float = 0f
        private set
    var pillarboxOffsetRight: Float = 0f
        private set
    var letterboxOffsetTop: Float = 0f
        private set
    var letterboxOffsetBottom: Float = 0f
        private set

    var viewportWidth: Float = screenWidth.toFloat()
        private set
    var viewportHeight: Float = screenHeight.toFloat()
        private set

    init {
        recalculateViewport(screenWidth, screenHeight)
    }

    /**
     * Recalculates viewport bounds and margin offsets for current display dimensions.
     */
    fun recalculateViewport(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height

        val currentAspectRatio = width.toFloat() / height.toFloat()

        if (currentAspectRatio > targetAspectRatio) {
            // Ultra-wide display (Pillarbox on Left & Right)
            val effectiveWidth = height * targetAspectRatio
            val totalHorizontalMargin = width - effectiveWidth
            pillarboxOffsetLeft = totalHorizontalMargin / 2f
            pillarboxOffsetRight = pillarboxOffsetLeft
            letterboxOffsetTop = 0f
            letterboxOffsetBottom = 0f

            viewportWidth = effectiveWidth
            viewportHeight = height.toFloat()
        } else if (currentAspectRatio < targetAspectRatio) {
            // Taller display (Letterbox on Top & Bottom)
            val effectiveHeight = width / targetAspectRatio
            val totalVerticalMargin = height - effectiveHeight
            letterboxOffsetTop = totalVerticalMargin / 2f
            letterboxOffsetBottom = letterboxOffsetTop
            pillarboxOffsetLeft = 0f
            pillarboxOffsetRight = 0f

            viewportWidth = width.toFloat()
            viewportHeight = effectiveHeight
        } else {
            // Exact 16:9 Match
            pillarboxOffsetLeft = 0f
            pillarboxOffsetRight = 0f
            letterboxOffsetTop = 0f
            letterboxOffsetBottom = 0f
            viewportWidth = width.toFloat()
            viewportHeight = height.toFloat()
        }

        Log.i(TAG, "Viewport calculated: AR=%.3f, Margins (L=%.1f, R=%.1f, T=%.1f, B=%.1f), Canvas=%.0fx%.0f"
            .format(currentAspectRatio, pillarboxOffsetLeft, pillarboxOffsetRight, letterboxOffsetTop, letterboxOffsetBottom, viewportWidth, viewportHeight))
    }

    /**
     * Translates a canvas coordinate (0..1920, 0..1080) to real display coordinates
     * accounting for ultra-wide pillarbox/letterbox black bars.
     */
    fun mapToScreen(canvasPoint: PointF): PointF {
        val scaleX = viewportWidth / 1920f
        val scaleY = viewportHeight / 1080f

        val mappedX = pillarboxOffsetLeft + (canvasPoint.x * scaleX)
        val mappedY = letterboxOffsetTop + (canvasPoint.y * scaleY)

        return PointF(mappedX, mappedY)
    }

    /**
     * Translates a canvas Rect (0..1920, 0..1080) to real display Rect.
     */
    fun mapRectToScreen(canvasRect: Rect): Rect {
        val scaleX = viewportWidth / 1920f
        val scaleY = viewportHeight / 1080f

        val left = (pillarboxOffsetLeft + (canvasRect.left * scaleX)).toInt()
        val top = (letterboxOffsetTop + (canvasRect.top * scaleY)).toInt()
        val right = (pillarboxOffsetLeft + (canvasRect.right * scaleX)).toInt()
        val bottom = (letterboxOffsetTop + (canvasRect.bottom * scaleY)).toInt()

        return Rect(left, top, right, bottom)
    }
}
