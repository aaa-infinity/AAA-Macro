package com.aaa.macro.engine

import android.graphics.PointF
import android.util.Log
import java.util.Random

/**
 * Enterprise Dragon / Electro-Dragon Wave Deployment Orchestrator.
 *
 * Implements a 7-Stage Funnel & Main Wave Penetration Sequence:
 * 1. Flank Funnel Anchors (1 Dragon Far-Left, 1 Dragon Far-Right)
 * 2. Funnel Timing Delay (3.0s - 4.2s)
 * 3. Multi-Touch Main Dragon Wave Line Spread
 * 4. Support Heroes Behind Wave (1.5s offset)
 * 5. Core Rage & Freeze Spell Dispersion
 * 6. Staggered Hero Equipment Ability Triggers (15s - 25s)
 * 7. Anti-Spam Server Lockout Duration (40s - 55s)
 */
class DragonWaveStrategy(
    private val gestureDispatcher: HumanGestureDispatcher,
    private val slotResolver: ArmySlotResolver,
    private val resolutionScaler: ResolutionScaler,
    private val cutoutManager: CutoutManager
) {
    companion object {
        private const val TAG = "DragonWaveStrategy"
    }

    private val random = Random()

    /**
     * Executes the complete 7-stage professional Dragon / E-Drag attack.
     */
    suspend fun executeAttackSequence(
        onLog: suspend (String) -> Unit
    ) {
        onLog("🐉 Stage 1: Creating Funnel (Flank Anchors)...")
        executeFunnelCreation()

        onLog("⏳ Stage 2: Waiting for flank collapse (3.5s)...")
        val funnelDelay = 3000L + random.nextInt(1200) // 3.0s - 4.2s
        gestureDispatcher.humanSleep(funnelDelay, 100L)

        onLog("🌊 Stage 3: Deploying Main Dragon Wave (Bézier Line Spread)...")
        executeMainDragonWave()

        onLog("👑 Stage 4: Deploying Support Heroes behind wave (1.5s offset)...")
        gestureDispatcher.humanSleep(1500L, 200L)
        executeSupportHeroes()

        onLog("⚡ Stage 5: Dropping Core Rage & Freeze Spells...")
        executeSpellDeployment()

        onLog("🗡️ Stage 6: Staggering Hero Equipment Abilities (15s - 25s)...")
        val abilityStagger = 15000L + random.nextInt(10000) // 15s to 25s
        gestureDispatcher.humanSleep(abilityStagger, 500L)
        triggerHeroEquipmentAbilities()

        val minCombatSec = 40 + random.nextInt(16) // 40s - 55s
        onLog("🛡️ Stage 7: Maintaining combat duration (${minCombatSec}s) to bypass server lockouts...")
        gestureDispatcher.humanSleep(minCombatSec * 1000L, 2000L)
    }

    private suspend fun executeFunnelCreation() {
        val dragonSlot = slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_DRAGON) ?: return
        gestureDispatcher.humanTap(dragonSlot.x, dragonSlot.y, jitterRadius = 4f)
        gestureDispatcher.humanSleep(180L, 30L)

        // Far-Left Flank Anchor
        val leftAnchor = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(320f, 780f))
        gestureDispatcher.humanTap(leftAnchor.x, leftAnchor.y, jitterRadius = 8f)
        gestureDispatcher.humanSleep(220L, 40L)

        // Far-Right Flank Anchor
        val rightAnchor = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(1600f, 780f))
        gestureDispatcher.humanTap(rightAnchor.x, rightAnchor.y, jitterRadius = 8f)
    }

    private suspend fun executeMainDragonWave() {
        val dragonSlot = slotResolver.getSlotCoordinate(ArmySlotType.MAIN_ARMY_DRAGON) ?: return
        gestureDispatcher.humanTap(dragonSlot.x, dragonSlot.y, jitterRadius = 4f)
        gestureDispatcher.humanSleep(150L, 30L)

        // Main line spread along bottom-center red line
        val pStart = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(500f, 920f))
        val pEnd = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(1420f, 920f))

        val wavePoints = mutableListOf<PointF>()
        val drops = 6
        for (i in 0..drops) {
            val alpha = i.toFloat() / drops
            val wx = pStart.x + alpha * (pEnd.x - pStart.x)
            val wy = pStart.y + alpha * (pEnd.y - pStart.y)
            wavePoints.add(PointF(wx, wy))
        }

        // Deploy in 2 rapid multi-touch waves
        gestureDispatcher.humanMultiTouchDeploy(wavePoints)
        gestureDispatcher.humanSleep(250L, 40L)
        gestureDispatcher.humanMultiTouchDeploy(wavePoints)
    }

    private suspend fun executeSupportHeroes() {
        val heroCoords = slotResolver.getHeroSlotCoordinates()
        val heroDropBase = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(960f, 930f))

        for (heroSlot in heroCoords) {
            gestureDispatcher.deployHeroWithEquipment(
                heroSlotX = heroSlot.x,
                heroSlotY = heroSlot.y,
                dropX = heroDropBase.x,
                dropY = heroDropBase.y,
                abilityTriggerDelayMs = 0L
            )
            gestureDispatcher.humanSleep(200L, 30L)
        }
    }

    private suspend fun executeSpellDeployment() {
        // 1. Rage Spell
        val rageSlot = slotResolver.getSlotCoordinate(ArmySlotType.SPELL_RAGE)
        if (rageSlot != null) {
            gestureDispatcher.humanTap(rageSlot.x, rageSlot.y)
            gestureDispatcher.humanSleep(150L, 30L)

            val coreTarget = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(960f, 620f))
            gestureDispatcher.humanTap(coreTarget.x, coreTarget.y, jitterRadius = 20f)
            gestureDispatcher.humanSleep(250L, 40L)
        }

        // 2. Freeze Spell
        val freezeSlot = slotResolver.getSlotCoordinate(ArmySlotType.SPELL_FREEZE)
        if (freezeSlot != null) {
            gestureDispatcher.humanTap(freezeSlot.x, freezeSlot.y)
            gestureDispatcher.humanSleep(150L, 30L)

            val defenseTarget = cutoutManager.adjustCoordinate(resolutionScaler.scalePoint(960f, 480f))
            gestureDispatcher.humanTap(defenseTarget.x, defenseTarget.y, jitterRadius = 20f)
        }
    }

    private suspend fun triggerHeroEquipmentAbilities() {
        val heroCoords = slotResolver.getHeroSlotCoordinates()
        for (heroSlot in heroCoords) {
            gestureDispatcher.humanTap(heroSlot.x, heroSlot.y, jitterRadius = 4f)
            gestureDispatcher.humanSleep(350L, 50L)
        }
    }
}
