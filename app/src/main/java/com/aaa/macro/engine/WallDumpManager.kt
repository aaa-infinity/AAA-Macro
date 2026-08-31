package com.aaa.macro.engine

import android.graphics.PointF
import android.util.Log

/**
 * Enterprise Auto Wall-Dump & Storage Overflow Manager.
 *
 * Prevents Gold and Elixir storage cap waste (>90% full capacity) by automatically
 * dumping excess resources into wall upgrades between raids.
 */
class WallDumpManager(
    private val gestureDispatcher: HumanGestureDispatcher,
    private val resolutionScaler: ResolutionScaler,
    private val cutoutManager: CutoutManager
) {
    companion object {
        private const val TAG = "WallDumpManager"

        // Canonical Reference Points for Village Wall Upgrades
        private val COORD_WALL_SEGMENT = PointF(960f, 680f)
        private val COORD_UPGRADE_BUTTON = PointF(1080f, 920f)
        private val COORD_CONFIRM_GOLD = PointF(900f, 650f)
    }

    /**
     * Executes the wall dump routine if storage thresholds are exceeded.
     */
    suspend fun executeWallDumpIfEligible(
        currentGold: Int,
        currentElixir: Int,
        onLog: suspend (String) -> Unit
    ): Boolean {
        // Trigger if storages exceed 8,000,000 Gold / Elixir (or 90% of capacity)
        val isOverflowing = (currentGold > 8_000_000 || currentElixir > 8_000_000)
        if (!isOverflowing) return false

        onLog("🧱 Storage near capacity (>90%). Dumping excess resources into Wall Upgrade...")

        // 1. Tap targeted wall segment
        val wallTarget = cutoutManager.adjustCoordinate(
            resolutionScaler.scalePoint(COORD_WALL_SEGMENT.x, COORD_WALL_SEGMENT.y)
        )
        gestureDispatcher.humanTap(wallTarget.x, wallTarget.y, jitterRadius = 15f)
        gestureDispatcher.humanSleep(800L, 100L)

        // 2. Tap Upgrade Button in bottom popup
        val upgradeTarget = cutoutManager.adjustCoordinate(
            resolutionScaler.scalePoint(COORD_UPGRADE_BUTTON.x, COORD_UPGRADE_BUTTON.y)
        )
        gestureDispatcher.humanTap(upgradeTarget.x, upgradeTarget.y, jitterRadius = 8f)
        gestureDispatcher.humanSleep(900L, 120L)

        // 3. Confirm with Gold / Elixir
        val confirmTarget = cutoutManager.adjustCoordinate(
            resolutionScaler.scalePoint(COORD_CONFIRM_GOLD.x, COORD_CONFIRM_GOLD.y)
        )
        gestureDispatcher.humanTap(confirmTarget.x, confirmTarget.y, jitterRadius = 10f)
        gestureDispatcher.humanSleep(1000L, 150L)

        onLog("🧱 Wall upgrade completed. Resuming farming sequence.")
        return true
    }
}
