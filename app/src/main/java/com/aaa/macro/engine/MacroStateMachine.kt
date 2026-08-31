package com.aaa.macro.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.aaa.macro.model.BattleConfig
import com.aaa.macro.model.LootConfig
import com.aaa.macro.model.LootSnapshot
import com.aaa.macro.model.MacroState
import com.aaa.macro.model.MacroStats
import com.aaa.macro.service.MacroAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.Point
import java.util.Random

/**
 * Production-Grade Finite State Machine Game Controller.
 *
 * Advanced Heuristics & Anti-Detection:
 * - Hardware Kill-Switch listener (Volume Down)
 * - Camera Calibration Routine (Pinch-to-zoom-out & corner drag)
 * - Multi-touch simultaneous troop deployment
 * - Background Failsafe & Desync Auto-Recovery (checks for Reload/Try Again popups every 3s)
 * - Session Fatigue Simulation (micro-pauses of 30-60s every 3-5 raids)
 * - Thermal & Resource Throttling (1.5-2 FPS analysis frequency)
 */
class MacroStateMachine(
    private val context: Context,
    private val visionEngine: VisionEngine,
    private val gestureDispatcher: HumanGestureDispatcher,
    val lootConfig: LootConfig = LootConfig(),
    val battleConfig: BattleConfig = BattleConfig()
) {
    companion object {
        private const val TAG = "MacroStateMachine"

        // Canonical 1920x1080 Reference Coordinates
        private val COORD_HOME_ATTACK = PointF(105f, 950f)
        private val COORD_FIND_MATCH = PointF(1420f, 720f)
        private val COORD_NEXT_BUTTON = PointF(1750f, 890f)
        private val COORD_END_BATTLE = PointF(110f, 840f)
        private val COORD_CONFIRM_END = PointF(1100f, 620f)
        private val COORD_RETURN_HOME = PointF(960f, 910f)
        private val COORD_SAFE_ZONE = PointF(960f, 400f)

        // Bounding box for Loot Numbers at 1920x1080 (Top-Left HUD)
        private val LOOT_HUD_BOX_1080P = Rect(40, 50, 480, 240)

        // Troop slots at 1920x1080 (Bottom bar)
        private val TROOP_SLOTS_1080P = listOf(
            PointF(320f, 990f),
            PointF(420f, 990f),
            PointF(520f, 990f),
            PointF(620f, 990f)
        )
    }

    private val random = Random()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var machineJob: Job? = null
    private var backgroundFailsafeJob: Job? = null

    private val _state = MutableStateFlow(MacroState.IDLE)
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private val _latestLoot = MutableStateFlow(LootSnapshot())
    val latestLoot: StateFlow<LootSnapshot> = _latestLoot.asStateFlow()

    private val _stats = MutableStateFlow(MacroStats())
    val stats: StateFlow<MacroStats> = _stats.asStateFlow()

    private val _logStream = MutableSharedFlow<String>(replay = 20)
    val logStream: SharedFlow<String> = _logStream.asSharedFlow()

    private var raidsSinceLastBreak = 0
    private var nextFatigueThreshold = 3 + random.nextInt(3) // 3 to 5 raids

    private var attackBtnTemplate: Mat? = null
    private var findMatchBtnTemplate: Mat? = null
    private var nextBtnTemplate: Mat? = null
    private var returnHomeBtnTemplate: Mat? = null
    private var endBattleBtnTemplate: Mat? = null
    private var cloudsTemplate: Mat? = null
    private var reloadDialogTemplate: Mat? = null
    private var tryAgainDialogTemplate: Mat? = null

    init {
        loadReferenceTemplates()
        setupKillSwitch()
    }

    private fun setupKillSwitch() {
        MacroAccessibilityService.registerKillSwitchListener {
            Log.w(TAG, "Kill-switch callback invoked! Halting MacroStateMachine.")
            pause()
            _state.value = MacroState.IDLE
        }
    }

    private fun loadReferenceTemplates() {
        attackBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/attack_button.png")
        findMatchBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/find_match_button.png")
        nextBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/next_button.png")
        returnHomeBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/return_home_button.png")
        endBattleBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/end_battle_button.png")
        cloudsTemplate = AssetHelper.loadMatFromAsset(context, "templates/clouds_indicator.png")
        reloadDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_reload.png")
        tryAgainDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_try_again.png")
    }

    private suspend fun emitLog(message: String) {
        Log.i(TAG, message)
        _logStream.emit(message)
    }

    /**
     * Starts or resumes the Macro Finite State Machine.
     */
    fun start() {
        if (machineJob?.isActive == true) {
            Log.w(TAG, "MacroStateMachine is already running.")
            return
        }

        startBackgroundFailsafeWatcher()

        machineJob = scope.launch {
            emitLog("Starting AAA Macro Engine with Anti-Detection Heuristics...")
            _state.value = MacroState.STATE_HOME

            var stateStartTime = System.currentTimeMillis()
            var currentSearchCount = 0

            while (isActive) {
                try {
                    when (_state.value) {
                        MacroState.IDLE -> {
                            delay(250)
                        }

                        MacroState.STATE_HOME -> {
                            // Check Session Fatigue Break
                            if (raidsSinceLastBreak >= nextFatigueThreshold) {
                                val fatigueSeconds = 30 + random.nextInt(31) // 30 to 60s
                                emitLog("💤 Simulating human session fatigue... Resting for ${fatigueSeconds}s.")
                                gestureDispatcher.humanSleep(fatigueSeconds * 1000L, 2000L)
                                raidsSinceLastBreak = 0
                                nextFatigueThreshold = 3 + random.nextInt(3)
                            }

                            // Camera calibration routine before interacting with village
                            emitLog("Calibrating camera position (Pinch-to-zoom & corner drag)...")
                            resetCameraPosition()
                            gestureDispatcher.humanSleep(600L, 100L)

                            emitLog("Village calibrated. Initiating attack sequence...")
                            val executed = executeHomeState()
                            if (executed) {
                                currentSearchCount = 0
                                stateStartTime = System.currentTimeMillis()
                                _state.value = MacroState.STATE_SEARCHING
                            } else {
                                gestureDispatcher.humanSleep(1500L, 300L)
                            }
                        }

                        MacroState.STATE_SEARCHING -> {
                            val elapsed = System.currentTimeMillis() - stateStartTime
                            if (elapsed > battleConfig.searchTimeoutMs) {
                                emitLog("Search timeout exceeded (>20s). Triggering recovery failsafe...")
                                _state.value = MacroState.STATE_RECOVERY
                                continue
                            }

                            // Thermal throttling: 1.5 - 2 FPS
                            val isBaseReady = checkBaseReady()
                            if (isBaseReady) {
                                currentSearchCount++
                                _stats.value = _stats.value.copy(totalSearches = _stats.value.totalSearches + 1)
                                emitLog("Base loaded! (Search #$currentSearchCount). Evaluating loot...")
                                gestureDispatcher.humanSleep(500L, 100L)
                                _state.value = MacroState.STATE_EVALUATE
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                gestureDispatcher.humanSleep(600L, 100L)
                            }
                        }

                        MacroState.STATE_EVALUATE -> {
                            val loot = evaluateLootOnScreen()
                            _latestLoot.value = loot
                            emitLog("Loot readout: Gold: %,d | Elixir: %,d".format(loot.gold, loot.elixir))

                            val meetsTarget = (loot.gold >= lootConfig.minGold && loot.elixir >= lootConfig.minElixir)
                            if (meetsTarget) {
                                emitLog("🎯 TARGET MET! [Gold: ${loot.gold}, Elixir: ${loot.elixir}] -> Starting multi-touch deployment!")
                                _stats.value = _stats.value.copy(
                                    attacksExecuted = _stats.value.attacksExecuted + 1,
                                    totalGoldLooted = _stats.value.totalGoldLooted + loot.gold,
                                    totalElixirLooted = _stats.value.totalElixirLooted + loot.elixir
                                )
                                raidsSinceLastBreak++
                                _state.value = MacroState.STATE_DEPLOY
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                emitLog("Loot below target. Tapping 'Next' to search again...")
                                tapNextButton()
                                gestureDispatcher.humanSleep(1800L, 350L)
                                _state.value = MacroState.STATE_SEARCHING
                                stateStartTime = System.currentTimeMillis()
                            }
                        }

                        MacroState.STATE_DEPLOY -> {
                            emitLog("Executing multi-touch troop deployment...")
                            executeMultiTouchTroopDeployment()
                            emitLog("Deployment finished. Monitoring battle completion...")
                            _state.value = MacroState.STATE_RETURN_HOME
                            stateStartTime = System.currentTimeMillis()
                        }

                        MacroState.STATE_RETURN_HOME -> {
                            emitLog("Ending battle and returning to home village...")
                            val returned = executeReturnHome()
                            if (returned) {
                                gestureDispatcher.humanSleep(2500L, 500L)
                                _state.value = MacroState.STATE_HOME
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                val elapsed = System.currentTimeMillis() - stateStartTime
                                if (elapsed > 30000L) {
                                    emitLog("Return home stuck. Moving to recovery...")
                                    _state.value = MacroState.STATE_RECOVERY
                                } else {
                                    gestureDispatcher.humanSleep(1200L, 200L)
                                }
                            }
                        }

                        MacroState.STATE_RECOVERY -> {
                            emitLog("Executing failsafe recovery: Dismissing popups & tapping safe zones...")
                            executeFailsafeRecovery()
                            gestureDispatcher.humanSleep(2000L, 400L)
                            _state.value = MacroState.STATE_HOME
                            stateStartTime = System.currentTimeMillis()
                        }
                    }
                } catch (ce: CancellationException) {
                    emitLog("Macro loop cancelled.")
                    break
                } catch (e: Exception) {
                    emitLog("Error in macro loop: ${e.localizedMessage}")
                    Log.e(TAG, "Exception in StateMachine loop", e)
                    gestureDispatcher.humanSleep(1500L, 300L)
                }
            }
        }
    }

    /**
     * Background Failsafe Handler:
     * Periodically (every 3 seconds) inspects screen frames for error / desync popups
     * and automatically taps Reload or Try Again to recover game connectivity.
     */
    private fun startBackgroundFailsafeWatcher() {
        backgroundFailsafeJob?.cancel()
        backgroundFailsafeJob = scope.launch {
            while (isActive) {
                delay(3000L)
                if (_state.value == MacroState.IDLE) continue

                try {
                    val screenMat = visionEngine.captureScreenMat()
                    if (screenMat != null) {
                        var recovered = false

                        // Check Reload Dialog
                        if (reloadDialogTemplate != null) {
                            val pt = visionEngine.findTemplate(screenMat, reloadDialogTemplate!!, 0.75f)
                            if (pt != null) {
                                emitLog("⚠️ Disconnect dialog detected! Tapping 'Reload Game' to reconnect...")
                                gestureDispatcher.humanTap(pt.x.toFloat(), pt.y.toFloat())
                                recovered = true
                            }
                        }

                        // Check Try Again Dialog
                        if (!recovered && tryAgainDialogTemplate != null) {
                            val pt = visionEngine.findTemplate(screenMat, tryAgainDialogTemplate!!, 0.75f)
                            if (pt != null) {
                                emitLog("⚠️ Connection error detected! Tapping 'Try Again'...")
                                gestureDispatcher.humanTap(pt.x.toFloat(), pt.y.toFloat())
                                recovered = true
                            }
                        }

                        screenMat.release()

                        if (recovered) {
                            gestureDispatcher.humanSleep(4000L, 800L)
                            _state.value = MacroState.STATE_HOME
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background failsafe watcher", e)
                }
            }
        }
    }

    /**
     * Camera Calibration Routine:
     * Performs a two-finger pinch-to-zoom-out gesture followed by a corner drag
     * to normalize game camera perspective before interactions.
     */
    private suspend fun resetCameraPosition() {
        val scaler = visionEngine.resolutionScaler
        val center = scaler.scalePoint(960f, 540f)

        // Two-finger pinch zoom out
        gestureDispatcher.pinchZoomOut(centerX = center.x, centerY = center.y, span = 350f * scaler.scaleX)
        gestureDispatcher.humanSleep(450L, 80L)

        // Drag screen towards top-left to align base to standard coordinate grid
        val startDrag = scaler.scalePoint(1300f, 750f)
        val endDrag = scaler.scalePoint(600f, 350f)
        gestureDispatcher.humanSwipe(startDrag.x, startDrag.y, endDrag.x, endDrag.y, durationMs = 380L)
    }

    /**
     * STATE_HOME: Taps "Attack" button -> Taps "Find Match".
     */
    private suspend fun executeHomeState(): Boolean {
        val screenMat = visionEngine.captureScreenMat()
        var attackPoint: Point? = null
        if (screenMat != null && attackBtnTemplate != null) {
            attackPoint = visionEngine.findTemplate(screenMat, attackBtnTemplate!!, 0.75f)
            screenMat.release()
        }

        val targetX: Float
        val targetY: Float

        if (attackPoint != null) {
            targetX = attackPoint.x.toFloat()
            targetY = attackPoint.y.toFloat()
        } else {
            val scaled = visionEngine.resolutionScaler.scalePoint(COORD_HOME_ATTACK.x, COORD_HOME_ATTACK.y)
            targetX = scaled.x
            targetY = scaled.y
        }

        gestureDispatcher.humanTap(targetX, targetY)
        gestureDispatcher.humanSleep(900L, 150L)

        val matchScaled = visionEngine.resolutionScaler.scalePoint(COORD_FIND_MATCH.x, COORD_FIND_MATCH.y)
        gestureDispatcher.humanTap(matchScaled.x, matchScaled.y)
        gestureDispatcher.humanSleep(1200L, 200L)
        return true
    }

    private fun checkBaseReady(): Boolean {
        val screenMat = visionEngine.captureScreenMat() ?: return false
        try {
            if (nextBtnTemplate != null) {
                val nextPoint = visionEngine.findTemplate(screenMat, nextBtnTemplate!!, 0.70f)
                if (nextPoint != null) {
                    return true
                }
            }
            return true
        } finally {
            screenMat.release()
        }
    }

    private suspend fun evaluateLootOnScreen(): LootSnapshot {
        val bitmap = visionEngine.captureScreenBitmap() ?: return LootSnapshot()
        try {
            val hudRect = visionEngine.resolutionScaler.scaleRect(
                LOOT_HUD_BOX_1080P.left,
                LOOT_HUD_BOX_1080P.top,
                LOOT_HUD_BOX_1080P.right,
                LOOT_HUD_BOX_1080P.bottom
            )
            val (gold, elixir) = visionEngine.readLootValues(bitmap, hudRect)
            return LootSnapshot(gold = gold, elixir = elixir)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun tapNextButton() {
        val screenMat = visionEngine.captureScreenMat()
        var nextPoint: Point? = null
        if (screenMat != null && nextBtnTemplate != null) {
            nextPoint = visionEngine.findTemplate(screenMat, nextBtnTemplate!!, 0.72f)
            screenMat.release()
        }

        val targetX: Float
        val targetY: Float
        if (nextPoint != null) {
            targetX = nextPoint.x.toFloat()
            targetY = nextPoint.y.toFloat()
        } else {
            val scaled = visionEngine.resolutionScaler.scalePoint(COORD_NEXT_BUTTON.x, COORD_NEXT_BUTTON.y)
            targetX = scaled.x
            targetY = scaled.y
        }

        gestureDispatcher.humanTap(targetX, targetY, jitterRadius = 8f)
    }

    /**
     * STATE_DEPLOY: Multi-touch concurrent troop deployment across outer perimeter lines.
     */
    private suspend fun executeMultiTouchTroopDeployment() {
        val scaler = visionEngine.resolutionScaler

        val deployLines = listOf(
            Pair(PointF(350f, 250f), PointF(960f, 150f)),
            Pair(PointF(960f, 150f), PointF(1570f, 250f)),
            Pair(PointF(350f, 850f), PointF(960f, 950f)),
            Pair(PointF(960f, 950f), PointF(1570f, 850f))
        )

        for (slotIndex in 0 until battleConfig.troopSlotCount.coerceAtMost(TROOP_SLOTS_1080P.size)) {
            val slotCoord = TROOP_SLOTS_1080P[slotIndex]
            val scaledSlot = scaler.scalePoint(slotCoord.x, slotCoord.y)

            // Select troop slot
            gestureDispatcher.humanTap(scaledSlot.x, scaledSlot.y, jitterRadius = 5f)
            gestureDispatcher.humanSleep(160L, 30L)

            val line = deployLines[slotIndex % deployLines.size]
            val pStart = scaler.scalePoint(line.first.x, line.first.y)
            val pEnd = scaler.scalePoint(line.second.x, line.second.y)

            // Multi-touch drop points along the boundary line
            val multiTouchPoints = mutableListOf<PointF>()
            val drops = 4
            for (i in 0..drops) {
                val alpha = i.toFloat() / drops
                val dropX = pStart.x + alpha * (pEnd.x - pStart.x)
                val dropY = pStart.y + alpha * (pEnd.y - pStart.y)
                multiTouchPoints.add(PointF(dropX, dropY))
            }

            // Dispatch concurrent multi-touch stroke gestures
            gestureDispatcher.humanMultiTouchDeploy(multiTouchPoints)
            gestureDispatcher.humanSleep(battleConfig.deployDelayBaseMs, battleConfig.deployDelayVarianceMs)
        }

        emitLog("Troops deployed. Monitoring battle...")
        gestureDispatcher.humanSleep(12000L, 2000L)
    }

    private suspend fun executeReturnHome(): Boolean {
        val scaler = visionEngine.resolutionScaler

        val endBattleScaled = scaler.scalePoint(COORD_END_BATTLE.x, COORD_END_BATTLE.y)
        gestureDispatcher.humanTap(endBattleScaled.x, endBattleScaled.y)
        gestureDispatcher.humanSleep(800L, 150L)

        val confirmScaled = scaler.scalePoint(COORD_CONFIRM_END.x, COORD_CONFIRM_END.y)
        gestureDispatcher.humanTap(confirmScaled.x, confirmScaled.y)
        gestureDispatcher.humanSleep(1200L, 200L)

        val returnHomeScaled = scaler.scalePoint(COORD_RETURN_HOME.x, COORD_RETURN_HOME.y)
        gestureDispatcher.humanTap(returnHomeScaled.x, returnHomeScaled.y)
        return true
    }

    private suspend fun executeFailsafeRecovery() {
        val scaler = visionEngine.resolutionScaler
        val safePoint = scaler.scalePoint(COORD_SAFE_ZONE.x, COORD_SAFE_ZONE.y)

        gestureDispatcher.humanTap(safePoint.x, safePoint.y, jitterRadius = 25f)
        gestureDispatcher.humanSleep(500L, 100L)

        gestureDispatcher.humanSwipe(
            safePoint.x,
            safePoint.y,
            safePoint.x + 100f,
            safePoint.y,
            durationMs = 250L
        )
    }

    fun pause() {
        machineJob?.cancel()
        machineJob = null
        backgroundFailsafeJob?.cancel()
        backgroundFailsafeJob = null
        _state.value = MacroState.IDLE
        scope.launch { emitLog("Macro paused by user.") }
    }

    fun stop() {
        pause()
        _state.value = MacroState.IDLE
    }

    fun release() {
        stop()
        attackBtnTemplate?.release()
        findMatchBtnTemplate?.release()
        nextBtnTemplate?.release()
        returnHomeBtnTemplate?.release()
        endBattleBtnTemplate?.release()
        cloudsTemplate?.release()
        reloadDialogTemplate?.release()
        tryAgainDialogTemplate?.release()
        AssetHelper.clearCache()
    }
}
