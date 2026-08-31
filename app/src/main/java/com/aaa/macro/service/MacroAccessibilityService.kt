package com.aaa.macro.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android Native Accessibility Service for No-Root Input Simulation.
 *
 * Features:
 * - High-speed dispatchGesture execution
 * - Hardware Kill-Switch (VOLUME_DOWN key event interceptor) for immediate macro abort
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroAccessibility"

        private var instanceRef: WeakReference<MacroAccessibilityService>? = null

        val instance: MacroAccessibilityService?
            get() = instanceRef?.get()

        val isRunning: Boolean
            get() = instance != null

        private val killSwitchListeners = CopyOnWriteArrayList<() -> Unit>()

        fun registerKillSwitchListener(listener: () -> Unit) {
            killSwitchListeners.add(listener)
        }

        fun unregisterKillSwitchListener(listener: () -> Unit) {
            killSwitchListeners.remove(listener)
        }

        fun triggerEmergencyAbort() {
            Log.w(TAG, "EMERGENCY KILL-SWITCH TRIGGERED. Aborting all macro operations.")
            for (listener in killSwitchListeners) {
                try {
                    listener.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking kill switch listener", e)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        Log.i(TAG, "MacroAccessibilityService connected successfully with Key Filter capability.")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            Log.w(TAG, "Hardware VOLUME_DOWN detected! Triggering instant emergency kill-switch.")
            triggerEmergencyAbort()

            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "🛑 AAA Macro Emergency Abort Activated (Volume Down)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true // Consume event
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Window state monitoring
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
