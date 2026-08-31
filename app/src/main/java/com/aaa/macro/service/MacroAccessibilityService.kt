package com.aaa.macro.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Android Native Accessibility Service for No-Root Input Simulation.
 * Dispatches realistic, anti-detection human gestures to the active foreground game.
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroAccessibility"

        private var instanceRef: WeakReference<MacroAccessibilityService>? = null

        val instance: MacroAccessibilityService?
            get() = instanceRef?.get()

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        Log.i(TAG, "MacroAccessibilityService connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Optional monitoring of foreground window changes
    }

    override fun onInterrupt() {
        Log.w(TAG, "MacroAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "MacroAccessibilityService unbinding.")
        instanceRef = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "MacroAccessibilityService destroyed.")
        instanceRef = null
        super.onDestroy()
    }
}
