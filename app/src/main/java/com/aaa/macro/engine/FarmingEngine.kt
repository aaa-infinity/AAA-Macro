package com.aaa.macro.engine

import android.graphics.PointF
import android.util.Log
import com.aaa.macro.service.MacroAccessibilityService
import kotlinx.coroutines.*
import java.io.File

/**
 * Enterprise Autonomous Clash of Clans Farming Engine.
 *
 * Implements:
 * - Autonomous State Loop: HOME -> MATCHMAKING -> COMBAT -> RETURNING.
 * - Dynamic Replay of User-Recorded Attacks via TouchReplayEngine.
 * - Zero-Asset relative percentage coordinates (adaptive across all landscape screen sizes).
 * - Direct execution via MacroAccessibilityService.dispatchTap.
 * - Real-time status update callbacks to FloatingHub UI.
 * - Hardware kill-switch integration.
 */
object FarmingEngine {
    private const val TAG = "FarmingEngine"
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var isRunning: Boolean = false
        private set

    var customAttackFile: File? = null

    enum class GameState { HOME, MATCHMAKING, COMBAT, RETURNING }
    private var state = GameState.HOME

    init {
        // Hardware Kill-Switch listener
        MacroAccessibilityService.registerKillSwitchListener {
            if (isRunning) {
                Log.w(TAG, "Hardware Kill-Switch triggered. Halting FarmingEngine.")
                stop { }
            }
        }
    }

    fun start(onUpdate: (String) -> Unit) {
        if (isRunning) return
        isRunning = true

        job = scope.launch {
            while (isActive && isRunning) {
                val frame = ScreenCaptureManager.getLatestBitmap()
                val (w, h) = if (frame != null) {
                    Pair(frame.width.toFloat(), frame.height.toFloat())
                } else {
                    val metrics = MacroAccessibilityService.instance?.resources?.displayMetrics
                    if (metrics != null) {
                        val screenW = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
                        val screenH = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
                        Pair(screenW, screenH)
                    } else {
                        onUpdate("INITIALIZING VISION...")
                        delay(300)
                        continue
                    }
                }

                try {
                    when (state) {
                        GameState.HOME -> {
                            onUpdate("HOME: Opening Attack Menu")
                            HumanGestureDispatcher.humanTap(w * 0.055f, h * 0.885f)
                            delay(1200)

                            onUpdate("HOME: Finding Match")
                            HumanGestureDispatcher.humanTap(w * 0.78f, h * 0.72f)
                            delay(2500)
                            state = GameState.MATCHMAKING
                        }
                        GameState.MATCHMAKING -> {
                            for (step in 1..4) {
                                if (!isRunning || !isActive) break
                                onUpdate("MATCHMAKING: Finding target (${step}s)")
                                delay(1000)
                            }
                            state = GameState.COMBAT
                        }
                        GameState.COMBAT -> {
                            val recordingFile = customAttackFile
                            if (recordingFile != null && TouchReplayEngine.hasRecording(recordingFile)) {
                                onUpdate("COMBAT: Replaying Custom Attack")
                                TouchReplayEngine.replay(recordingFile, w, h) { cur, total ->
                                    onUpdate("REPLAY: $cur/$total")
                                }
                            } else {
                                onUpdate("COMBAT: Deploying Army")
                                // 1. Select First Troop Slot
                                HumanGestureDispatcher.humanTap(w * 0.18f, h * 0.90f)
                                delay(300)

                                // 2. Multi-point deployment spread
                                val spread = listOf(
                                    PointF(w * 0.25f, h * 0.20f),
                                    PointF(w * 0.35f, h * 0.15f),
                                    PointF(w * 0.45f, h * 0.12f),
                                    PointF(w * 0.55f, h * 0.15f)
                                )
                                spread.forEach { pt ->
                                    HumanGestureDispatcher.humanTap(pt.x, pt.y)
                                    delay(120)
                                }
                            }

                            // Hold battle duration (Anti-lockout window)
                            for (i in 30 downTo 1) {
                                if (!isRunning || !isActive) break
                                onUpdate("BATTLE: ${i}s remaining")
                                delay(1000)
                            }
                            state = GameState.RETURNING
                        }
                        GameState.RETURNING -> {
                            onUpdate("RETURNING: Ending Battle")
                            HumanGestureDispatcher.humanTap(w * 0.075f, h * 0.78f) // Surrender
                            delay(800)
                            HumanGestureDispatcher.humanTap(w * 0.58f, h * 0.62f)  // Confirm OK
                            delay(2500)
                            HumanGestureDispatcher.humanTap(w * 0.50f, h * 0.85f)  // Return Home
                            delay(3000)
                            state = GameState.HOME
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in FarmingEngine loop", e)
                    delay(1000)
                } finally {
                    frame?.recycle()
                }
            }
        }
    }

    fun stop(onUpdate: (String) -> Unit = {}) {
        isRunning = false
        job?.cancel()
        job = null
        state = GameState.HOME
        onUpdate("IDLE")
    }
}
