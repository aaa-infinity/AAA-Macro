package com.aaa.macro.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aaa.macro.MainActivity
import com.aaa.macro.R
import com.aaa.macro.data.SettingsRepository
import com.aaa.macro.engine.CutoutManager
import com.aaa.macro.engine.FarmingFSM
import com.aaa.macro.engine.HumanGestureDispatcher
import com.aaa.macro.engine.OfflineVisionEngine
import com.aaa.macro.engine.ResolutionScaler
import com.aaa.macro.engine.ViewportDetector
import com.aaa.macro.engine.WakeManager
import com.aaa.macro.model.FarmingPreset
import com.aaa.macro.model.MacroState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * Enterprise Foreground Service hosting the Floating Mini-Hub UI.
 *
 * Guaranteed visibility across all Android devices (including Samsung OneUI) using
 * ContextThemeWrapper with android.R.style.Theme_DeviceDefault_Light and high-contrast styling.
 */
open class FloatingHubService : Service() {

    companion object {
        private const val TAG = "FloatingHubService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "aaa_macro_channel"

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
    private lateinit var settingsRepository: SettingsRepository

    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var btnPlay: Button? = null
    private var tvArmyStrategy: TextView? = null
    private var btnClose: TextView? = null

    private var cutoutManager: CutoutManager? = null
    private var viewportDetector: ViewportDetector? = null
    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var farmingFSM: FarmingFSM? = null

    private var isMacroActive = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "FloatingHubService onCreate()")

        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV native library loading check.")
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wakeManager = WakeManager(applicationContext)
        wakeManager.acquireWakeLock()
        settingsRepository = SettingsRepository(applicationContext)

        startServiceForeground()
        showFloatingWidget()
    }

    private fun startServiceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AAA Macro Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background vision capture and floating farming overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AAA Macro Active")
            .setContentText("Floating controller active over Clash of Clans")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            Log.i(TAG, "startForeground activated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating startForeground", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWidget() {
        if (floatingView != null) return

        try {
            // ContextThemeWrapper ensures drawables, fonts, and button styles inflate reliably on Samsung OneUI & all OEMs
            val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_hub, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (savedX, savedY) = settingsRepository.loadOverlayPosition()
            val initialX = if (savedX > 0) savedX else 100
            val initialY = if (savedY > 0) savedY else 100

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }

            val view = floatingView ?: return

            btnPlay = view.findViewById(R.id.btn_play_pause)
            btnClose = view.findViewById(R.id.btn_close_hub)
            tvArmyStrategy = view.findViewById(R.id.tv_army_strategy)
            val root = view.findViewById<View>(R.id.hub_root)
            val dragHandle = view.findViewById<View>(R.id.tv_drag_handle)

            val savedPreset = settingsRepository.loadSelectedPreset()
            tvArmyStrategy?.text = savedPreset.displayName

            btnPlay?.setOnClickListener {
                togglePlayPause()
            }

            tvArmyStrategy?.setOnClickListener {
                cycleStrategyPreset()
            }

            btnClose?.setOnClickListener {
                farmingFSM?.stop()
                stopSelf()
            }

            // Draggable Touch Listener on root and drag handle
            val dragTouchListener = object : View.OnTouchListener {
                private var initialParamX = 0
                private var initialParamY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialParamX = params.x
                            initialParamY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialParamX + (event.rawX - initialTouchX).toInt()
                            params.y = initialParamY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating floating view layout", e)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            settingsRepository.saveOverlayPosition(params.x, params.y)
                            return true
                        }
                    }
                    return false
                }
            }

            dragHandle?.setOnTouchListener(dragTouchListener)
            root.setOnTouchListener(dragTouchListener)

            windowManager.addView(view, params)
            Log.i(TAG, "Floating hub view attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating widget", e)
        }
    }

    private fun togglePlayPause() {
        val fsm = farmingFSM
        if (fsm == null) {
            Toast.makeText(this, "Screen capture not yet initialized. Please launch from dashboard.", Toast.LENGTH_SHORT).show()
            return
        }

        isMacroActive = !isMacroActive
        if (isMacroActive) {
            fsm.start()
            btnPlay?.text = "PAUSE"
            btnPlay?.setBackgroundColor(0xFFEF4444.toInt())
            Toast.makeText(this, "Macro Started", Toast.LENGTH_SHORT).show()
        } else {
            fsm.pause()
            btnPlay?.text = "START"
            btnPlay?.setBackgroundColor(0xFFF59E0B.toInt())
            Toast.makeText(this, "Macro Paused", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleStrategyPreset() {
        val fsm = farmingFSM
        val presets = FarmingPreset.values()
        val currentPreset = fsm?.selectedPreset?.value ?: settingsRepository.loadSelectedPreset()
        val nextIndex = (presets.indexOf(currentPreset) + 1) % presets.size
        val nextPreset = presets[nextIndex]

        fsm?.setPreset(nextPreset)
        settingsRepository.saveSelectedPreset(nextPreset)
        tvArmyStrategy?.text = nextPreset.displayName
        Toast.makeText(this, "Strategy: ${nextPreset.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "FloatingHubService onStartCommand()")
        startServiceForeground()

        if (intent != null) {
            val resultCode = intent.getIntExtra(
                "RESULT_CODE",
                intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            )

            val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
                    ?: intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("RESULT_DATA")
                    ?: intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

            if (resultCode == Activity.RESULT_OK && projectionData != null) {
                setupEngines(resultCode, projectionData)
            } else if (resultCode != Activity.RESULT_CANCELED && projectionData != null) {
                setupEngines(resultCode, projectionData)
            }
        }

        return START_NOT_STICKY
    }

    private fun setupEngines(resultCode: Int, projectionData: Intent) {
        try {
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

            val landscapeWidth = maxOf(screenWidth, screenHeight)
            val landscapeHeight = minOf(screenWidth, screenHeight)

            // Cutout & Viewport Detector
            val cutout = CutoutManager(applicationContext)
            cutout.updateCutoutInsets(windowManager)
            this.cutoutManager = cutout

            val viewport = ViewportDetector(landscapeWidth, landscapeHeight)
            this.viewportDetector = viewport

            // Scaler & Vision Engine
            val resolutionScaler = ResolutionScaler(landscapeWidth, landscapeHeight)
            val vision = OfflineVisionEngine(applicationContext, resolutionScaler)
            vision.initializeCapture(mediaProjection, landscapeWidth, landscapeHeight, metrics.densityDpi)
            this.visionEngine = vision

            // Humanized Gesture Dispatcher using singleton Accessibility instance
            val gestures = HumanGestureDispatcher()
            this.gestureDispatcher = gestures

            // Farming Finite State Machine
            val fsm = FarmingFSM(
                context = applicationContext,
                visionEngine = vision,
                gestureDispatcher = gestures,
                cutoutManager = cutout,
                viewportDetector = viewport
            )
            this.farmingFSM = fsm

            val savedPreset = settingsRepository.loadSelectedPreset()
            fsm.setPreset(savedPreset)
            tvArmyStrategy?.text = savedPreset.displayName

            // Observe FSM state and dynamically update floating widget button
            serviceScope.launch {
                fsm.state.collectLatest { state ->
                    if (state == MacroState.IDLE) {
                        isMacroActive = false
                        btnPlay?.text = "START"
                        btnPlay?.setBackgroundColor(0xFFF59E0B.toInt())
                    } else {
                        isMacroActive = true
                        btnPlay?.text = "PAUSE"
                        btnPlay?.setBackgroundColor(0xFFEF4444.toInt())
                    }
                }
            }

            Log.i(TAG, "Engines and FSM successfully initialized with MediaProjection.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup engines", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FloatingHubService onDestroy()")
        isRunning = false

        wakeManager.releaseWakeLock()
        serviceScope.cancel()

        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating view on destroy", e)
            }
            floatingView = null
        }

        farmingFSM?.release()
        farmingFSM = null

        visionEngine?.release()
        visionEngine = null
    }
}
