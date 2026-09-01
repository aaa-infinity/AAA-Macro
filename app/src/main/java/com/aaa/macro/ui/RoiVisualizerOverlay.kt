package com.aaa.macro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Lightweight Visual Alignment Debug Overlay.
 *
 * Renders non-touchable bounding box rectangles showing exact scanning ROIs:
 * 1. Top-Left Loot ROI (Amber)
 * 2. Bottom-Left Attack / Return Village ROI (Emerald Green)
 * 3. Bottom-Right Next / Surrender ROI (Cyan Blue)
 */
class RoiVisualizerOverlay(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "RoiVisualizerOverlay"
    }

    private val overlayView = object : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            isFakeBoldText = true
            color = Color.WHITE
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            // 1. Top-Left Loot ROI
            val lootRect = RectF(0f, 0f, w * 0.35f, h * 0.35f)
            fillPaint.color = Color.argb(40, 245, 158, 11) // Amber tint
            strokePaint.color = Color.rgb(245, 158, 11)
            canvas.drawRect(lootRect, fillPaint)
            canvas.drawRect(lootRect, strokePaint)
            canvas.drawText("🎯 Loot OCR ROI", lootRect.left + 20f, lootRect.bottom - 20f, textPaint)

            // 2. Bottom-Left Attack ROI
            val atkRect = RectF(0f, h * 0.70f, w * 0.25f, h * 0.98f)
            fillPaint.color = Color.argb(40, 16, 185, 129) // Emerald tint
            strokePaint.color = Color.rgb(16, 185, 129)
            canvas.drawRect(atkRect, fillPaint)
            canvas.drawRect(atkRect, strokePaint)
            canvas.drawText("⚔️ Attack ROI", atkRect.left + 20f, atkRect.top + 40f, textPaint)

            // 3. Bottom-Right Next / Surrender ROI
            val nextRect = RectF(w * 0.70f, h * 0.70f, w, h * 0.98f)
            fillPaint.color = Color.argb(40, 6, 182, 212) // Cyan tint
            strokePaint.color = Color.rgb(6, 182, 212)
            canvas.drawRect(nextRect, fillPaint)
            canvas.drawRect(nextRect, strokePaint)
            canvas.drawText("⏩ Next/Surrender ROI", nextRect.left + 20f, nextRect.top + 40f, textPaint)
        }
    }

    private val params = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
    }

    private var isAttached = false

    fun setVisible(visible: Boolean) {
        if (visible && !isAttached) {
            attach()
        } else if (!visible && isAttached) {
            detach()
        }
    }

    private fun attach() {
        try {
            if (overlayView.windowToken == null) {
                windowManager.addView(overlayView, params)
                isAttached = true
                Log.i(TAG, "RoiVisualizerOverlay attached to WindowManager.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching RoiVisualizerOverlay", e)
        }
    }

    fun detach() {
        try {
            if (overlayView.windowToken != null) {
                windowManager.removeView(overlayView)
                isAttached = false
                Log.i(TAG, "RoiVisualizerOverlay detached from WindowManager.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching RoiVisualizerOverlay", e)
        }
    }
}
