package com.aaa.macro.engine

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics

/**
 * Display Notch & Cutout Safe-Area Manager.
 *
 * Dynamically queries DisplayCutout safe insets (safeInsetLeft, safeInsetRight)
 * across landscape orientations (ROTATION_90 and ROTATION_270) to prevent
 * touch and vision template misalignment caused by punch-hole cameras or rounded corners.
 */
class CutoutManager(private val context: Context) {

    companion object {
        private const val TAG = "CutoutManager"
    }

    var safeInsetLeft: Int = 0
        private set
    var safeInsetRight: Int = 0
        private set
    var safeInsetTop: Int = 0
        private set
    var safeInsetBottom: Int = 0
        private set

    var currentRotation: Int = Surface.ROTATION_90
        private set

    /**
     * Updates safe insets based on current WindowMetrics or WindowManager insets.
     */
    fun updateCutoutInsets(windowManager: WindowManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
                val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars()
                )
                val cutout = windowMetrics.windowInsets.displayCutout

                if (cutout != null) {
                    safeInsetLeft = cutout.safeInsetLeft
                    safeInsetRight = cutout.safeInsetRight
                    safeInsetTop = cutout.safeInsetTop
                    safeInsetBottom = cutout.safeInsetBottom
                } else {
                    safeInsetLeft = insets.left
                    safeInsetRight = insets.right
                    safeInsetTop = insets.top
                    safeInsetBottom = insets.bottom
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                currentRotation = display.rotation
            }
            Log.i(TAG, "Cutout Insets updated: L=$safeInsetLeft, R=$safeInsetRight, T=$safeInsetTop, B=$safeInsetBottom")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query DisplayCutout insets: ${e.message}")
        }
    }

    /**
     * Adjusts a screen coordinate by adding the safe cutout insets.
     */
    fun adjustCoordinate(point: PointF): PointF {
        return PointF(
            point.x + safeInsetLeft,
            point.y + safeInsetTop
        )
    }

    /**
     * Adjusts a Region-of-Interest (ROI) Rect by offsetting for notch insets.
     */
    fun adjustRect(rect: Rect): Rect {
        return Rect(
            rect.left + safeInsetLeft,
            rect.top + safeInsetTop,
            rect.right + safeInsetLeft,
            rect.bottom + safeInsetTop
        )
    }

    /**
     * Reverses cutout offset for canonical coordinate storage.
     */
    fun unadjustCoordinate(point: PointF): PointF {
        return PointF(
            (point.x - safeInsetLeft).coerceAtLeast(0f),
            (point.y - safeInsetTop).coerceAtLeast(0f)
        )
    }
}
