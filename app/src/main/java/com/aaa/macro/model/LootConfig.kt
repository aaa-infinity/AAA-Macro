package com.aaa.macro.model

/**
 * Loot filtering threshold configuration and realtime statistics.
 */
data class LootConfig(
    var minGold: Int = 400000,
    var minElixir: Int = 400000,
    var minDarkElixir: Int = 3000
)

data class LootSnapshot(
    val gold: Int = 0,
    val elixir: Int = 0,
    val darkElixir: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class MacroStats(
    var totalSearches: Int = 0,
    var attacksExecuted: Int = 0,
    var totalGoldLooted: Long = 0L,
    var totalElixirLooted: Long = 0L,
    var totalDarkElixirLooted: Long = 0L
)
