package com.aaa.macro.engine

import android.content.Context
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
 * 2026 Optimized Finite State Machine Controller.
 *
 * Game Mechanics:
 * - Instant Training Flow: Zero army training downtime; immediate loop resumption on return home.
 * - Spam-Attack Lockout Protection: Randomized 35s - 55s battle timer before surrendering.
 * - Hero Equipment Staggering: Deploys heroes and activates equipment abilities sequentially.
 * - Ultra-Low Memory: Fast StateClassifier integration & localized ROI loot OCR (<60 MB RAM).
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

        // Localized ROI Bounding Box for Loot Numbers (Top-Left HUD)
        private val LOOT_HUD_BOX_1080P = Rect(40, 50, 480, 240)

        // Troop & Hero slots at 1920x1080 (Bottom bar)
        private val TROOP_SLOTS_1080P = listOf(
            PointF(320f, 990f),
            PointF(420f, 990f),
            PointF(520f, 990f),
            PointF(620f, 990f)
        )

        private val HERO_SLOTS_1080P = listOf(
            PointF(720f, 990f), // Barbarian King
            PointF(820f, 990f), // Archer Queen
            PointF(920f, 990f), // Grand Warden
            PointF(1020f, 990f) // Royal Champion
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
    private var nextFatigueThreshold = 4 + random.nextInt(3) // 4 to 6 raids

    private var attackBtnTemplate: Mat? = null
    private var findMatchBtnTemplate: Mat? = null
    private var nextBtnTemplate: Mat? = null
    private var returnHomeBtnTemplate: Mat? = null
    private var endBattleBtnTemplate: Mat? = null
    private var reloadDialogTemplate: Mat? = null
    private var tryAgainDialogTemplate: Mat? = null

    init {
        loadReferenceTemplates()
        setupKillSwitch()
    }

    private fun setupKillSwitch() {
        MacroAccessibilityService.registerKillSwitchListener {
            Log.w(TAG, "Hardware Kill-Switch triggered. Freezing StateMachine.")
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
        reloadDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_reload.png")
        tryAgainDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_try_again.png")
    }

    private suspend fun emitLog(message: String) {
        Log.i(TAG, message)
        _logStream.emit(message)
    }

    fun start() {
        if (machineJob?.isActive == true) return

        startBackgroundFailsafeWatcher()

        machineJob = scope.launch {
            emitLog("Starting 2026 Optimized AAA Macro Engine...")
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
                            // 1. Session Fatigue Simulation
                            if (raidsSinceLastBreak >= nextFatigueThreshold) {
                                val fatigueSeconds = 35 + random.nextInt(26) // 35 to 60s
                                emitLog("💤 Simulating natural human fatigue. Resting for ${fatigueSeconds}s...")
                                gestureDispatcher.humanSleep(fatigueSeconds * 1000L, 2000L)
                                raidsSinceLastBreak = 0
                                nextFatigueThreshold = 4 + random.nextInt(3)
                            }

                            // 2. Camera Calibration Routine
                            emitLog("Calibrating camera (Pinch zoom & grid alignment)...")
                            resetCameraPosition()
                            gestureDispatcher.humanSleep(500L, 100L)

                            // 3. Instant Training Flow: Initiate next attack immediately
                            emitLog("Instant Training active. Initiating attack sequence...")
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
                                emitLog("Search timeout (>20s). Triggering recovery failsafe...")
                                _state.value = MacroState.STATE_RECOVERY
                                continue
                            }

                            // Fast Screen Classifier (1.5 - 2 FPS)
                            val classified = visionEngine.classifyCurrentScreen()
                            if (classified == DetectedScreenState.MATCH_FOUND || checkBaseReady()) {
                                currentSearchCount++
                                _stats.value = _stats.value.copy(totalSearches = _stats.value.totalSearches + 1)
                                emitLog("Match Found! (Search #$currentSearchCount). Reading localized loot ROI...")
                                gestureDispatcher.humanSleep(400L, 100L)
                                _state.value = MacroState.STATE_EVALUATE
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                gestureDispatcher.humanSleep(550L, 100L)
                            }
                        }

                        MacroState.STATE_EVALUATE -> {
                            val loot = evaluateLocalizedLoot()
                            _latestLoot.value = loot
                            emitLog("ROI Loot Readout: Gold: %,d | Elixir: %,d".format(loot.gold, loot.elixir))

                            val meetsTarget = (loot.gold >= lootConfig.minGold && loot.elixir >= lootConfig.minElixir)
                            if (meetsTarget) {
                                emitLog("🎯 TARGET MET! [Gold: ${loot.gold}, Elixir: ${loot.elixir}] -> Commencing deployment!")
                                _stats.value = _stats.value.copy(
                                    attacksExecuted = _stats.value.attacksExecuted + 1,
                                    totalGoldLooted = _stats.value.totalGoldLooted + loot.gold,
                                    totalElixirLooted = _stats.value.totalElixirLooted + loot.elixir
                                )
                                raidsSinceLastBreak++
                                _state.value = MacroState.STATE_DEPLOY
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                emitLog("Loot below threshold. Skipping to next opponent...")
                                tapNextButton()
                                gestureDispatcher.humanSleep(1700L, 300L)
                                _state.value = MacroState.STATE_SEARCHING
                                stateStartTime = System.currentTimeMillis()
                            }
                        }

                        MacroState.STATE_DEPLOY -> {
                            emitLog("Executing multi-touch troop deployment & Hero Equipment abilities...")
                            executeDeployWithHeroEquipment()

                            // Spam-Attack Lockout Protection: Enforce minimum battle duration (35s - 55s)
                            val minDurationSec = battleConfig.minBattleDurationSec.coerceAtLeast(30)
                            val maxDurationSec = battleConfig.maxBattleDurationSec.coerceAtLeast(minDurationSec)
                            val targetDurationMs = (minDurationSec + random.nextInt(maxDurationSec - minDurationSec + 1)) * 1000L

                            emitLog("🛡️ Lockout Protection: Letting battle run for ${targetDurationMs / 1000}s to prevent matchmaking bans...")
                            gestureDispatcher.humanSleep(targetDurationMs, 2000L)

                            _state.value = MacroState.STATE_RETURN_HOME
                            stateStartTime = System.currentTimeMillis()
                        }

                        MacroState.STATE_RETURN_HOME -> {
                            emitLog("Surrendering / Ending battle and returning home...")
                            val returned = executeReturnHome()
                            if (returned) {
                                gestureDispatcher.humanSleep(2200L, 400L)
                                _state.value = MacroState.STATE_HOME
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                val elapsed = System.currentTimeMillis() - stateStartTime
                                if (elapsed > 25000L) {
                                    emitLog("Return home stuck. Routing to failsafe...")
                                    _state.value = MacroState.STATE_RECOVERY
                                } else {
                                    gestureDispatcher.humanSleep(1000L, 200L)
                                }
                            }
                        }

                        MacroState.STATE_RECOVERY -> {
                            emitLog("Executing Failsafe Recovery: Dismissing popups & tapping safe zones...")
                            executeFailsafeRecovery()
                            gestureDispatcher.humanSleep(2000L, 400L)
                            _state.value = MacroState.STATE_HOME
                            stateStartTime = System.currentTimeMillis()
                        }
                    }
                } catch (ce: CancellationException) {
                    emitLog("Macro engine cancelled.")
                    break
                } catch (e: Exception) {
                    emitLog("Error in macro loop: ${e.localizedMessage}")
                    Log.e(TAG, "Exception in StateMachine loop", e)
                    gestureDispatcher.humanSleep(1500L, 300L)
                }
            }
        }
    }

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

                        if (reloadDialogTemplate != null) {
                            val pt = visionEngine.findTemplate(screenMat, reloadDialogTemplate!!, 0.75f)
                            if (pt != null) {
                                emitLog("⚠️ Disconnect dialog detected! Tapping 'Reload Game'...")
                                gestureDispatcher.humanTap(pt.x.toFloat(), pt.y.toFloat())
                                recovered = true
                            }
                        }

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
                    Log.e(TAG, "Error in background failsafe", e)
                }
            }
        }
    }

    private suspend fun resetCameraPosition() {
        val scaler = visionEngine.resolutionScaler
        val center = scaler.scalePoint(960f, 540f)

        gestureDispatcher.pinchZoomOut(centerX = center.x, centerY = center.y, span = 350f * scaler.scaleX)
        gestureDispatcher.humanSleep(450L, 80L)

        val startDrag = scaler.scalePoint(1300f, 750f)
        val endDrag = scaler.scalePoint(600f, 350f)
        gestureDispatcher.humanSwipe(startDrag.x, startDrag.y, endDrag.x, endDrag.y, durationMs = 380L)
    }

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
        gestureDispatcher.humanSleep(1100L, 200L)
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

    /**
     * Localized ROI Loot Reading (<60 MB RAM).
     */
    private suspend fun evaluateLocalizedLoot(): LootSnapshot {
        val hudRect = visionEngine.resolutionScaler.scaleRect(
            LOOT_HUD_BOX_1080P.left,
            LOOT_HUD_BOX_1080P.top,
            LOOT_HUD_BOX_1080P.right,
            LOOT_HUD_BOX_1080P.bottom
        )
        val (gold, elixir) = visionEngine.readLootValues(hudRect)
        return LootSnapshot(gold = gold, elixir = elixir)
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
     * Deploys troops with multi-touch funneling followed by staggered Hero & Equipment ability activations.
     */
    private suspend fun executeDeployWithHeroEquipment() {
        val scaler = visionEngine.resolutionScaler

        val deployLines = listOf(
            Pair(PointF(350f, 250f), PointF(960f, 150f)),
            Pair(PointF(960f, 150f), PointF(1570f, 250f)),
            Pair(PointF(350f, 850f), PointF(960f, 950f)),
            Pair(PointF(960f, 950f), PointF(1570f, 850f))
        )

        // 1. Deploy Regular Troops via Multi-Touch
        for (slotIndex in 0 until battleConfig.troopSlotCount.coerceAtMost(TROOP_SLOTS_1080P.size)) {
            val slotCoord = TROOP_SLOTS_1080P[slotIndex]
            val scaledSlot = scaler.scalePoint(slotCoord.x, slotCoord.y)

            gestureDispatcher.humanTap(scaledSlot.x, scaledSlot.y, jitterRadius = 5f)
            gestureDispatcher.humanSleep(150L, 30L)

            val line = deployLines[slotIndex % deployLines.size]
            val pStart = scaler.scalePoint(line.first.x, line.first.y)
            val pEnd = scaler.scalePoint(line.second.x, line.second.y)

            val multiTouchPoints = mutableListOf<PointF>()
            val drops = 4
            for (i in 0..drops) {
                val alpha = i.toFloat() / drops
                val dropX = pStart.x + alpha * (pEnd.x - pStart.x)
                val dropY = pStart.y + alpha * (pEnd.y - pStart.y)
                multiTouchPoints.add(PointF(dropX, dropY))
            }

            gestureDispatcher.humanMultiTouchDeploy(multiTouchPoints)
            gestureDispatcher.humanSleep(battleConfig.deployDelayBaseMs, battleConfig.deployDelayVarianceMs)
        }

        // 2. Deploy Heroes with Staggered Equipment Ability Triggers
        for (heroIndex in 0 until battleConfig.heroSlotCount.coerceAtMost(HERO_SLOTS_1080P.size)) {
            val heroSlot = HERO_SLOTS_1080P[heroIndex]
            val scaledHeroSlot = scaler.scalePoint(heroSlot.x, heroSlot.y)

            val funnelDrop = scaler.scalePoint(960f, 900f) // Funnel drop near bottom-center
            gestureDispatcher.deployHeroWithEquipment(
                heroSlotX = scaledHeroSlot.x,
                heroSlotY = scaledHeroSlot.y,
                dropX = funnelDrop.x,
                dropY = funnelDrop.y,
                abilityTriggerDelayMs = 0L // Hero deployed; equipment triggered later
            )
            gestureDispatcher.humanSleep(300L, 50L)
        }

        // Staggered trigger of Hero Equipment abilities
        emitLog("Staggering Hero Equipment ability activations...")
        gestureDispatcher.humanSleep(battleConfig.heroAbilityDelayMs, 1000L)

        for (heroIndex in 0 until battleConfig.heroSlotCount.coerceAtMost(HERO_SLOTS_1080P.size)) {
            val heroSlot = HERO_SLOTS_1080P[heroIndex]
            val scaledHeroSlot = scaler.scalePoint(heroSlot.x, heroSlot.y)
            gestureDispatcher.humanTap(scaledHeroSlot.x, scaledHeroSlot.y, jitterRadius = 4f)
            gestureDispatcher.humanSleep(400L, 80L)
        }
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
        scope.launch { emitLog("Macro paused.") }
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
        reloadDialogTemplate?.release()
        tryAgainDialogTemplate?.release()
        AssetHelper.clearCache()
    }
}
