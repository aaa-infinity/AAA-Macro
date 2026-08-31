package com.aaa.macro.engine

import android.graphics.PointF
import android.graphics.Rect
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Resolution Normalization Engine:
 * Maps coordinates and template sizes from reference 1920x1080 landscape resolution
 * to arbitrary target device resolutions and aspect ratios dynamically.
 */
class ResolutionScaler(
    var screenWidth: Int = 1920,
    var screenHeight: Int = 1080
) {
    companion object {
        const val BASE_WIDTH = 1920f
        const val BASE_HEIGHT = 1080f
    }

    val scaleX: Float
        get() = screenWidth.toFloat() / BASE_WIDTH

    val scaleY: Float
        get() = screenHeight.toFloat() / BASE_HEIGHT

    /**
     * Updates display dimensions whenever orientation or screen size changes.
     */
    fun updateDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            this.screenWidth = width
            this.screenHeight = height
        }
    }

    /**
     * Converts a base 1920x1080 point into actual device pixel coordinates.
     */
    fun scaleX(x: Float): Float = x * scaleX
    fun scaleY(y: Float): Float = y * scaleY

    fun scalePoint(x: Float, y: Float): PointF {
        return PointF(x * scaleX, y * scaleY)
    }

    fun scalePoint(point: Point): Point {
        return Point(point.x * scaleX, point.y * scaleY)
    }

    /**
     * Scales a base 1920x1080 bounding box into actual device pixels for OCR and region matching.
     */
    fun scaleRect(baseLeft: Int, baseTop: Int, baseRight: Int, baseBottom: Int): Rect {
        val left = (baseLeft * scaleX).toInt().coerceIn(0, screenWidth - 1)
        val top = (baseTop * scaleY).toInt().coerceIn(0, screenHeight - 1)
        val right = (baseRight * scaleX).toInt().coerceIn(left + 1, screenWidth)
        val bottom = (baseBottom * scaleY).toInt().coerceIn(top + 1, screenHeight)
        return Rect(left, top, right, bottom)
    }

    /**
     * Dynamically resizes a reference OpenCV template Mat to match current screen scale factor.
     */
    fun scaleTemplate(srcMat: Mat): Mat {
        val avgScale = (scaleX + scaleY) / 2.0
        if (Math.abs(avgScale - 1.0) < 0.02) {
            return srcMat.clone()
        }
        val targetWidth = (srcMat.cols() * avgScale).toInt().coerceAtLeast(4)
        val targetHeight = (srcMat.rows() * avgScale).toInt().coerceAtLeast(4)
        val resizedMat = Mat()
        Imgproc.resize(
            srcMat,
            resizedMat,
            Size(targetWidth.toDouble(), targetHeight.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_AREA
        )
        return resizedMat
    }
}
