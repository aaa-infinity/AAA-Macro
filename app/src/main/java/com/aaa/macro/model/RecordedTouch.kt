package com.aaa.macro.model

/**
 * Normalized Touch Data Model for Macrorify-Style Gesture Recording and Resolution-Independent Replay.
 */
data class RecordedTouch(
    val action: Int,          // MotionEvent.ACTION_DOWN (0), MOVE (2), UP (1)
    val xRatio: Float,        // Relative X (0.0 to 1.0) for resolution scaling
    val yRatio: Float,        // Relative Y (0.0 to 1.0)
    val timestampOffset: Long // Milliseconds since recording began
)
