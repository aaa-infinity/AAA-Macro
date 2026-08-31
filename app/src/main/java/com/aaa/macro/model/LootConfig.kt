package com.aaa.macro.model

/**
 * Enterprise Loot Configuration & Real-Time Metrics.
 */
data class LootConfig(
    var minGold: Int = 450_000,
    var minElixir: Int = 450_000,
    var minDarkElixir: Int = 3_000,
    var requireDeadBase: Boolean = false,
    var enableWallDump: Boolean = true,
    var wallDumpThresholdRatio: Float = 0.90f
)

/**
 * Snapshot of parsed on-screen loot values and base telemetry.
 */
data class LootSnapshot(
    val gold: Int = 0,
    val elixir: Int = 0,
    val darkElixir: Int = 0,
    val isDeadBase: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
