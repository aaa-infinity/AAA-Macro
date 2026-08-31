package com.aaa.macro.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Enterprise WakeLock & CPU Keep-Awake Controller.
 *
 * Manages PowerManager.PARTIAL_WAKE_LOCK lifecycle to prevent CPU sleep
 * and network desync during prolonged automated farming sessions.
 */
class WakeManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeManager"
        private const val WAKE_LOCK_TAG = "com.aaa.macro:FarmingWakeLock"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Acquires the partial WakeLock if not already held.
     */
    @Synchronized
    fun acquireWakeLock(timeoutMs: Long = 60 * 60 * 1000L) { // 1 hour max default
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                }
            }

            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(timeoutMs)
                Log.i(TAG, "Partial WakeLock acquired (timeout: ${timeoutMs / 1000}s).")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    /**
     * Releases the held WakeLock safely.
     */
    @Synchronized
    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "Partial WakeLock released.")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }
}
