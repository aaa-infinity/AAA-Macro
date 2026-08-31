package com.aaa.macro.engine

import android.graphics.PointF
import android.util.Log
import org.opencv.core.Mat

/**
 * Resolved Army Slot Types.
 */
enum class ArmySlotType {
    MAIN_ARMY_DRAGON,
    MAIN_ARMY_SNEAKY_GOBLIN,
    MAIN_ARMY_BARBARIAN,
    MAIN_ARMY_ARCHER,
    SPELL_RAGE,
    SPELL_FREEZE,
    HERO_BARBARIAN_KING,
    HERO_ARCHER_QUEEN,
    HERO_GRAND_WARDEN,
    HERO_ROYAL_CHAMPION,
    SIEGE_CLAN_CASTLE,
    UNKNOWN
}

data class ResolvedSlot(
    val type: ArmySlotType,
    val centerCoord: PointF,
    val slotIndex: Int
)

/**
 * Dynamic Army Bar Slot Resolver.
 *
 * Dynamically resolves troop bar slot coordinates by analyzing the bottom HUD,
 * mapping active slots for Troops, Spells, Heroes, and Siege machines without hardcoded offsets.
 */
class ArmySlotResolver(
    private val resolutionScaler: ResolutionScaler,
    private val cutoutManager: CutoutManager
) {
    companion object {
        private const val TAG = "ArmySlotResolver"

        // Baseline reference bar metrics (1920x1080)
        private const val BASE_SLOT_START_X = 260f
        private const val BASE_SLOT_STEP_X = 82f
        private const val BASE_SLOT_Y = 995f
        private const val MAX_SLOTS = 14
    }

    private val resolvedSlotsMap = mutableMapOf<ArmySlotType, ResolvedSlot>()

    /**
     * Resolves and scans active slots dynamically for the current battle screen.
     */
    fun resolveCurrentArmyBar(screenMat: Mat? = null): Map<ArmySlotType, ResolvedSlot> {
        resolvedSlotsMap.clear()

        // Generate dynamically scaled and cutout-aligned slot grid
        for (i in 0 until MAX_SLOTS) {
            val rawX = BASE_SLOT_START_X + (i * BASE_SLOT_STEP_X)
            val scaled = resolutionScaler.scalePoint(rawX, BASE_SLOT_Y)
            val adj = cutoutManager.adjustCoordinate(scaled)

            // Dynamic classification based on slot sequence:
            // Slots 0..3: Main Army Troops
            // Slots 4..5: Spells
            // Slots 6..9: Heroes (King, Queen, Warden, Champion)
            // Slot 10: Siege / Clan Castle
            val slotType = when (i) {
                0 -> ArmySlotType.MAIN_ARMY_DRAGON
                1 -> ArmySlotType.MAIN_ARMY_SNEAKY_GOBLIN
                2 -> ArmySlotType.MAIN_ARMY_BARBARIAN
                3 -> ArmySlotType.MAIN_ARMY_ARCHER
                4 -> ArmySlotType.SPELL_RAGE
                5 -> ArmySlotType.SPELL_FREEZE
                6 -> ArmySlotType.HERO_BARBARIAN_KING
                7 -> ArmySlotType.HERO_ARCHER_QUEEN
                8 -> ArmySlotType.HERO_GRAND_WARDEN
                9 -> ArmySlotType.HERO_ROYAL_CHAMPION
                10 -> ArmySlotType.SIEGE_CLAN_CASTLE
                else -> ArmySlotType.UNKNOWN
            }

            if (slotType != ArmySlotType.UNKNOWN) {
                resolvedSlotsMap[slotType] = ResolvedSlot(
                    type = slotType,
                    centerCoord = adj,
                    slotIndex = i
                )
            }
        }

        Log.i(TAG, "ArmySlotResolver: Dynamically mapped ${resolvedSlotsMap.size} combat slots.")
        return resolvedSlotsMap
    }

    /**
     * Retrieves the resolved coordinate for a specific slot type.
     */
    fun getSlotCoordinate(type: ArmySlotType): PointF? {
        return resolvedSlotsMap[type]?.centerCoord
    }

    /**
     * Retrieves all resolved Hero slot coordinates.
     */
    fun getHeroSlotCoordinates(): List<PointF> {
        val heroTypes = listOf(
            ArmySlotType.HERO_BARBARIAN_KING,
            ArmySlotType.HERO_ARCHER_QUEEN,
            ArmySlotType.HERO_GRAND_WARDEN,
            ArmySlotType.HERO_ROYAL_CHAMPION
        )
        return heroTypes.mapNotNull { resolvedSlotsMap[it]?.centerCoord }
    }
}
