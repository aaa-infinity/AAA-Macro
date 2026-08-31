package com.aaa.macro.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Random
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2026 Optimized Anti-Detection Gesture Engine.
 *
 * Implements:
 * - Micro-drift touch paths (1-2px sub-pixel drift simulating natural fingertip flesh compression)
 * - Gaussian spatial jitter (\sigma = 2.5)
 * - Hero Equipment Staggering (sequential multi-touch deployment loops targeting Hero deployment & equipment ability triggers)
 * - Multi-touch simultaneous troop funneling
 * - Camera Calibration (two-finger pinch zoom & corner alignment drag)
 * - Non-blocking stochastic delays
 */
class HumanGestureDispatcher(
    private val serviceProvider: () -> AccessibilityService?
) {
    companion object {
        private const val TAG = "HumanGesture"
    }

    private val random = Random()

    /**
     * Executes a humanized tap with Gaussian spatial jitter and Touch Micro-Drift physics.
     *
     * @param x Target center X coordinate.
     * @param y Target center Y coordinate.
     * @param jitterRadius Maximum boundary radius for Gaussian perturbation.
     * @return True if gesture was successfully dispatched and completed.
     */
    suspend fun humanTap(
        x: Float,
        y: Float,
        jitterRadius: Float = 6f
    ): Boolean {
        val service = serviceProvider()
        if (service == null) {
            Log.w(TAG, "AccessibilityService unavailable. Cannot dispatch tap.")
            return false
        }

        val gaussianX = (random.nextGaussian() * 2.5).toFloat().coerceIn(-jitterRadius, jitterRadius)
        val gaussianY = (random.nextGaussian() * 2.5).toFloat().coerceIn(-jitterRadius, jitterRadius)

        val startX = (x + gaussianX).coerceAtLeast(1f)
        val startY = (y + gaussianY).coerceAtLeast(1f)

        // Micro-drift physics: 1.0px to 2.2px displacement during touch contact
        val driftAngle = random.nextDouble() * 2.0 * Math.PI
        val driftDist = (1.0 + random.nextDouble() * 1.2).toFloat()
        val endX = (startX + driftDist * cos(driftAngle).toFloat()).coerceAtLeast(1f)
        val endY = (startY + driftDist * sin(driftAngle).toFloat()).coerceAtLeast(1f)

        val holdDuration = (40L + random.nextInt(41)).coerceIn(40L, 80L)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, holdDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSuspending(service, gesture)
    }

    /**
     * Executes multi-touch simultaneous troop deployment across multiple coordinate paths.
     */
    suspend fun humanMultiTouchDeploy(points: List<PointF>): Boolean {
        val service = serviceProvider()
        if (service == null || points.isEmpty()) {
            return false
        }

        val builder = GestureDescription.Builder()
        val safePoints = points.take(10)

        for (pt in safePoints) {
            val gaussianX = (random.nextGaussian() * 2.0).toFloat().coerceIn(-4f, 4f)
            val gaussianY = (random.nextGaussian() * 2.0).toFloat().coerceIn(-4f, 4f)
            val sx = (pt.x + gaussianX).coerceAtLeast(1f)
            val sy = (pt.y + gaussianY).coerceAtLeast(1f)

            val driftDist = (1.0 + random.nextDouble()).toFloat()
            val driftAngle = random.nextDouble() * 2.0 * Math.PI
            val ex = (sx + driftDist * cos(driftAngle).toFloat()).coerceAtLeast(1f)
            val ey = (sy + driftDist * sin(driftAngle).toFloat()).coerceAtLeast(1f)

            val holdDuration = (45L + random.nextInt(35)).coerceIn(45L, 80L)
            val startTime = random.nextInt(15).toLong()

            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }

            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, holdDuration))
        }

        val gesture = builder.build()
        return dispatchGestureSuspending(service, gesture)
    }

    /**
     * Staggered Hero & Equipment Ability Trigger:
     * Selects Hero, places Hero onto the battlefield, and triggers equipment ability after designated delay.
     */
    suspend fun deployHeroWithEquipment(
        heroSlotX: Float,
        heroSlotY: Float,
        dropX: Float,
        dropY: Float,
        abilityTriggerDelayMs: Long = 8000L
    ): Boolean {
        // 1. Select Hero Slot
        humanTap(heroSlotX, heroSlotY, jitterRadius = 4f)
        humanSleep(180L, 30L)

        // 2. Drop Hero
        val dropped = humanTap(dropX, dropY, jitterRadius = 10f)
        if (!dropped) return false

        // 3. Staggered delay before triggering active Hero Equipment ability
        if (abilityTriggerDelayMs > 0) {
            humanSleep(abilityTriggerDelayMs, 500L)
            // Tap hero slot again to activate equipment ability
            humanTap(heroSlotX, heroSlotY, jitterRadius = 4f)
        }
        return true
    }

    /**
     * Pinch-to-zoom-out camera calibration.
     */
    suspend fun pinchZoomOut(
        centerX: Float = 960f,
        centerY: Float = 540f,
        span: Float = 350f,
        durationMs: Long = 400L
    ): Boolean {
        val service = serviceProvider() ?: return false

        val path1 = Path().apply {
            moveTo(centerX - span, centerY - span * 0.5f)
            lineTo(centerX - 60f, centerY - 30f)
        }

        val path2 = Path().apply {
            moveTo(centerX + span, centerY + span * 0.5f)
            lineTo(centerX + 60f, centerY + 30f)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        return dispatchGestureSuspending(service, gesture)
    }

    /**
     * Smooth Bézier curve swipe.
     */
    suspend fun humanSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 350L
    ): Boolean {
        val service = serviceProvider() ?: return false

        val actualDuration = (durationMs + random.nextInt(60) - 30).coerceAtLeast(150L)
        val dx = endX - startX
        val dy = endY - startY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist < 5f) {
            return humanTap(startX, startY)
        }

        val perpX = -dy / dist
        val perpY = dx / dist

        val curveMagnitude1 = ((random.nextGaussian() * 16.0).toFloat()).coerceIn(-40f, 40f)
        val curveMagnitude2 = ((random.nextGaussian() * 16.0).toFloat()).coerceIn(-40f, 40f)

        val p1x = startX + dx * 0.3f + perpX * curveMagnitude1
        val p1y = startY + dy * 0.3f + perpY * curveMagnitude1

        val p2x = startX + dx * 0.7f + perpX * curveMagnitude2
        val p2y = startY + dy * 0.7f + perpY * curveMagnitude2

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(p1x, p1y, p2x, p2y, endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, actualDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGestureSuspending(service, gesture)
    }

    suspend fun humanSleep(baseMs: Long, varianceMs: Long = 50L) {
        val variance = if (varianceMs > 0) {
            (random.nextLong() % (varianceMs * 2 + 1)) - varianceMs
        } else {
            0L
        }
        val totalDelay = (baseMs + variance).coerceAtLeast(10L)
        delay(totalDelay)
    }

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
                    Log.w(TAG, "Gesture dispatch cancelled by system.")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false.")
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
