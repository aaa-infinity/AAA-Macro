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
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.aaa.macro.MainActivity
import com.aaa.macro.R
import com.aaa.macro.engine.CutoutManager
import com.aaa.macro.engine.FarmingFSM
import com.aaa.macro.engine.HumanGestureDispatcher
import com.aaa.macro.engine.OfflineVisionEngine
import com.aaa.macro.engine.ResolutionScaler
import com.aaa.macro.engine.ViewportDetector
import com.aaa.macro.engine.WakeManager
import com.aaa.macro.ui.FloatingOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.opencv.android.OpenCVLoader

/**
 * Enterprise Foreground Service hosting the Floating Mini-Hub UI.
 *
 * Enforces Android 14 Strict Startup Order:
 * - Executes startForeground() with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before creating VirtualDisplay.
 * - Instantiates ViewportDetector for ultra-wide pillarbox correction (19.5:9 / 20:9).
 */
open class FloatingMenuService : Service() {

    companion object {
        private const val TAG = "FloatingMenuService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "aaa_farming_service_channel"

        const val ACTION_START_WITH_PROJECTION = "com.aaa.macro.ACTION_START_WITH_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var wakeManager: WakeManager

    private var cutoutManager: CutoutManager? = null
    private var viewportDetector: ViewportDetector? = null
    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var farmingFSM: FarmingFSM? = null
    private var overlayView: FloatingOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "FloatingMenuService onCreate()")

        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV native library loading notice.")
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wakeManager = WakeManager(applicationContext)
        wakeManager.acquireWakeLock()

        createNotificationChannel()

        // Android 14 Enforcement: Start Foreground with MEDIA_PROJECTION immediately on create
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enforce Foreground status before any projection calls
        startForegroundWithNotification()

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
                Log.e(TAG, "Invalid projection credentials in onStartCommand.")
            }
        }
        return START_NOT_STICKY
    }

    private fun setupEnginesAndOverlay(resultCode: Int, projectionData: Intent) {
        // 1. Android 14 Enforcement: Ensure Foreground Service is ACTIVE before creating MediaProjection
        startForegroundWithNotification()

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

        // 2. Cutout Safe-Area & Viewport Detector
        val cutout = CutoutManager(applicationContext)
        cutout.updateCutoutInsets(windowManager)
        this.cutoutManager = cutout

        val viewport = ViewportDetector(screenWidth, screenHeight)
        this.viewportDetector = viewport

        // 3. Resolution Scaler & Offline Vision Engine (Creates VirtualDisplay after startForeground)
        val resolutionScaler = ResolutionScaler(screenWidth, screenHeight)
        val vision = OfflineVisionEngine(applicationContext, resolutionScaler)
        vision.initializeCapture(mediaProjection, screenWidth, screenHeight, metrics.densityDpi)
        this.visionEngine = vision

        // 4. Humanized Gesture Dispatcher
        val gestures = HumanGestureDispatcher { MacroAccessibilityService.instance }
        this.gestureDispatcher = gestures

        // 5. Farming Finite State Machine
        val fsm = FarmingFSM(
            context = applicationContext,
            visionEngine = vision,
            gestureDispatcher = gestures,
            cutoutManager = cutout,
            viewportDetector = viewport
        )
        this.farmingFSM = fsm

        // 6. Floating Overlay UI View
        overlayView?.detach()
        val overlay = FloatingOverlayView(
            context = applicationContext,
            windowManager = windowManager,
            farmingFSM = fsm,
            onCloseRequested = {
                stopSelf()
            }
        )
        overlay.attach()
        this.overlayView = overlay

        Log.i(TAG, "All Android 14 compliant farming engines and overlay attached successfully.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AAA Macro Farming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background vision capture and floating farming overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AAA Farming Macro Active")
            .setContentText("Automated multiplayer farming hub running.")
            .setSmallIcon(R.drawable.ic_launcher)
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

        wakeManager.releaseWakeLock()
        serviceScope.cancel()

        overlayView?.detach()
        overlayView = null

        farmingFSM?.release()
        farmingFSM = null

        visionEngine?.release()
        visionEngine = null

        super.onDestroy()
    }
}
