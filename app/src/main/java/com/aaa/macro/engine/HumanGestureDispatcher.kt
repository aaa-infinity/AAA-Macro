package com.aaa.macro.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Random
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Production-Grade Anti-Detection Gesture Engine.
 *
 * Implements non-linear stochastic input simulation:
 * - Gaussian distribution targeting (Box-Muller transform / nextGaussian)
 * - Micro-jitter on tap coordinates
 * - Variable press duration (40ms - 85ms)
 * - Cubic Bézier curve trajectory interpolation for swipes
 * - Stochastic non-blocking delays with randomized variance
 */
class HumanGestureDispatcher(
    private val serviceProvider: () -> AccessibilityService?
) {
    companion object {
        private const val TAG = "HumanGesture"
    }

    private val random = Random()

    /**
     * Executes a humanized tap at target (x, y) with Gaussian jitter and variable hold duration.
     *
     * @param x Target center X coordinate.
     * @param y Target center Y coordinate.
     * @param jitterRadius Maximum boundary radius for Gaussian jitter.
     * @return True if gesture was successfully dispatched and completed by system.
     */
    suspend fun humanTap(
        x: Float,
        y: Float,
        jitterRadius: Float = 6f
    ): Boolean {
        val service = serviceProvider()
        if (service == null) {
            Log.w(TAG, "AccessibilityService is not available. Cannot dispatch tap.")
            return false
        }

        // Gaussian perturbation around mean=0, stdDev=2.5
        val gaussianX = (random.nextGaussian() * 2.5).toFloat()
        val gaussianY = (random.nextGaussian() * 2.5).toFloat()

        // Clamp offset to jitterRadius
        val clampedX = gaussianX.coerceIn(-jitterRadius, jitterRadius)
        val clampedY = gaussianY.coerceIn(-jitterRadius, jitterRadius)

        val targetX = (x + clampedX).coerceAtLeast(1f)
        val targetY = (y + clampedY).coerceAtLeast(1f)

        // Randomized human tap hold duration (40ms - 85ms)
        val holdDuration = (40L + random.nextInt(46)).coerceIn(40L, 85L)

        val path = Path().apply {
            moveTo(targetX, targetY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, holdDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSuspending(service, gesture)
    }

    /**
     * Executes a humanized swipe using a cubic Bézier curve with randomized control anchors.
     *
     * @param startX Starting X coordinate.
     * @param startY Starting Y coordinate.
     * @param endX Ending X coordinate.
     * @param endY Ending Y coordinate.
     * @param durationMs Base duration of the swipe gesture in milliseconds.
     * @return True if gesture was successfully dispatched and completed.
     */
    suspend fun humanSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val service = serviceProvider()
        if (service == null) {
            Log.w(TAG, "AccessibilityService is not available. Cannot dispatch swipe.")
            return false
        }

        val actualDuration = (durationMs + random.nextInt(60) - 30).coerceAtLeast(150L)

        // Calculate vector and perpendicular direction
        val dx = endX - startX
        val dy = endY - startY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist < 5f) {
            return humanTap(startX, startY)
        }

        val perpX = -dy / dist
        val perpY = dx / dist

        // Random curvature amplitude (deflection)
        val curveMagnitude1 = ((random.nextGaussian() * 18.0).toFloat()).coerceIn(-45f, 45f)
        val curveMagnitude2 = ((random.nextGaussian() * 18.0).toFloat()).coerceIn(-45f, 45f)

        // Control point 1 (~30% along the path)
        val p1x = startX + dx * 0.3f + perpX * curveMagnitude1
        val p1y = startY + dy * 0.3f + perpY * curveMagnitude1

        // Control point 2 (~70% along the path)
        val p2x = startX + dx * 0.7f + perpX * curveMagnitude2
        val p2y = startY + dy * 0.7f + perpY * curveMagnitude2

        // Build smooth Cubic Bézier Path
        val path = Path()
        path.moveTo(startX, startY)
        path.cubicTo(p1x, p1y, p2x, p2y, endX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, actualDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSuspending(service, gesture)
    }

    /**
     * Non-blocking stochastic sleep using coroutines.
     *
     * @param baseMs Base delay in milliseconds.
     * @param varianceMs Random deviation (+/- variance).
     */
    suspend fun humanSleep(baseMs: Long, varianceMs: Long = 50L) {
        val variance = if (varianceMs > 0) {
            (random.nextLong() % (varianceMs * 2 + 1)) - varianceMs
        } else {
            0L
        }
        val totalDelay = (baseMs + variance).coerceAtLeast(10L)
        delay(totalDelay)
    }

    /**
     * Internal coroutine wrapper around Android AccessibilityService.dispatchGesture().
     */
    private suspend fun dispatchGestureSuspending(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture dispatch was cancelled by system.")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false immediately.")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during dispatchGesture", e)
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
    }
}
