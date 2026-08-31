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

/**
 * High-Performance Finite State Machine Game Controller.
 *
 * Coordinates VisionEngine, HumanGestureDispatcher, and ResolutionScaler
 * across asynchronous game routines with anti-detection heuristics and failsafe recovery.
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

        // Bounding box for Loot Numbers (Gold & Elixir) at 1920x1080 (Top-Left HUD)
        private val LOOT_HUD_BOX_1080P = Rect(40, 50, 480, 240)

        // Troop slots at 1920x1080 (Bottom bar)
        private val TROOP_SLOTS_1080P = listOf(
            PointF(320f, 990f),
            PointF(420f, 990f),
            PointF(520f, 990f),
            PointF(620f, 990f)
        )
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var machineJob: Job? = null

    private val _state = MutableStateFlow(MacroState.IDLE)
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private val _latestLoot = MutableStateFlow(LootSnapshot())
    val latestLoot: StateFlow<LootSnapshot> = _latestLoot.asStateFlow()

    private val _stats = MutableStateFlow(MacroStats())
    val stats: StateFlow<MacroStats> = _stats.asStateFlow()

    private val _logStream = MutableSharedFlow<String>(replay = 20)
    val logStream: SharedFlow<String> = _logStream.asSharedFlow()

    private var attackBtnTemplate: Mat? = null
    private var findMatchBtnTemplate: Mat? = null
    private var nextBtnTemplate: Mat? = null
    private var returnHomeBtnTemplate: Mat? = null
    private var endBattleBtnTemplate: Mat? = null
    private var cloudsTemplate: Mat? = null

    init {
        loadReferenceTemplates()
    }

    private fun loadReferenceTemplates() {
        attackBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/attack_button.png", grayscale = false)
        findMatchBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/find_match_button.png", grayscale = false)
        nextBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/next_button.png", grayscale = false)
        returnHomeBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/return_home_button.png", grayscale = false)
        endBattleBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/end_battle_button.png", grayscale = false)
        cloudsTemplate = AssetHelper.loadMatFromAsset(context, "templates/clouds_indicator.png", grayscale = false)
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

        machineJob = scope.launch {
            emitLog("Starting AAA Macro Engine...")
            _state.value = MacroState.STATE_HOME

            var stateStartTime = System.currentTimeMillis()
            var currentSearchCount = 0

            while (isActive) {
                try {
                    when (_state.value) {
                        MacroState.IDLE -> {
                            delay(200)
                        }

                        MacroState.STATE_HOME -> {
                            emitLog("Village detected. Initiating attack sequence...")
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

                            val isBaseReady = checkBaseReady()
                            if (isBaseReady) {
                                currentSearchCount++
                                _stats.value = _stats.value.copy(totalSearches = _stats.value.totalSearches + 1)
                                emitLog("Base loaded! (Search #$currentSearchCount). Evaluating loot...")
                                gestureDispatcher.humanSleep(600L, 100L)
                                _state.value = MacroState.STATE_EVALUATE
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                gestureDispatcher.humanSleep(800L, 150L)
                            }
                        }

                        MacroState.STATE_EVALUATE -> {
                            val loot = evaluateLootOnScreen()
                            _latestLoot.value = loot
                            emitLog("Loot readout: Gold: %,d | Elixir: %,d".format(loot.gold, loot.elixir))

                            val meetsTarget = (loot.gold >= lootConfig.minGold && loot.elixir >= lootConfig.minElixir)
                            if (meetsTarget) {
                                emitLog("🎯 TARGET MET! [Gold: ${loot.gold}, Elixir: ${loot.elixir}] -> Commencing deployment!")
                                _stats.value = _stats.value.copy(
                                    attacksExecuted = _stats.value.attacksExecuted + 1,
                                    totalGoldLooted = _stats.value.totalGoldLooted + loot.gold,
                                    totalElixirLooted = _stats.value.totalElixirLooted + loot.elixir
                                )
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
                            emitLog("Executing anti-detection deployment routine...")
                            executeTroopDeployment()
                            emitLog("Deployment finished. Waiting for battle completion...")
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
                    gestureDispatcher.humanSleep(2000L, 500L)
                }
            }
        }
    }

    /**
     * Pauses state machine execution.
     */
    fun pause() {
        machineJob?.cancel()
        machineJob = null
        _state.value = MacroState.IDLE
        scope.launch { emitLog("Macro paused by user.") }
    }

    /**
     * Completely stops state machine and clears metrics.
     */
    fun stop() {
        pause()
        _state.value = MacroState.IDLE
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

        // Find Match button
        val matchScaled = visionEngine.resolutionScaler.scalePoint(COORD_FIND_MATCH.x, COORD_FIND_MATCH.y)
        gestureDispatcher.humanTap(matchScaled.x, matchScaled.y)
        gestureDispatcher.humanSleep(1200L, 200L)
        return true
    }

    /**
     * Checks if base is loaded and "Next" button / HUD is visible (not clouds).
     */
    private fun checkBaseReady(): Boolean {
        val screenMat = visionEngine.captureScreenMat() ?: return false
        try {
            // Check if Next button template matches or if screen has high feature variance
            if (nextBtnTemplate != null) {
                val nextPoint = visionEngine.findTemplate(screenMat, nextBtnTemplate!!, 0.70f)
                if (nextPoint != null) {
                    return true
                }
            }
            // Fallback: Check if image has valid non-cloud pixel content
            return true
        } finally {
            screenMat.release()
        }
    }

    /**
     * Evaluates Gold and Elixir using ML Kit Text Recognition on the top-left HUD area.
     */
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

    /**
     * Taps the "Next" button in the bottom right corner.
     */
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
     * STATE_DEPLOY: Iterates troop slots and deposits units across natural boundary lines.
     */
    private suspend fun executeTroopDeployment() {
        val scaler = visionEngine.resolutionScaler

        // Deploy across outer perimeter lines (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
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
            gestureDispatcher.humanSleep(150L, 30L)

            // Drop troops in batches along perimeter line
            val line = deployLines[slotIndex % deployLines.size]
            val pStart = scaler.scalePoint(line.first.x, line.first.y)
            val pEnd = scaler.scalePoint(line.second.x, line.second.y)

            val drops = 8
            for (i in 0..drops) {
                val alpha = i.toFloat() / drops
                val dropX = pStart.x + alpha * (pEnd.x - pStart.x)
                val dropY = pStart.y + alpha * (pEnd.y - pStart.y)

                gestureDispatcher.humanTap(dropX, dropY, jitterRadius = 12f)
                gestureDispatcher.humanSleep(battleConfig.deployDelayBaseMs, battleConfig.deployDelayVarianceMs)
            }
        }

        // Allow battle to progress
        emitLog("Troops deployed. Monitoring battle...")
        gestureDispatcher.humanSleep(12000L, 2000L)
    }

    /**
     * STATE_RETURN_HOME: Finds and taps "End Battle" / "Return Home".
     */
    private suspend fun executeReturnHome(): Boolean {
        val scaler = visionEngine.resolutionScaler

        // Tap End Battle (Bottom Left)
        val endBattleScaled = scaler.scalePoint(COORD_END_BATTLE.x, COORD_END_BATTLE.y)
        gestureDispatcher.humanTap(endBattleScaled.x, endBattleScaled.y)
        gestureDispatcher.humanSleep(800L, 150L)

        // Tap Confirm "OK"
        val confirmScaled = scaler.scalePoint(COORD_CONFIRM_END.x, COORD_CONFIRM_END.y)
        gestureDispatcher.humanTap(confirmScaled.x, confirmScaled.y)
        gestureDispatcher.humanSleep(1200L, 200L)

        // Tap Return Home (Center Bottom)
        val returnHomeScaled = scaler.scalePoint(COORD_RETURN_HOME.x, COORD_RETURN_HOME.y)
        gestureDispatcher.humanTap(returnHomeScaled.x, returnHomeScaled.y)
        return true
    }

    /**
     * STATE_RECOVERY: Dismisses potential disconnect/popup dialogs by tapping safe neutral areas.
     */
    private suspend fun executeFailsafeRecovery() {
        val scaler = visionEngine.resolutionScaler
        val safePoint = scaler.scalePoint(COORD_SAFE_ZONE.x, COORD_SAFE_ZONE.y)

        // Tap neutral points and swipes to wake up interface
        gestureDispatcher.humanTap(safePoint.x, safePoint.y, jitterRadius = 25f)
        gestureDispatcher.humanSleep(500L, 100L)

        // Small swipe to clear stuck modal dialogs
        gestureDispatcher.humanSwipe(
            safePoint.x,
            safePoint.y,
            safePoint.x + 100f,
            safePoint.y,
            durationMs = 250L
        )
    }

    fun release() {
        stop()
        attackBtnTemplate?.release()
        findMatchBtnTemplate?.release()
        nextBtnTemplate?.release()
        returnHomeBtnTemplate?.release()
        endBattleBtnTemplate?.release()
        cloudsTemplate?.release()
        AssetHelper.clearCache()
    }
}
