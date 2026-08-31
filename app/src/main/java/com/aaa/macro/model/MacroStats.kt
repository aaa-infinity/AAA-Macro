package com.aaa.macro.model

/**
 * Enterprise Live Telemetry & Farming Statistics.
 */
data class MacroStats(
    val totalSearches: Int = 0,
    val attacksExecuted: Int = 0,
    val totalGoldLooted: Int = 0,
    val totalElixirLooted: Int = 0,
    val totalDarkElixirLooted: Int = 0,
    val totalWallsUpgraded: Int = 0
)
