package com.aaa.macro.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Android Native Accessibility Service with Package Safety Watcher & Hardware Kill-Switch.
 *
 * Features:
 * - Singleton instance tracking for HumanGestureDispatcher
 * - Package Safety Watcher: Detects when com.supercell.clashofclans loses foreground focus and pauses macro.
 * - Hardware Kill-Switch: VOLUME_DOWN key event interceptor for instant abort.
 * - High-speed dispatchGesture execution.
 */
class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroAccessibility"
        private const val TARGET_GAME_PACKAGE = "com.supercell.clashofclans"

        var instance: MacroAccessibilityService? = null

        val isRunning: Boolean
            get() = instance != null

        private val killSwitchListeners = CopyOnWriteArrayList<() -> Unit>()
        private val focusLostListeners = CopyOnWriteArrayList<() -> Unit>()

        fun registerKillSwitchListener(listener: () -> Unit) {
            killSwitchListeners.add(listener)
        }

        fun unregisterKillSwitchListener(listener: () -> Unit) {
            killSwitchListeners.remove(listener)
        }

        fun registerFocusLostListener(listener: () -> Unit) {
            focusLostListeners.add(listener)
        }

        fun unregisterFocusLostListener(listener: () -> Unit) {
            focusLostListeners.remove(listener)
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

        fun triggerFocusLost() {
            Log.w(TAG, "Target game lost foreground focus. Pausing macro.")
            for (listener in focusLostListeners) {
                try {
                    listener.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking focus lost listener", e)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "MacroAccessibilityService connected successfully. Singleton instance assigned.")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            Log.w(TAG, "Hardware VOLUME_DOWN detected! Triggering instant emergency kill-switch.")
            triggerEmergencyAbort()

            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    "🛑 AAA Macro Emergency Abort (Volume Down)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true // Consume event
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Package Safety Watcher: Monitor foreground window changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val currentPkg = event.packageName?.toString() ?: return
            val myPkg = packageName

            // If another full-screen app or system UI comes to front, pause macro safety
            if (currentPkg != TARGET_GAME_PACKAGE &&
                currentPkg != myPkg &&
                !currentPkg.contains("systemui") &&
                !currentPkg.contains("inputmethod")
            ) {
                Log.d(TAG, "Non-game window detected ($currentPkg). Pausing macro.")
                triggerFocusLost()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "MacroAccessibilityService interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "MacroAccessibilityService unbinding.")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "MacroAccessibilityService destroyed.")
        instance = null
        super.onDestroy()
    }
}
