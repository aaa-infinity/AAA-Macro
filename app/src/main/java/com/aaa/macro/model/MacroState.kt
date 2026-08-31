package com.aaa.macro.model

/**
 * Finite State Machine states for the AAA Macro automation loop.
 */
enum class MacroState(val displayName: String) {
    /** Macro is stopped or paused by the user */
    IDLE("Idle / Paused"),

    /** In village home base; preparing to initiate attack search */
    STATE_HOME("Home Village"),

    /** Cloud transition or matchmaking screen actively searching for opponent */
    STATE_SEARCHING("Searching Match"),

    /** Base loaded; performing ML Kit OCR on Gold, Elixir, and Dark Elixir */
    STATE_EVALUATE("Evaluating Loot"),

    /** Target loot threshold met; executing anti-detection troop deployment */
    STATE_DEPLOY("Deploying Troops"),

    /** Battle completed or ended; returning to village home base */
    STATE_RETURN_HOME("Returning Home"),

    /** Failsafe recovery state; clears unexpected popups or connection dialogs */
    STATE_RECOVERY("Failsafe Recovery")
}
