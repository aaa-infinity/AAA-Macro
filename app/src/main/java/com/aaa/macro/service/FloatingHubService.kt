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
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
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
import kotlin.math.abs

/**
 * Enterprise Foreground Service hosting the Floating Mini-Hub Pill UI.
 *
 * Strict Android 14 Compliance:
 * 1. NotificationChannel created FIRST in onCreate().
 * 2. startForeground(MEDIA_PROJECTION) called immediately.
 * 3. Floating Mini-Hub pill widget attached directly to WindowManager in onCreate().
 * 4. Vision Engine & FSM initialized upon receiving MediaProjection consent in onStartCommand().
 */
open class FloatingHubService : Service() {

    companion object {
        private const val TAG = "FloatingHubService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "aaa_farming_service_channel"

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

    private var btnPlayPause: ImageButton? = null
    private var tvStrategy: TextView? = null
    private var tvStatus: TextView? = null
    private var btnClose: ImageButton? = null

    private var cutoutManager: CutoutManager? = null
    private var viewportDetector: ViewportDetector? = null
    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var farmingFSM: FarmingFSM? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "FloatingHubService onCreate()")

        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV native library init check.")
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        wakeManager = WakeManager(applicationContext)
        wakeManager.acquireWakeLock()
        settingsRepository = SettingsRepository(applicationContext)

        // 1. Create NotificationChannel FIRST
        createNotificationChannel()

        // 2. Start Foreground immediately (Android 14 requirement)
        val notification = createNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            Log.i(TAG, "startForeground successfully activated with type: $foregroundType")
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating startForeground in onCreate", e)
        }

        // 3. Immediately display the Floating Mini-Hub Pill Widget
        initFloatingWidget()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingWidget() {
        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_hub, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (savedX, savedY) = settingsRepository.loadOverlayPosition()
            val initialXPos = if (savedX > 0) savedX else 120
            val initialYPos = if (savedY > 0) savedY else 120

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
                x = initialXPos
                y = initialYPos
            }

            val view = floatingView ?: return

            // Views
            btnPlayPause = view.findViewById(R.id.btn_play_pause)
            tvStrategy = view.findViewById(R.id.tv_strategy)
            tvStatus = view.findViewById(R.id.tv_status)
            btnClose = view.findViewById(R.id.btn_close)

            val savedPreset = settingsRepository.loadSelectedPreset()
            tvStrategy?.text = savedPreset.displayName

            // Play / Pause Toggle
            btnPlayPause?.setOnClickListener {
                toggleMacroState()
            }

            // Strategy Pill Toggle (Cycle presets on tap)
            tvStrategy?.setOnClickListener {
                cycleStrategyPreset()
            }

            // Close Button
            btnClose?.setOnClickListener {
                farmingFSM?.stop()
                stopSelf()
            }

            // Draggable touch listener
            val rootLayout = view.findViewById<View>(R.id.hub_root)
            rootLayout.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                try {
                                    windowManager.updateViewLayout(view, params)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error updating floating view layout", e)
                                }
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isDragging) {
                                settingsRepository.saveOverlayPosition(params.x, params.y)
                                return true
                            }
                        }
                    }
                    return false
                }
            })

            windowManager.addView(view, params)
            Log.i(TAG, "Floating Mini-Hub pill attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing floating widget", e)
        }
    }

    private fun toggleMacroState() {
        val fsm = farmingFSM
        if (fsm == null) {
            tvStatus?.text = "STANDBY"
            return
        }

        if (fsm.state.value == MacroState.IDLE) {
            fsm.start()
        } else {
            fsm.pause()
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
        tvStrategy?.text = nextPreset.displayName
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "FloatingHubService onStartCommand()")

        // Ensure foreground is active
        val notification = createNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand startForeground", e)
        }

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

            // Cutout & Viewport Detector
            val cutout = CutoutManager(applicationContext)
            cutout.updateCutoutInsets(windowManager)
            this.cutoutManager = cutout

            val viewport = ViewportDetector(screenWidth, screenHeight)
            this.viewportDetector = viewport

            // Scaler & Vision Engine
            val resolutionScaler = ResolutionScaler(screenWidth, screenHeight)
            val vision = OfflineVisionEngine(applicationContext, resolutionScaler)
            vision.initializeCapture(mediaProjection, screenWidth, screenHeight, metrics.densityDpi)
            this.visionEngine = vision

            // Humanized Gesture Dispatcher
            val gestures = HumanGestureDispatcher { MacroAccessibilityService.instance }
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
            tvStrategy?.text = savedPreset.displayName

            // Observe FSM state and dynamically update floating widget
            serviceScope.launch {
                fsm.state.collectLatest { state ->
                    tvStatus?.text = when (state) {
                        MacroState.IDLE -> "IDLE"
                        MacroState.STATE_HOME -> "HOME"
                        MacroState.STATE_SEARCHING -> "SEARCH"
                        MacroState.STATE_EVALUATE_LOOT -> "EVAL"
                        MacroState.STATE_DEPLOY -> "ATTACK"
                        MacroState.STATE_WATCH_BATTLE -> "BATTLE"
                        MacroState.STATE_SURRENDER -> "SURR"
                        MacroState.STATE_RETURN_HOME -> "RETURN"
                    }

                    if (state == MacroState.IDLE) {
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                        btnPlayPause?.setColorFilter(getColor(R.color.accent_green))
                    } else {
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                        btnPlayPause?.setColorFilter(getColor(R.color.accent_red))
                    }
                }
            }

            Log.i(TAG, "Engines and FSM successfully initialized with MediaProjection.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup engines", e)
        }
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

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AAA Macro Active")
            .setContentText("Floating farming widget active over Clash of Clans.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FloatingHubService onDestroy()")
        isRunning = false

        wakeManager.releaseWakeLock()
        serviceScope.cancel()

        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating view on destroy", e)
            }
        }
        floatingView = null

        farmingFSM?.release()
        farmingFSM = null

        visionEngine?.release()
        visionEngine = null
    }
}
