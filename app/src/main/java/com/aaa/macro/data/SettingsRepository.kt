package com.aaa.macro.data

import android.content.Context
import android.content.SharedPreferences
import com.aaa.macro.model.FarmingPreset
import com.aaa.macro.model.LootConfig

/**
 * Enterprise Settings & Telemetry Persistence Repository.
 *
 * Persists UI values, loot thresholds, selected strategy presets,
 * battery dimming flags, and floating overlay window (X, Y) coordinates.
 */
class SettingsRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "aaa_macro_enterprise_settings"

        private const val KEY_MIN_GOLD = "key_min_gold"
        private const val KEY_MIN_ELIXIR = "key_min_elixir"
        private const val KEY_MIN_DARK_ELIXIR = "key_min_dark_elixir"
        private const val KEY_SELECTED_PRESET = "key_selected_preset"
        private const val KEY_ENABLE_WALL_DUMP = "key_enable_wall_dump"
        private const val KEY_ENABLE_PASSIVE_HARVEST = "key_enable_passive_harvest"
        private const val KEY_ENABLE_BATTERY_DIMMING = "key_enable_battery_dimming"
        private const val KEY_OVERLAY_X = "key_overlay_x"
        private const val KEY_OVERLAY_Y = "key_overlay_y"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadLootConfig(): LootConfig {
        return LootConfig(
            minGold = prefs.getInt(KEY_MIN_GOLD, 450_000),
            minElixir = prefs.getInt(KEY_MIN_ELIXIR, 450_000),
            minDarkElixir = prefs.getInt(KEY_MIN_DARK_ELIXIR, 3_000),
            enableWallDump = prefs.getBoolean(KEY_ENABLE_WALL_DUMP, true)
        )
    }

    fun saveLootConfig(config: LootConfig) {
        prefs.edit()
            .putInt(KEY_MIN_GOLD, config.minGold)
            .putInt(KEY_MIN_ELIXIR, config.minElixir)
            .putInt(KEY_MIN_DARK_ELIXIR, config.minDarkElixir)
            .putBoolean(KEY_ENABLE_WALL_DUMP, config.enableWallDump)
            .apply()
    }

    fun loadSelectedPreset(): FarmingPreset {
        val name = prefs.getString(KEY_SELECTED_PRESET, FarmingPreset.DRAGON_EDRAG_WAVE.name)
        return try {
            FarmingPreset.valueOf(name ?: FarmingPreset.DRAGON_EDRAG_WAVE.name)
        } catch (_: Exception) {
            FarmingPreset.DRAGON_EDRAG_WAVE
        }
    }

    fun saveSelectedPreset(preset: FarmingPreset) {
        prefs.edit().putString(KEY_SELECTED_PRESET, preset.name).apply()
    }

    var isPassiveHarvestEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_PASSIVE_HARVEST, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_PASSIVE_HARVEST, value).apply()

    var isBatteryDimmingEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_BATTERY_DIMMING, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_BATTERY_DIMMING, value).apply()

    fun loadOverlayPosition(): Pair<Int, Int> {
        val x = prefs.getInt(KEY_OVERLAY_X, 60)
        val y = prefs.getInt(KEY_OVERLAY_Y, 120)
        return Pair(x, y)
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }
}
