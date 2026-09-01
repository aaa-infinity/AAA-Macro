package com.aaa.macro.engine

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.aaa.macro.model.RecordedTouch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Enterprise Macrorify-Style Touch Recorder Engine.
 *
 * Implements:
 * - Real-time gesture recording with relative percentage coordinates (resolution-independent).
 * - Full-screen transparent overlay interceptor with touch passthrough to Clash of Clans.
 * - Millisecond timestamp offsets for precise replay timing.
 * - JSON serialization for attack sequence persistence.
 */
object TouchRecorder {
    private const val TAG = "TouchRecorder"
    private val recordedEvents = mutableListOf<RecordedTouch>()
    private var startTime = 0L
    var isRecording = false
        private set

    private var interceptorView: View? = null
    private var windowManager: WindowManager? = null

    fun startRecording(context: Context? = null, onTouchCaptured: ((Int) -> Unit)? = null) {
        recordedEvents.clear()
        startTime = System.currentTimeMillis()
        isRecording = true
        Log.i(TAG, "Touch recording started.")

        if (context != null) {
            setupOverlayInterceptor(context, onTouchCaptured)
        }
    }

    private fun setupOverlayInterceptor(context: Context, onTouchCaptured: ((Int) -> Unit)? = null) {
        removeOverlayInterceptor()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            this.windowManager = wm

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Full-screen transparent overlay with touch passthrough
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            val view = View(context).apply {
                setOnTouchListener { _, event ->
                    if (isRecording) {
                        val metrics = resources.displayMetrics
                        val w = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
                        val h = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
                        onTouchEvent(event, w, h)
                        onTouchCaptured?.invoke(recordedEvents.size)
                    }
                    false // Return false so touch passes directly through to Clash of Clans!
                }
            }

            wm.addView(view, params)
            interceptorView = view
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up touch interceptor view", e)
        }
    }

    fun removeOverlayInterceptor() {
        if (interceptorView != null && windowManager != null) {
            try {
                windowManager?.removeView(interceptorView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing touch interceptor view", e)
            }
            interceptorView = null
        }
    }

    fun onTouchEvent(event: MotionEvent, screenWidth: Float, screenHeight: Float) {
        if (!isRecording) return
        val offset = System.currentTimeMillis() - startTime
        val safeW = if (screenWidth > 0) screenWidth else 1920f
        val safeH = if (screenHeight > 0) screenHeight else 1080f

        synchronized(recordedEvents) {
            recordedEvents.add(
                RecordedTouch(
                    action = event.actionMasked,
                    xRatio = (event.rawX / safeW).coerceIn(0f, 1f),
                    yRatio = (event.rawY / safeH).coerceIn(0f, 1f),
                    timestampOffset = offset
                )
            )
        }
    }

    fun stopAndSave(file: File): Int {
        isRecording = false
        removeOverlayInterceptor()
        val jsonArray = JSONArray()

        val snapshot: List<RecordedTouch>
        synchronized(recordedEvents) {
            snapshot = ArrayList(recordedEvents)
        }

        snapshot.forEach {
            val obj = JSONObject().apply {
                put("action", it.action)
                put("x", it.xRatio.toDouble())
                put("y", it.yRatio.toDouble())
                put("time", it.timestampOffset)
            }
            jsonArray.put(obj)
        }

        try {
            file.parentFile?.mkdirs()
            file.writeText(jsonArray.toString())
            Log.i(TAG, "Saved ${snapshot.size} recorded touches to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving recorded touches", e)
        }

        return snapshot.size
    }

    fun getRecordedCount(): Int = synchronized(recordedEvents) { recordedEvents.size }
}
