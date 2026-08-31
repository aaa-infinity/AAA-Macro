package com.aaa.macro.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Random
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Enterprise Anti-Detection Gesture Engine.
 *
 * Implements:
 * - GestureMutex: State-locked semaphore preventing overlapping dispatchGesture() collisions.
 * - Gaussian Spatial Perturbation (\sigma = 2.0).
 * - Touch Micro-Drift Physics (1.0px to 2.2px displacement during touch contact).
 * - Multi-finger concurrent stroke builder.
 * - Cubic Bézier swipe paths.
 */
class HumanGestureDispatcher(
    private val serviceProvider: () -> AccessibilityService?
) {
    companion object {
        private const val TAG = "HumanGesture"
    }

    private val random = Random()
    private val gestureMutex = Mutex()

    /**
     * Executes a humanized tap with Gaussian spatial jitter and Touch Micro-Drift physics.
     */
    suspend fun humanTap(
        x: Float,
        y: Float,
        jitterRadius: Float = 5f
    ): Boolean = gestureMutex.withLock {
        val service = serviceProvider()
        if (service == null) {
            Log.w(TAG, "AccessibilityService unavailable. Cannot dispatch tap.")
            return@withLock false
        }

        val gaussianX = (random.nextGaussian() * 2.0).toFloat().coerceIn(-jitterRadius, jitterRadius)
        val gaussianY = (random.nextGaussian() * 2.0).toFloat().coerceIn(-jitterRadius, jitterRadius)

        val startX = (x + gaussianX).coerceAtLeast(1f)
        val startY = (y + gaussianY).coerceAtLeast(1f)

        // Micro-drift physics: 1.0px to 2.0px displacement during touch contact
        val driftAngle = random.nextDouble() * 2.0 * Math.PI
        val driftDist = (1.0 + random.nextDouble() * 1.0).toFloat()
        val endX = (startX + driftDist * cos(driftAngle).toFloat()).coerceAtLeast(1f)
        val endY = (startY + driftDist * sin(driftAngle).toFloat()).coerceAtLeast(1f)

        val holdDuration = (45L + random.nextInt(36)).coerceIn(45L, 80L)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, holdDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return@withLock dispatchGestureSuspending(service, gesture)
    }

    /**
     * Executes concurrent multi-touch deployment across multiple coordinate points.
     */
    suspend fun humanMultiTouchDeploy(points: List<PointF>): Boolean = gestureMutex.withLock {
        val service = serviceProvider()
        if (service == null || points.isEmpty()) {
            return@withLock false
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
        return@withLock dispatchGestureSuspending(service, gesture)
    }

    /**
     * Deploys Hero unit and triggers active equipment ability after designated delay.
     */
    suspend fun deployHeroWithEquipment(
        heroSlotX: Float,
        heroSlotY: Float,
        dropX: Float,
        dropY: Float,
        abilityTriggerDelayMs: Long = 0L
    ): Boolean {
        // 1. Select Hero Slot
        val selected = humanTap(heroSlotX, heroSlotY, jitterRadius = 4f)
        if (!selected) return false
        humanSleep(180L, 30L)

        // 2. Drop Hero at funnel coordinate
        val dropped = humanTap(dropX, dropY, jitterRadius = 10f)
        if (!dropped) return false

        // 3. Staggered equipment ability trigger
        if (abilityTriggerDelayMs > 0) {
            humanSleep(abilityTriggerDelayMs, 500L)
            humanTap(heroSlotX, heroSlotY, jitterRadius = 4f)
        }
        return true
    }

    /**
     * Pinch-to-zoom camera reset calibration.
     */
    suspend fun pinchZoomOut(
        centerX: Float = 960f,
        centerY: Float = 540f,
        span: Float = 350f,
        durationMs: Long = 400L
    ): Boolean = gestureMutex.withLock {
        val service = serviceProvider() ?: return@withLock false

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

        return@withLock dispatchGestureSuspending(service, gesture)
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
    ): Boolean = gestureMutex.withLock {
        val service = serviceProvider() ?: return@withLock false

        val actualDuration = (durationMs + random.nextInt(60) - 30).coerceAtLeast(150L)
        val dx = endX - startX
        val dy = endY - startY
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (dist < 5f) {
            val path = Path().apply { moveTo(startX, startY); lineTo(startX + 1f, startY + 1f) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50L)
            return@withLock dispatchGestureSuspending(service, GestureDescription.Builder().addStroke(stroke).build())
        }

        val perpX = -dy / dist
        val perpY = dx / dist

        val curve1 = ((random.nextGaussian() * 14.0).toFloat()).coerceIn(-35f, 35f)
        val curve2 = ((random.nextGaussian() * 14.0).toFloat()).coerceIn(-35f, 35f)

        val p1x = startX + dx * 0.3f + perpX * curve1
        val p1y = startY + dy * 0.3f + perpY * curve1

        val p2x = startX + dx * 0.7f + perpX * curve2
        val p2y = startY + dy * 0.7f + perpY * curve2

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(p1x, p1y, p2x, p2y, endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, actualDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return@withLock dispatchGestureSuspending(service, gesture)
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
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture dispatch cancelled by system.")
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false.")
                if (continuation.isActive) continuation.resume(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during dispatchGesture", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
