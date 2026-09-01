package com.aaa.macro.engine

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.aaa.macro.model.BattleConfig
import com.aaa.macro.model.FarmingPreset
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
import java.util.Random

/**
 * Enterprise Farming Finite State Machine (FarmingFSM).
 *
 * Coordinates:
 * - 40s Cloud Search Watchdog (Cancel & Retry matchmaking)
 * - Ultra-wide Viewport & Pillarbox Offsets (19.5:9 / 20:9)
 * - Package Safety Watcher (Auto-pause on focus loss)
 * - Dynamic Army Slot Resolution & 7-Stage Dragon Funnel
 * - Auto Wall-Dump & Shadow-Filtering OCR
 */
class FarmingFSM(
    private val context: Context,
    val visionEngine: OfflineVisionEngine,
    val gestureDispatcher: HumanGestureDispatcher,
    val cutoutManager: CutoutManager,
    val viewportDetector: ViewportDetector = ViewportDetector(),
    val lootConfig: LootConfig = LootConfig(),
    val battleConfig: BattleConfig = BattleConfig()
) {
    companion object {
        private const val TAG = "FarmingFSM"

        // Canonical 1920x1080 Reference Coordinates
        private val COORD_HOME_ATTACK = PointF(105f, 950f)
        private val COORD_FIND_MATCH = PointF(1420f, 720f)
        private val COORD_NEXT_BUTTON = PointF(1750f, 890f)
        private val COORD_CANCEL_SEARCH = PointF(960f, 910f)
        private val COORD_END_BATTLE = PointF(110f, 840f)
        private val COORD_CONFIRM_END = PointF(1100f, 620f)
        private val COORD_RETURN_HOME = PointF(960f, 910f)
        private val COORD_SAFE_ZONE = PointF(960f, 450f)

        // Localized Top-Left Loot HUD Box
        private val LOOT_HUD_BOX_1080P = Rect(40, 50, 480, 240)
    }

    private val random = Random()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var fsmJob: Job? = null
    private var failsafeJob: Job? = null

    val slotResolver = ArmySlotResolver(visionEngine.resolutionScaler, cutoutManager)
    val dragonStrategy = DragonWaveStrategy(gestureDispatcher, slotResolver, visionEngine.resolutionScaler, cutoutManager)
    val wallDumpManager = WallDumpManager(gestureDispatcher, visionEngine.resolutionScaler, cutoutManager)

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

    private var reloadDialogTemplate: Mat? = null
    private var tryAgainDialogTemplate: Mat? = null

    init {
        loadTemplates()
        setupListeners()
    }

    private fun setupListeners() {
        // 1. Hardware Kill-Switch
        MacroAccessibilityService.registerKillSwitchListener {
            Log.w(TAG, "Hardware Kill-Switch triggered! Immediately pausing Farming FSM.")
            pause()
            _state.value = MacroState.IDLE
        }

        // 2. Package Safety Watcher: Auto-pause when game loses focus
        MacroAccessibilityService.registerFocusLostListener {
            if (_state.value != MacroState.IDLE) {
                Log.w(TAG, "Package focus lost. Pausing Farming FSM for safety.")
                pause()
            }
        }
    }

    private fun loadTemplates() {
        reloadDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_reload.png")
        tryAgainDialogTemplate = AssetHelper.loadMatFromAsset(context, "templates/dialog_try_again.png")
    }

    fun setPreset(preset: FarmingPreset) {
        _selectedPreset.value = preset
        scope.launch { emitLog("Army Preset: ${preset.displayName}") }
    }

    private suspend fun emitLog(message: String) {
        Log.i(TAG, message)
        _logStream.emit(message)
    }

    fun start() {
        if (fsmJob?.isActive == true) return

        startBackgroundFailsafeWatcher()

        fsmJob = scope.launch {
            emitLog("Starting Enterprise Farming Engine [${_selectedPreset.value.displayName}]...")
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
                            // Check Auto Wall-Dump if storages full
                            if (lootConfig.enableWallDump) {
                                wallDumpManager.executeWallDumpIfEligible(
                                    currentGold = _stats.value.totalGoldLooted,
                                    currentElixir = _stats.value.totalElixirLooted,
                                    onLog = { emitLog(it) }
                                )
                            }

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

                            // Cloud Search Timeout: 40-second watchdog
                            if (elapsed > 40000L) {
                                emitLog("☁️ Cloud search timeout (>40s). Tapping Cancel and restarting matchmaking...")
                                cancelCloudMatchmaking()
                                gestureDispatcher.humanSleep(2500L, 400L)
                                _state.value = MacroState.STATE_HOME
                                stateStartTime = System.currentTimeMillis()
                                continue
                            }

                            val screenState = visionEngine.classifyCurrentScreen()
                            if (screenState == DetectedScreenState.MATCH_FOUND || isOpponentReady()) {
                                currentSearchCount++
                                _stats.value = _stats.value.copy(totalSearches = _stats.value.totalSearches + 1)
                                emitLog("Opponent Base Found (#$currentSearchCount)! Parsing Gold/Elixir/DE...")
                                gestureDispatcher.humanSleep(350L, 60L)
                                _state.value = MacroState.STATE_EVALUATE
                                stateStartTime = System.currentTimeMillis()
                            } else {
                                gestureDispatcher.humanSleep(500L, 80L)
                            }
                        }

                        MacroState.STATE_EVALUATE -> {
                            val loot = evaluateLocalizedLoot()
                            _latestLoot.value = loot
                            emitLog("Loot Detected -> Gold: %,d | Elixir: %,d | DE: %,d".format(loot.gold, loot.elixir, loot.darkElixir))

                            val meetsGold = (loot.gold >= lootConfig.minGold)
                            val meetsElixir = (loot.elixir >= lootConfig.minElixir)
                            val meetsDarkElixir = (loot.darkElixir >= lootConfig.minDarkElixir)
                            val meetsDeadBase = (!lootConfig.requireDeadBase || loot.isDeadBase)

                            val meetsTarget = (meetsGold && meetsElixir && meetsDarkElixir && meetsDeadBase)

                            if (meetsTarget) {
                                emitLog("🎯 TARGET CRITERIA MET! Commencing attack sequence...")
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
                                gestureDispatcher.humanSleep(250L, 40L)
                                _state.value = MacroState.STATE_SEARCHING
                                stateStartTime = System.currentTimeMillis()
                            }
                        }

                        MacroState.STATE_DEPLOY -> {
                            slotResolver.resolveCurrentArmyBar()

                            when (_selectedPreset.value) {
                                FarmingPreset.DRAGON_EDRAG_WAVE -> {
                                    dragonStrategy.executeAttackSequence { emitLog(it) }
                                }
                                FarmingPreset.SNEAKY_GOBLIN_SNIPE -> {
                                    deploySneakyGoblinSnipe()
                                }
                                FarmingPreset.BARCH_PERIMETER_SPREAD -> {
                                    deployBarchSpread()
                                }
                            }

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
                            emitLog("Executing Recovery: Tapping safe zones & dismissing dialogs...")
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
                    Log.e(TAG, "Exception in loop", e)
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
                                val adjPt = mapTargetCoordinate(PointF(pt.x.toFloat(), pt.y.toFloat()))
                                emitLog("⚠️ Disconnect Dialog detected! Tapping Reload...")
                                gestureDispatcher.humanTap(adjPt.x, adjPt.y)
                                recovered = true
                            }
                        }

                        if (!recovered && tryAgainDialogTemplate != null) {
                            val pt = visionEngine.findTemplate(screenMat, tryAgainDialogTemplate!!, 0.75f)
                            if (pt != null) {
                                val adjPt = mapTargetCoordinate(PointF(pt.x.toFloat(), pt.y.toFloat()))
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

    private fun mapTargetCoordinate(canvasPoint: PointF): PointF {
        val viewportMapped = viewportDetector.mapToScreen(canvasPoint)
        return cutoutManager.adjustCoordinate(viewportMapped)
    }

    private suspend fun executeHomeAttackSequence(): Boolean {
        val adjAttack = mapTargetCoordinate(COORD_HOME_ATTACK)
        gestureDispatcher.humanTap(adjAttack.x, adjAttack.y)
        gestureDispatcher.humanSleep(900L, 150L)

        val adjMatch = mapTargetCoordinate(COORD_FIND_MATCH)
        gestureDispatcher.humanTap(adjMatch.x, adjMatch.y)
        gestureDispatcher.humanSleep(1100L, 200L)
        return true
    }

    private suspend fun cancelCloudMatchmaking() {
        val adjCancel = mapTargetCoordinate(COORD_CANCEL_SEARCH)
        gestureDispatcher.humanTap(adjCancel.x, adjCancel.y, jitterRadius = 10f)
    }

    private fun isOpponentReady(): Boolean {
        val screenMat = visionEngine.captureScreenMat() ?: return false
        screenMat.release()
        return true
    }

    private suspend fun evaluateLocalizedLoot(): LootSnapshot {
        val mappedRect = viewportDetector.mapRectToScreen(LOOT_HUD_BOX_1080P)
        val adjRect = cutoutManager.adjustRect(mappedRect)
        return visionEngine.readLootMetrics(adjRect)
    }

    private suspend fun tapNextButton() {
        val adjNext = mapTargetCoordinate(COORD_NEXT_BUTTON)
        gestureDispatcher.humanTap(adjNext.x, adjNext.y, jitterRadius = 6f)
    }

    private suspend fun deploySneakyGoblinSnipe() {
        val goblinSlot = slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_SNEAKY_GOBLIN)
            ?: slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_DRAGON)
            ?: return

        gestureDispatcher.humanTap(goblinSlot.x, goblinSlot.y, jitterRadius = 4f)
        gestureDispatcher.humanSleep(150L, 30L)

        val perimeterTargets = listOf(
            PointF(400f, 300f),
            PointF(1520f, 300f),
            PointF(400f, 800f),
            PointF(1520f, 800f),
            PointF(960f, 200f),
            PointF(960f, 900f)
        )

        for (pt in perimeterTargets) {
            val adj = mapTargetCoordinate(pt)
            repeat(3) {
                gestureDispatcher.humanTap(adj.x, adj.y, jitterRadius = 12f)
                gestureDispatcher.humanSleep(100L, 20L)
            }
        }

        gestureDispatcher.humanSleep(40000L, 2000L) // Anti-Spam duration
    }

    private suspend fun deployBarchSpread() {
        val slot1 = slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_BARBARIAN)
            ?: slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_DRAGON)
            ?: return

        gestureDispatcher.humanTap(slot1.x, slot1.y, jitterRadius = 4f)
        gestureDispatcher.humanSleep(150L, 30L)

        val circlePoints = listOf(
            PointF(400f, 250f),
            PointF(960f, 160f),
            PointF(1520f, 250f),
            PointF(1650f, 540f),
            PointF(1520f, 830f),
            PointF(960f, 920f),
            PointF(400f, 830f),
            PointF(270f, 540f)
        ).map { mapTargetCoordinate(it) }

        gestureDispatcher.humanMultiTouchDeploy(circlePoints)
        gestureDispatcher.humanSleep(40000L, 2000L) // Anti-Spam duration
    }

    private suspend fun executeReturnHome(): Boolean {
        val adjEnd = mapTargetCoordinate(COORD_END_BATTLE)
        gestureDispatcher.humanTap(adjEnd.x, adjEnd.y)
        gestureDispatcher.humanSleep(800L, 150L)

        val adjConfirm = mapTargetCoordinate(COORD_CONFIRM_END)
        gestureDispatcher.humanTap(adjConfirm.x, adjConfirm.y)
        gestureDispatcher.humanSleep(1200L, 200L)

        val adjHome = mapTargetCoordinate(COORD_RETURN_HOME)
        gestureDispatcher.humanTap(adjHome.x, adjHome.y)
        return true
    }

    private suspend fun executeNeutralRecovery() {
        val adjSafe = mapTargetCoordinate(COORD_SAFE_ZONE)
        gestureDispatcher.humanTap(adjSafe.x, adjSafe.y, jitterRadius = 30f)
        gestureDispatcher.humanSleep(500L, 100L)
    }

    fun pause() {
        fsmJob?.cancel()
        fsmJob = null
        failsafeJob?.cancel()
        failsafeJob = null
        _state.value = MacroState.IDLE
        scope.launch { emitLog("Macro paused.") }
    }

    fun stop() {
        pause()
    }

    fun release() {
        stop()
        reloadDialogTemplate?.release()
        tryAgainDialogTemplate?.release()
        AssetHelper.clearCache()
    }
}
