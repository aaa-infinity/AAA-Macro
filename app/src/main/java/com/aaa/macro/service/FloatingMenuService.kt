package com.aaa.macro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.aaa.macro.MainActivity
import com.aaa.macro.R
import com.aaa.macro.engine.HumanGestureDispatcher
import com.aaa.macro.engine.MacroStateMachine
import com.aaa.macro.engine.OfflineVisionEngine
import com.aaa.macro.engine.ResolutionScaler
import com.aaa.macro.engine.VisionEngine
import com.aaa.macro.ui.FloatingOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * Foreground Service hosting the Floating Overlay UI,
 * MediaProjection Vision Engine, and Macro State Machine.
 */
class FloatingMenuService : Service() {

    companion object {
        private const val TAG = "FloatingMenuService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "aaa_macro_service_channel"

        const val ACTION_START_WITH_PROJECTION = "com.aaa.macro.ACTION_START_WITH_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var stateMachine: MacroStateMachine? = null
    private var overlayView: FloatingOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "FloatingMenuService onCreate()")

        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV native library failed to load via OpenCVLoader.")
        } else {
            Log.i(TAG, "OpenCV loaded successfully.")
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_WITH_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

            if (resultCode != 0 && projectionData != null) {
                setupEnginesAndOverlay(resultCode, projectionData)
            } else {
                Log.e(TAG, "Invalid projection credentials received in onStartCommand.")
            }
        }
        return START_NOT_STICKY
    }

    private fun setupEnginesAndOverlay(resultCode: Int, projectionData: Intent) {
        val mediaProjection: MediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)

        val metrics = resources.displayMetrics
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display ?: (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        val screenWidth = if (size.x > 0) size.x else metrics.widthPixels
        val screenHeight = if (size.y > 0) size.y else metrics.heightPixels

        val resolutionScaler = ResolutionScaler(screenWidth, screenHeight)
        val vision = OfflineVisionEngine(applicationContext, resolutionScaler)
        vision.initializeCapture(mediaProjection, screenWidth, screenHeight, metrics.densityDpi)
        this.visionEngine = vision

        val gestures = HumanGestureDispatcher { MacroAccessibilityService.instance }
        this.gestureDispatcher = gestures

        val machine = MacroStateMachine(applicationContext, vision, gestures)
        this.stateMachine = machine

        // Create and display floating overlay
        overlayView?.detach()
        val overlay = FloatingOverlayView(
            context = applicationContext,
            windowManager = windowManager,
            stateMachine = machine,
            onCloseRequested = {
                stopSelf()
            }
        )
        this.overlayView = overlay
        overlay.attach()

        // Observe state changes and update UI
        serviceScope.launch {
            machine.state.collectLatest { state ->
                overlay.updateState(state)
            }
        }

        serviceScope.launch {
            machine.latestLoot.collectLatest { loot ->
                overlay.updateLoot(loot.gold, loot.elixir)
            }
        }

        serviceScope.launch {
            machine.stats.collectLatest { stats ->
                overlay.updateSearches(stats.totalSearches)
            }
        }

        Log.i(TAG, "Engines and Floating Overlay fully initialized.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "FloatingMenuService onDestroy()")
        isRunning = false

        serviceScope.cancel()

        overlayView?.detach()
        overlayView = null

        stateMachine?.release()
        stateMachine = null

        visionEngine?.release()
        visionEngine = null

        super.onDestroy()
    }
}
