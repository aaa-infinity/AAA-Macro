package com.aaa.macro.model

/**
 * 2026 Optimized Battle Configuration.
 *
 * Includes parameters for:
 * - Spam-Attack Lockout Protection (randomized 35s - 55s battle duration)
 * - Instant Training Flow (zero cooldown between raids)
 * - Hero Equipment ability trigger timings
 * - Multi-touch troop funneling
 */
data class BattleConfig(
    val deployDelayBaseMs: Long = 110L,
    val deployDelayVarianceMs: Long = 30L,
    val minBattleDurationSec: Int = 35,
    val maxBattleDurationSec: Int = 55,
    val heroAbilityDelayMs: Long = 8000L,
    val maxSearchCount: Int = 100,
    val searchTimeoutMs: Long = 20000L,
    val troopSlotCount: Int = 4,
    val heroSlotCount: Int = 4,
    val wavesPerSide: Int = 3,
    val enableInstantRetrain: Boolean = true
)
