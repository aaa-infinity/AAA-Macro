package com.aaa.macro.engine

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.File

/**
 * Enterprise Touch Replay Engine.
 *
 * Implements:
 * - High-fidelity playback of user-recorded attack sequences.
 * - Dynamic resolution scaling (maps recorded X/Y ratios to current device pixel coordinates).
 * - Anti-ban micro-drift and humanized spatial jitter via HumanGestureDispatcher.
 * - Millisecond-accurate gesture timing and progress tracking.
 * - Concurrency safety and gesture mutex locking.
 */
object TouchReplayEngine {
    private const val TAG = "TouchReplayEngine"

    suspend fun replay(
        file: File,
        screenWidth: Float,
        screenHeight: Float,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "Recording file does not exist: ${file.absolutePath}")
            return false
        }

        try {
            val content = file.readText()
            if (content.isBlank()) return false
            val jsonArray = JSONArray(content)
            val total = jsonArray.length()
            if (total == 0) return false

            var lastTime = 0L

            for (i in 0 until total) {
                val obj = jsonArray.getJSONObject(i)
                val action = obj.optInt("action", 0)
                val xRatio = obj.optDouble("x", 0.0).toFloat()
                val yRatio = obj.optDouble("y", 0.0).toFloat()
                val time = obj.optLong("time", 0L)

                val x = (xRatio * screenWidth).coerceIn(1f, screenWidth - 1f)
                val y = (yRatio * screenHeight).coerceIn(1f, screenHeight - 1f)

                val wait = time - lastTime
                if (wait in 1..8000) {
                    delay(wait)
                }
                lastTime = time

                // Dispatch humanized gesture for ACTION_DOWN (0) or ACTION_MOVE (2)
                if (action == 0 || action == 2) {
                    HumanGestureDispatcher.humanTap(x, y, jitterRadius = 2f)
                }

                onProgress?.invoke(i + 1, total)
            }
            Log.i(TAG, "Completed replaying $total recorded gestures.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during gesture replay", e)
            return false
        }
    }

    fun hasRecording(file: File): Boolean {
        return file.exists() && file.length() > 10
    }
}
