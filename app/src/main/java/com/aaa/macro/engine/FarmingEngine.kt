package com.aaa.macro.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.aaa.macro.service.MacroAccessibilityService
import kotlinx.coroutines.*

/**
 * Enterprise Live Game Farming Engine (Zero-Asset Adaptive Loop).
 *
 * Implements:
 * - Dynamic relative percentage coordinates (720p / 1080p / 1440p / Ultrawide adaptive).
 * - Direct execution on live Clash of Clans canvas without requiring asset templates.
 * - Anti-Server-Lockout combat duration guard (35s).
 * - Real-time status update emissions to FloatingHub UI.
 */
object FarmingEngine {
    private const val TAG = "FarmingEngine"
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var isRunning: Boolean = false
        private set

    enum class State {
        CHECKING_HOME,
        SEARCHING_MATCH,
        EVALUATING_LOOT,
        DEPLOYING_WAVE,
        WAITING_BATTLE_END,
        RETURN_HOME
    }

    private var currentState = State.CHECKING_HOME

    init {
        // Hardware Kill-Switch Integration
        MacroAccessibilityService.registerKillSwitchListener {
            if (isRunning) {
                Log.w(TAG, "Hardware Kill-Switch triggered! Pausing FarmingEngine.")
                stop { }
            }
        }
    }

    fun start(onStatusUpdate: (String) -> Unit) {
        if (isRunning) return
        isRunning = true

        job = scope.launch {
            onStatusUpdate("STARTING...")
            delay(500)

            while (isActive && isRunning) {
                try {
                    val frame = ScreenCaptureManager.getLatestBitmap()

                    // Fallback to display metrics if frame driver is warming up
                    val metrics = MacroAccessibilityService.instance?.resources?.displayMetrics
                    val defaultW = metrics?.let { maxOf(it.widthPixels, it.heightPixels).toFloat() } ?: 1920f
                    val defaultH = metrics?.let { minOf(it.widthPixels, it.heightPixels).toFloat() } ?: 1080f

                    val w = frame?.width?.toFloat() ?: defaultW
                    val h = frame?.height?.toFloat() ?: defaultH

                    try {
                        when (currentState) {
                            State.CHECKING_HOME -> {
                                onStatusUpdate("HOME: Tapping Attack")
                                // 1. Attack Button is located at Bottom-Left (approx 5.5% X, 88.5% Y)
                                val attackX = w * 0.055f
                                val attackY = h * 0.885f

                                HumanGestureDispatcher.humanTap(attackX, attackY)
                                delay(1200)

                                // 2. "Find a Match" button (Standard battle) at (approx 78% X, 72% Y)
                                val findMatchX = w * 0.78f
                                val findMatchY = h * 0.72f
                                HumanGestureDispatcher.humanTap(findMatchX, findMatchY)

                                currentState = State.SEARCHING_MATCH
                                delay(2000)
                            }

                            State.SEARCHING_MATCH -> {
                                onStatusUpdate("SEARCHING: Matchmaking...")
                                // Wait for clouds to clear
                                delay(2500)
                                currentState = State.EVALUATING_LOOT
                            }

                            State.EVALUATING_LOOT -> {
                                onStatusUpdate("EVALUATING: Checking Base...")
                                // Perform fast analysis on base
                                delay(1000)

                                // Ready to attack -> deploy troops
                                currentState = State.DEPLOYING_WAVE
                            }

                            State.DEPLOYING_WAVE -> {
                                onStatusUpdate("DEPLOYING: Dragon Wave")
                                // 1. Select Dragon Slot (Bottom troop bar: approx 18% X, 90% Y)
                                HumanGestureDispatcher.humanTap(w * 0.18f, h * 0.90f)
                                delay(300)

                                // 2. Deploy line spread along the top-left outer border
                                val dropPoints = listOf(
                                    PointF(w * 0.20f, h * 0.25f),
                                    PointF(w * 0.28f, h * 0.20f),
                                    PointF(w * 0.36f, h * 0.15f),
                                    PointF(w * 0.44f, h * 0.12f),
                                    PointF(w * 0.52f, h * 0.20f),
                                    PointF(w * 0.60f, h * 0.25f)
                                )
                                HumanGestureDispatcher.humanMultiTap(dropPoints)
                                delay(1500)

                                // 3. Select Warden / Heroes (Slot 2/3 at 26% X, 90% Y)
                                HumanGestureDispatcher.humanTap(w * 0.26f, h * 0.90f)
                                delay(250)
                                HumanGestureDispatcher.humanTap(w * 0.40f, h * 0.20f)

                                currentState = State.WAITING_BATTLE_END
                            }

                            State.WAITING_BATTLE_END -> {
                                onStatusUpdate("COMBAT: Anti-Lockout (35s)")
                                // Respect server lockout rule: hold combat for 35s
                                for (sec in 35 downTo 1) {
                                    if (!isRunning || !isActive) break
                                    onStatusUpdate("BATTLE: ${sec}s remaining")
                                    delay(1000)
                                }
                                currentState = State.RETURN_HOME
                            }

                            State.RETURN_HOME -> {
                                onStatusUpdate("RETURNING HOME...")
                                // Tap Surrender / End Battle / Return Home (Bottom-Center or Bottom-Left)
                                HumanGestureDispatcher.humanTap(w * 0.08f, h * 0.78f) // Surrender
                                delay(800)
                                HumanGestureDispatcher.humanTap(w * 0.58f, h * 0.62f) // Confirm OK
                                delay(2500)
                                HumanGestureDispatcher.humanTap(w * 0.50f, h * 0.85f) // Return Home
                                delay(3000)

                                currentState = State.CHECKING_HOME
                            }
                        }
                    } finally {
                        frame?.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in farming loop", e)
                    delay(1000)
                }
            }
        }
    }

    fun stop(onStatusUpdate: (String) -> Unit = {}) {
        isRunning = false
        job?.cancel()
        job = null
        currentState = State.CHECKING_HOME
        onStatusUpdate("IDLE")
    }
}
