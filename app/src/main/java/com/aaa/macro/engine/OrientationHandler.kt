package com.aaa.macro.engine

import android.content.Context
import android.graphics.PointF
import android.view.Surface
import android.view.WindowManager

/**
 * Landscape Coordinate Matrix & Orientation Engine.
 *
 * Dynamically handles coordinate transformations across device orientations:
 * - Surface.ROTATION_90 (Standard Landscape)
 * - Surface.ROTATION_270 (Reverse Landscape)
 * - Surface.ROTATION_0 / ROTATION_180 (Portrait Fallback)
 */
class OrientationHandler(
    private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val currentRotation: Int
        get() = @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation

    val isLandscape: Boolean
        get() {
            val rot = currentRotation
            return rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        }

    /**
     * Transforms landscape canonical coordinates to current surface orientation.
     *
     * @param point Canonical landscape coordinate.
     * @param displayWidth Current physical display width.
     * @param displayHeight Current physical display height.
     * @return Physical device point adjusted for active display rotation.
     */
    fun transformToPhysical(
        point: PointF,
        displayWidth: Int,
        displayHeight: Int
    ): PointF {
        return when (currentRotation) {
            Surface.ROTATION_90 -> {
                // Standard landscape orientation
                PointF(point.x, point.y)
            }
            Surface.ROTATION_270 -> {
                // Reverse landscape orientation (180 deg flip of landscape coordinates)
                PointF(displayWidth - point.x, displayHeight - point.y)
            }
            Surface.ROTATION_180 -> {
                // Reverse portrait
                PointF(point.y, displayHeight - point.x)
            }
            else -> {
                // Normal portrait
                PointF(point.x, point.y)
            }
        }
    }

    /**
     * Normalizes physical screen touch coordinates back into canonical landscape coordinates.
     */
    fun normalizeToLandscape(
        physicalPoint: PointF,
        displayWidth: Int,
        displayHeight: Int
    ): PointF {
        return when (currentRotation) {
            Surface.ROTATION_90 -> {
                PointF(physicalPoint.x, physicalPoint.y)
            }
            Surface.ROTATION_270 -> {
                PointF(displayWidth - physicalPoint.x, displayHeight - physicalPoint.y)
            }
            Surface.ROTATION_180 -> {
                PointF(displayHeight - physicalPoint.y, physicalPoint.x)
            }
            else -> {
                PointF(physicalPoint.x, physicalPoint.y)
            }
        }
    }
}
