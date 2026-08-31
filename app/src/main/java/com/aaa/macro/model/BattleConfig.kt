package com.aaa.macro.model

/**
 * Battle deployment parameters and coordinates configuration.
 */
data class BattleConfig(
    val deployDelayBaseMs: Long = 110L,
    val deployDelayVarianceMs: Long = 35L,
    val maxSearchCount: Int = 100,
    val searchTimeoutMs: Long = 20000L,
    val battleDurationMs: Long = 90000L,
    val troopSlotCount: Int = 4,
    val wavesPerSide: Int = 3
)
