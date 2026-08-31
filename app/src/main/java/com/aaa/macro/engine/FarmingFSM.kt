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
 * Farming Deployment Presets.
 */
enum class FarmingPreset(val displayName: String) {
    DRAGON_EDRAG_WAVE("Preset 1: Dragon / E-Drag Wave"),
    SNEAKY_GOBLIN_SNIPE("Preset 2: Sneaky Goblin Collector Snipe"),
    BARCH_PERIMETER_SPREAD("Preset 3: BARCH Perimeter Spread")
}

/**
 * Dedicated Standard Multiplayer Farming Finite State Machine (FarmingFSM).
 *
 * Implements:
 * - Notch/Cutout-aware coordinate transformations
 * - Multi-Preset Army Deployment (Dragon Wave, Sneaky Goblin, BARCH)
 * - Zero-cloud On-Device ML Kit OCR with ROI cropping
 * - 35s - 50s Anti-Spam Server Lockout Delay
 * - Background Desync & 12s Neutral Recovery Failsafes
 */
class FarmingFSM(
    private val context: Context,
    val visionEngine: OfflineVisionEngine,
    val gestureDispatcher: HumanGestureDispatcher,
    val cutoutManager: CutoutManager,
    val lootConfig: LootConfig = LootConfig(),
    val battleConfig: BattleConfig = BattleConfig()
) {
    companion object {
        private const val TAG = "FarmingFSM"

        // Canonical 1920x1080 Reference Coordinates
        private val COORD_HOME_ATTACK = PointF(105f, 950f)
        private val COORD_FIND_MATCH = PointF(1420f, 720f)
        private val COORD_NEXT_BUTTON = PointF(1750f, 890f)
        private val COORD_END_BATTLE = PointF(110f, 840f)
        private val COORD_CONFIRM_END = PointF(1100f, 620f)
        private val COORD_RETURN_HOME = PointF(960f, 910f)
        private val COORD_SAFE_ZONE = PointF(960f, 450f)

        // Localized Top-Left Loot HUD Box
        private val LOOT_HUD_BOX_1080P = Rect(40, 50, 480, 240)

        // Bottom Army Bar Slots (1920x1080)
        private val TROOP_SLOTS_1080P = listOf(
            PointF(320f, 990f),
            PointF(420f, 990f),
            PointF(520f, 990f),
            PointF(620f, 990f)
        )

        private val SPELL_SLOTS_1080P = listOf(
            PointF(680f, 990f),
            PointF(740f, 990f)
        )

        private val HERO_SLOTS_1080P = listOf(
            PointF(800f, 990f), // Barbarian King
            PointF(860f, 990f), // Archer Queen
            PointF(920f, 990f), // Grand Warden
            PointF(980f, 990f)  // Royal Champion
        )
    }

    private val random = Random()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var fsmJob: Job? = null
    private var failsafeJob: Job? = null

    private val _state = MutableStateFlow(MacroState.IDLE)
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private val _selectedPreset = MutableStateFlow(FarmingPreset.DRAGON_EDRAG_WAVE)
    val selectedPreset: StateFlow<FarmingPreset> = _selectedPreset.asStateFlow()

    private val _latestLoot = MutableStateFlow(LootSnapshot())
    val latestLoot: StateFlow<LootSnapshot> = _latestLoot.asStateFlow()

    private val _stats = MutableStateFlow(MacroStats())
    val stats: StateFlow<MacroStats> = _stats.asStateFlow()

    private val _logStream = MutableSharedFlow<String>(replay = 25)
    val logStream: SharedFlow<String> = _logStream.asSharedFlow()

    private var attackBtnTemplate: Mat? = null
    private var findMatchBtnTemplate: Mat? = null
    private var nextBtnTemplate: Mat? = null
    private var returnHomeBtnTemplate: Mat? = null
    private var endBattleBtnTemplate: Mat? = null
    private var reloadDialogTemplate: Mat? = null
    private var tryAgainDialogTemplate: Mat? = null

    init {
        loadTemplates()
        setupKillSwitch()
    }

    private fun setupKillSwitch() {
        MacroAccessibilityService.registerKillSwitchListener {
            Log.w(TAG, "Hardware Kill-Switch triggered! Immediately pausing Farming FSM.")
            pause()
            _state.value = MacroState.IDLE
        }
    }

    private fun loadTemplates() {
        attackBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/attack_button.png")
        findMatchBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/find_match_button.png")
        nextBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/next_button.png")
        returnHomeBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/return_home_button.png")
        endBattleBtnTemplate = AssetHelper.loadMatFromAsset(context, "templates/end_battle_button.png")
        reloadDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_reload.png")
        tryAgainDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_try_again.png")
    }

    fun setPreset(preset: FarmingPreset) {
        _selectedPreset.value = preset
        scope.launch { emitLog("Army Preset switched to: ${preset.displayName}") }
    }

    private suspend fun emitLog(message: String) {
        Log.i(TAG, message)
        _logStream.emit(message)
    }

    fun start() {
        if (fsmJob?.isActive == true) return

        startBackgroundFailsafeWatcher()

        fsmJob = scope.launch {
            emitLog("Starting Professional Farming Engine [${_selectedPreset.value.displayName}]...")
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
                            emitLog("Detecting Home Base UI. Initiating Attack sequence...")
                            val success = executeHomeAttackSequence()
                            if (success) {
                                currentSearchCount = 0
                                stateStartTime = System.currentTimeMillis()
                                _state.value = MacroState.STATE_SEARCHING
                            } else {
                                gestureDispatcher.humanSleep(1500L, 300L)
                            }
                        }

                        MacroState.STATE_SEARCHING -> {
                            val elapsed = System.currentTimeMillis() - stateStartTime
                            if (elapsed > 12000L) { // 12s Neutral Recovery Failsafe
                                emitLog("Searching timeout (>12s). Triggering Neutral Recovery Failsafe...")
                                _state.value = MacroState.STATE_RECOVERY
                                continue
                            }

                            // Fast Screen Classifier
                            val screenState = visionEngine.classifyCurrentScreen()
                            if (screenState == DetectedScreenState.MATCH_FOUND || isOpponentLoaded()) {
                                currentSearchCount++
                                _stats.value = _stats.value.copy(totalSearches = _stats.value.totalSearches + 1)
                                emitLog("Opponent Found (#$currentSearchCount)! Evaluating localized Loot ROI...")
                                gestureDispatcher.humanSleep(350L, 80L)
                                _state.value = MacroState.STATE_EVALUATE
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                gestureDispatcher.humanSleep(500L, 100L)
                            }
                        }

                        MacroState.STATE_EVALUATE -> {
                            val loot = evaluateLocalizedLoot()
                            _latestLoot.value = loot
                            emitLog("Loot Detected -> Gold: %,d | Elixir: %,d".format(loot.gold, loot.elixir))

                            val meetsRequirement = (loot.gold >= lootConfig.minGold && loot.elixir >= lootConfig.minElixir)
                            if (meetsRequirement) {
                                emitLog("🎯 TARGET ACQUIRED! Commencing ${_selectedPreset.value.displayName} deployment!")
                                _stats.value = _stats.value.copy(
                                    attacksExecuted = _stats.value.attacksExecuted + 1,
                                    totalGoldLooted = _stats.value.totalGoldLooted + loot.gold,
                                    totalElixirLooted = _stats.value.totalElixirLooted + loot.elixir
                                )
                                _state.value = MacroState.STATE_DEPLOY
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                emitLog("Loot below target. Skipping with randomized delay...")
                                tapNextButton()
                                gestureDispatcher.humanSleep(250L, 40L) // 250ms +/- 40ms
                                _state.value = MacroState.STATE_SEARCHING
                                stateStartTime = System.currentTimeMillis()
                            }
                        }

                        MacroState.STATE_DEPLOY -> {
                            emitLog("Executing army deployment: ${_selectedPreset.value.displayName}...")
                            when (_selectedPreset.value) {
                                FarmingPreset.DRAGON_EDRAG_WAVE -> deployDragonWave()
                                FarmingPreset.SNEAKY_GOBLIN_SNIPE -> deploySneakyGoblinSnipe()
                                FarmingPreset.BARCH_PERIMETER_SPREAD -> deployBarchPerimeterSpread()
                            }

                            // Anti-Spam Lockout Delay (35s - 50s)
                            val minBattleSec = battleConfig.minBattleDurationSec.coerceIn(30, 45)
                            val maxBattleSec = battleConfig.maxBattleDurationSec.coerceIn(45, 60)
                            val battleDurationMs = (minBattleSec + random.nextInt(maxBattleSec - minBattleSec + 1)) * 1000L

                            emitLog("🛡️ Server Lockout Protection: Maintaining battle for ${battleDurationMs / 1000}s...")
                            gestureDispatcher.humanSleep(battleDurationMs, 2000L)

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
                                if (elapsed > 20000L) {
                                    _state.value = MacroState.STATE_RECOVERY
                                } else {
                                    gestureDispatcher.humanSleep(1000L, 200L)
                                }
                            }
                        }

                        MacroState.STATE_RECOVERY -> {
                            emitLog("Executing Recovery: Tapping neutral safe zones and dismissing dialogs...")
                            executeNeutralRecovery()
                            gestureDispatcher.humanSleep(1800L, 300L)
                            _state.value = MacroState.STATE_HOME
                            stateStartTime = System.currentTimeMillis()
                        }
                    }
                } catch (ce: CancellationException) {
                    emitLog("Farming engine stopped.")
                    break
                } catch (e: Exception) {
                    emitLog("FSM Error: ${e.localizedMessage}")
                    Log.e(TAG, "Exception in Farming loop", e)
                    gestureDispatcher.humanSleep(1500L, 300L)
                }
            }
        }
    }

    private fun startBackgroundFailsafeWatcher() {
        failsafeJob?.cancel()
        failsafeJob = scope.launch {
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
                                val adjPt = cutoutManager.adjustCoordinate(PointF(pt.x.toFloat(), pt.y.toFloat()))
                                emitLog("⚠️ Disconnect Dialog detected! Tapping Reload...")
                                gestureDispatcher.humanTap(adjPt.x, adjPt.y)
                                recovered = true
                            }
                        }

                        if (!recovered && tryAgainDialogTemplate != null) {
                            val pt = visionEngine.findTemplate(screenMat, tryAgainDialogTemplate!!, 0.75f)
                            if (pt != null) {
                                val adjPt = cutoutManager.adjustCoordinate(PointF(pt.x.toFloat(), pt.y.toFloat()))
                                emitLog("⚠️ Connection Error detected! Tapping Try Again...")
                                gestureDispatcher.humanTap(adjPt.x, adjPt.y)
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
                    Log.e(TAG, "Error in failsafe watcher", e)
                }
            }
        }
    }

    private suspend fun executeHomeAttackSequence(): Boolean {
        val scaler = visionEngine.resolutionScaler

        val rawAttack = scaler.scalePoint(COORD_HOME_ATTACK.x, COORD_HOME_ATTACK.y)
        val adjAttack = cutoutManager.adjustCoordinate(rawAttack)

        gestureDispatcher.humanTap(adjAttack.x, adjAttack.y)
        gestureDispatcher.humanSleep(900L, 150L)

        val rawMatch = scaler.scalePoint(COORD_FIND_MATCH.x, COORD_FIND_MATCH.y)
        val adjMatch = cutoutManager.adjustCoordinate(rawMatch)

        gestureDispatcher.humanTap(adjMatch.x, adjMatch.y)
        gestureDispatcher.humanSleep(1100L, 200L)
        return true
    }

    private fun isOpponentLoaded(): Boolean {
        val screenMat = visionEngine.captureScreenMat() ?: return false
        try {
            if (nextBtnTemplate != null) {
                val pt = visionEngine.findTemplate(screenMat, nextBtnTemplate!!, 0.70f)
                if (pt != null) return true
            }
            return true
        } finally {
            screenMat.release()
        }
    }

    private suspend fun evaluateLocalizedLoot(): LootSnapshot {
        val scaler = visionEngine.resolutionScaler
        val scaledRect = scaler.scaleRect(
            LOOT_HUD_BOX_1080P.left,
            LOOT_HUD_BOX_1080P.top,
            LOOT_HUD_BOX_1080P.right,
            LOOT_HUD_BOX_1080P.bottom
        )
        val adjRect = cutoutManager.adjustRect(scaledRect)
        val (gold, elixir) = visionEngine.readLootValues(adjRect)
        return LootSnapshot(gold = gold, elixir = elixir)
    }

    private suspend fun tapNextButton() {
        val scaler = visionEngine.resolutionScaler
        val rawNext = scaler.scalePoint(COORD_NEXT_BUTTON.x, COORD_NEXT_BUTTON.y)
        val adjNext = cutoutManager.adjustCoordinate(rawNext)

        gestureDispatcher.humanTap(adjNext.x, adjNext.y, jitterRadius = 6f)
    }

    // =========================================================================
    // ARMY DEPLOYMENT PRESETS
    // =========================================================================

    /**
     * Preset 1: Dragon / E-Drag Wave.
     * Line-spread dragons along outer border + Rage/Freeze core drops + Hero abilities.
     */
    private suspend fun deployDragonWave() {
        val scaler = visionEngine.resolutionScaler

        // 1. Select Dragon Slot (Slot 1 & 2)
        for (slotIdx in 0..1) {
            val slotRaw = scaler.scalePoint(TROOP_SLOTS_1080P[slotIdx].x, TROOP_SLOTS_1080P[slotIdx].y)
            val slotAdj = cutoutManager.adjustCoordinate(slotRaw)
            gestureDispatcher.humanTap(slotAdj.x, slotAdj.y)
            gestureDispatcher.humanSleep(150L, 30L)

            // Line spread along bottom-left red border
            val pStart = cutoutManager.adjustCoordinate(scaler.scalePoint(350f, 850f))
            val pEnd = cutoutManager.adjustCoordinate(scaler.scalePoint(960f, 950f))

            val linePoints = mutableListOf<PointF>()
            val drops = 5
            for (i in 0..drops) {
                val alpha = i.toFloat() / drops
                val dx = pStart.x + alpha * (pEnd.x - pStart.x)
                val dy = pStart.y + alpha * (pEnd.y - pStart.y)
                linePoints.add(PointF(dx, dy))
            }
            gestureDispatcher.humanMultiTouchDeploy(linePoints)
            gestureDispatcher.humanSleep(200L, 40L)
        }

        // 2. Deploy Spells (Rage / Freeze at core)
        for (spellIdx in SPELL_SLOTS_1080P.indices) {
            val spellRaw = scaler.scalePoint(SPELL_SLOTS_1080P[spellIdx].x, SPELL_SLOTS_1080P[spellIdx].y)
            val spellAdj = cutoutManager.adjustCoordinate(spellRaw)
            gestureDispatcher.humanTap(spellAdj.x, spellAdj.y)
            gestureDispatcher.humanSleep(150L, 30L)

            val coreTarget = cutoutManager.adjustCoordinate(scaler.scalePoint(960f, 540f))
            gestureDispatcher.humanTap(coreTarget.x, coreTarget.y, jitterRadius = 25f)
            gestureDispatcher.humanSleep(250L, 50L)
        }

        // 3. Deploy Heroes & Trigger Staggered Abilities
        deployHeroesWithAbilities()
    }

    /**
     * Preset 2: Sneaky Goblin Collector Snipe.
     * Perimeter multi-touch taps targeting exterior collectors.
     */
    private suspend fun deploySneakyGoblinSnipe() {
        val scaler = visionEngine.resolutionScaler

        // Select Goblin Slot (Slot 1)
        val slotRaw = scaler.scalePoint(TROOP_SLOTS_1080P[0].x, TROOP_SLOTS_1080P[0].y)
        val slotAdj = cutoutManager.adjustCoordinate(slotRaw)
        gestureDispatcher.humanTap(slotAdj.x, slotAdj.y)
        gestureDispatcher.humanSleep(150L, 30L)

        // Exterior Perimeter Targets
        val perimeterTaps = listOf(
            scaler.scalePoint(400f, 300f),
            scaler.scalePoint(1520f, 300f),
            scaler.scalePoint(400f, 800f),
            scaler.scalePoint(1520f, 800f),
            scaler.scalePoint(960f, 200f),
            scaler.scalePoint(960f, 900f)
        )

        for (pt in perimeterTaps) {
            val adj = cutoutManager.adjustCoordinate(pt)
            // Tap 2-3 goblins per collector
            repeat(3) {
                gestureDispatcher.humanTap(adj.x, adj.y, jitterRadius = 12f)
                gestureDispatcher.humanSleep(100L, 20L)
            }
        }
    }

    /**
     * Preset 3: BARCH Perimeter Spread.
     * Staggered Barbarian / Archer circle spread around base border.
     */
    private suspend fun deployBarchPerimeterSpread() {
        val scaler = visionEngine.resolutionScaler

        // Alternate between Barbarians (Slot 1) and Archers (Slot 2)
        for (slotIdx in 0..1) {
            val slotRaw = scaler.scalePoint(TROOP_SLOTS_1080P[slotIdx].x, TROOP_SLOTS_1080P[slotIdx].y)
            val slotAdj = cutoutManager.adjustCoordinate(slotRaw)
            gestureDispatcher.humanTap(slotAdj.x, slotAdj.y)
            gestureDispatcher.humanSleep(150L, 30L)

            val circlePoints = listOf(
                scaler.scalePoint(400f, 250f),
                scaler.scalePoint(960f, 160f),
                scaler.scalePoint(1520f, 250f),
                scaler.scalePoint(1650f, 540f),
                scaler.scalePoint(1520f, 830f),
                scaler.scalePoint(960f, 920f),
                scaler.scalePoint(400f, 830f),
                scaler.scalePoint(270f, 540f)
            ).map { cutoutManager.adjustCoordinate(it) }

            gestureDispatcher.humanMultiTouchDeploy(circlePoints)
            gestureDispatcher.humanSleep(300L, 50L)
        }

        deployHeroesWithAbilities()
    }

    private suspend fun deployHeroesWithAbilities() {
        val scaler = visionEngine.resolutionScaler

        for (heroIdx in HERO_SLOTS_1080P.indices) {
            val heroRaw = scaler.scalePoint(HERO_SLOTS_1080P[heroIdx].x, HERO_SLOTS_1080P[heroIdx].y)
            val heroAdj = cutoutManager.adjustCoordinate(heroRaw)

            val dropPoint = cutoutManager.adjustCoordinate(scaler.scalePoint(960f, 900f))

            gestureDispatcher.deployHeroWithEquipment(
                heroSlotX = heroAdj.x,
                heroSlotY = heroAdj.y,
                dropX = dropPoint.x,
                dropY = dropPoint.y,
                abilityTriggerDelayMs = 0L
            )
            gestureDispatcher.humanSleep(250L, 50L)
        }

        // Staggered Equipment Ability Activations
        gestureDispatcher.humanSleep(battleConfig.heroAbilityDelayMs, 1000L)
        for (heroIdx in HERO_SLOTS_1080P.indices) {
            val heroRaw = scaler.scalePoint(HERO_SLOTS_1080P[heroIdx].x, HERO_SLOTS_1080P[heroIdx].y)
            val heroAdj = cutoutManager.adjustCoordinate(heroRaw)
            gestureDispatcher.humanTap(heroAdj.x, heroAdj.y, jitterRadius = 4f)
            gestureDispatcher.humanSleep(350L, 60L)
        }
    }

    private suspend fun executeReturnHome(): Boolean {
        val scaler = visionEngine.resolutionScaler

        val rawEnd = scaler.scalePoint(COORD_END_BATTLE.x, COORD_END_BATTLE.y)
        val adjEnd = cutoutManager.adjustCoordinate(rawEnd)
        gestureDispatcher.humanTap(adjEnd.x, adjEnd.y)
        gestureDispatcher.humanSleep(800L, 150L)

        val rawConfirm = scaler.scalePoint(COORD_CONFIRM_END.x, COORD_CONFIRM_END.y)
        val adjConfirm = cutoutManager.adjustCoordinate(rawConfirm)
        gestureDispatcher.humanTap(adjConfirm.x, adjConfirm.y)
        gestureDispatcher.humanSleep(1200L, 200L)

        val rawHome = scaler.scalePoint(COORD_RETURN_HOME.x, COORD_RETURN_HOME.y)
        val adjHome = cutoutManager.adjustCoordinate(rawHome)
        gestureDispatcher.humanTap(adjHome.x, adjHome.y)
        return true
    }

    private suspend fun executeNeutralRecovery() {
        val scaler = visionEngine.resolutionScaler
        val rawSafe = scaler.scalePoint(COORD_SAFE_ZONE.x, COORD_SAFE_ZONE.y)
        val adjSafe = cutoutManager.adjustCoordinate(rawSafe)

        gestureDispatcher.humanTap(adjSafe.x, adjSafe.y, jitterRadius = 30f)
        gestureDispatcher.humanSleep(500L, 100L)
    }

    fun pause() {
        fsmJob?.cancel()
        fsmJob = null
        failsafeJob?.cancel()
        failsafeJob = null
        _state.value = MacroState.IDLE
        scope.launch { emitLog("Farming Engine paused.") }
    }

    fun stop() {
        pause()
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
