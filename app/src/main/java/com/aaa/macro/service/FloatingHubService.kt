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
import android.graphics.PointF
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
import android.widget.ImageButton
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
import com.aaa.macro.engine.ScreenCaptureManager
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
 * Enterprise Foreground Service hosting the Macrorify-Style Floating Circular Dock UI.
 *
 * Features:
 * - Collapsed Mode: 48dp circular green/gold emblem docked to screen edge.
 * - Expanded Mode: Sleek horizontal pill containing Play/Pause, Army Selector, Status HUD, and Exit.
 * - Android 14 compliant MediaProjection lifecycle with instant ScreenCaptureManager.init().
 * - Direct Attack button targeting on Play tap.
 */
open class FloatingHubService : Service() {

    companion object {
        private const val TAG = "FloatingHubService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "aaa_macro_channel"

        const val ACTION_START_WITH_PROJECTION = "com.aaa.macro.ACTION_START_WITH_PROJECTION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "EXTRA_RESULT_DATA"

        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var wakeManager: WakeManager
    private lateinit var settingsRepository: SettingsRepository

    private var mediaProjection: MediaProjection? = null

    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var collapsedView: View? = null
    private var expandedMenu: View? = null
    private var btnPlayPause: ImageButton? = null
    private var btnStrategyMenu: View? = null
    private var tvArmyStrategy: TextView? = null
    private var tvStatusHud: TextView? = null
    private var btnCloseDock: ImageButton? = null

    private var cutoutManager: CutoutManager? = null
    private var viewportDetector: ViewportDetector? = null
    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var farmingFSM: FarmingFSM? = null

    private var isMacroActive = false
    private var selectedPreset = FarmingPreset.DRAGON_EDRAG_WAVE

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
        selectedPreset = settingsRepository.loadSelectedPreset()

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
            .setContentText("Circular floating dock active over Clash of Clans")
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
            val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
            floatingView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_hub, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (savedX, savedY) = settingsRepository.loadOverlayPosition()
            val initialX = if (savedX > 0) savedX else 80
            val initialY = if (savedY > 0) savedY else 180

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

            collapsedView = view.findViewById(R.id.dock_collapsed_view)
            expandedMenu = view.findViewById(R.id.dock_expanded_menu)
            btnPlayPause = view.findViewById(R.id.btn_play_pause)
            btnStrategyMenu = view.findViewById(R.id.btn_strategy_menu)
            tvArmyStrategy = view.findViewById(R.id.tv_army_strategy)
            tvStatusHud = view.findViewById(R.id.tv_status_hud)
            btnCloseDock = view.findViewById(R.id.btn_close_dock)

            tvArmyStrategy?.text = selectedPreset.displayName

            // Play / Pause Button Listener
            btnPlayPause?.setOnClickListener {
                onPlayPauseClicked()
            }

            // Strategy Selector Button Listener
            val strategyClickListener = View.OnClickListener {
                cycleStrategyPreset()
            }
            btnStrategyMenu?.setOnClickListener(strategyClickListener)
            tvArmyStrategy?.setOnClickListener(strategyClickListener)

            // Close Dock Button Listener
            btnCloseDock?.setOnClickListener {
                farmingFSM?.stop()
                stopSelf()
            }

            // Draggable touch listener with tap-to-expand detection on collapsedView
            collapsedView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialParamX = 0
                private var initialParamY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialParamX = params.x
                            initialParamY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (abs(dx) > 8 || abs(dy) > 8) {
                                isDragging = true
                                params.x = initialParamX + dx.toInt()
                                params.y = initialParamY + dy.toInt()
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
                            } else {
                                // Tap toggles expanded dock
                                toggleDockExpansion()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager.addView(view, params)
            Log.i(TAG, "Circular floating dock attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating widget", e)
        }
    }

    private fun toggleDockExpansion() {
        val menu = expandedMenu ?: return
        if (menu.visibility == View.VISIBLE) {
            menu.visibility = View.GONE
        } else {
            menu.visibility = View.VISIBLE
        }
    }

    private fun onPlayPauseClicked() {
        if (!ScreenCaptureManager.isReady() || mediaProjection == null) {
            Toast.makeText(
                this,
                "Screen capture not yet initialized. Please launch from AAA Macro dashboard.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val fsm = farmingFSM ?: return

        isMacroActive = !isMacroActive
        if (isMacroActive) {
            // Update UI to Active/Pause state
            btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
            btnPlayPause?.setBackgroundResource(R.drawable.bg_circle_close)
            tvStatusHud?.text = "STARTING"
            Toast.makeText(this, "Macro Started [${selectedPreset.displayName}]", Toast.LENGTH_SHORT).show()

            // 1. Launch FarmingFSM with active strategy
            fsm.start(strategy = selectedPreset)

            // 2. Direct Action Execution: Capture active screen, find "Attack" button and dispatch tap
            serviceScope.launch(Dispatchers.Default) {
                val screenMat = visionEngine?.captureScreenMat()
                if (screenMat != null) {
                    try {
                        val attackPt = visionEngine?.findAttackButton(screenMat)
                        if (attackPt != null) {
                            val mappedPoint = viewportDetector?.mapToScreen(PointF(attackPt.x.toFloat(), attackPt.y.toFloat()))
                                ?: PointF(attackPt.x.toFloat(), attackPt.y.toFloat())
                            val finalPoint = cutoutManager?.adjustCoordinate(mappedPoint) ?: mappedPoint
                            gestureDispatcher?.humanTap(finalPoint.x, finalPoint.y)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct attack tap exception", e)
                    } finally {
                        screenMat.release()
                    }
                }
            }
        } else {
            // Update UI to Paused state
            fsm.pause()
            btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
            btnPlayPause?.setBackgroundResource(R.drawable.bg_circle_play)
            tvStatusHud?.text = "PAUSED"
            Toast.makeText(this, "Macro Paused", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleStrategyPreset() {
        val presets = FarmingPreset.values()
        val nextIndex = (presets.indexOf(selectedPreset) + 1) % presets.size
        selectedPreset = presets[nextIndex]

        farmingFSM?.setPreset(selectedPreset)
        settingsRepository.saveSelectedPreset(selectedPreset)
        tvArmyStrategy?.text = selectedPreset.displayName
        Toast.makeText(this, "Strategy: ${selectedPreset.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "FloatingHubService onStartCommand()")
        startServiceForeground()

        if (intent != null) {
            val resultCode = intent.getIntExtra(
                "EXTRA_RESULT_CODE",
                intent.getIntExtra("RESULT_CODE", intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED))
            )

            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("EXTRA_RESULT_DATA", Intent::class.java)
                    ?: intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
                    ?: intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("EXTRA_RESULT_DATA")
                    ?: intent.getParcelableExtra("RESULT_DATA")
                    ?: intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                Log.i(TAG, "Acquiring MediaProjection from token extras...")
                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                this.mediaProjection = projection

                // Initialize ScreenCaptureManager virtual display immediately
                ScreenCaptureManager.init(this, projection)

                // Initialize vision engine, gesture engine, and FSM
                setupEngines(projection)
            }
        }

        return START_NOT_STICKY
    }

    private fun setupEngines(projection: MediaProjection) {
        try {
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
            vision.initializeCapture(projection, landscapeWidth, landscapeHeight, metrics.densityDpi)
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

            fsm.setPreset(selectedPreset)
            tvArmyStrategy?.text = selectedPreset.displayName

            // Observe FSM state and dynamically update floating widget HUD
            serviceScope.launch {
                fsm.state.collectLatest { state ->
                    tvStatusHud?.text = when (state) {
                        MacroState.IDLE -> "IDLE"
                        MacroState.STATE_HOME -> "HOME"
                        MacroState.STATE_SEARCHING -> "SEARCH"
                        MacroState.STATE_EVALUATE -> "EVAL"
                        MacroState.STATE_DEPLOY -> "DEPLOY"
                        MacroState.STATE_RETURN_HOME -> "RETURN"
                        MacroState.STATE_RECOVERY -> "RECOVER"
                    }

                    if (state == MacroState.IDLE) {
                        isMacroActive = false
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                        btnPlayPause?.setBackgroundResource(R.drawable.bg_circle_play)
                    } else {
                        isMacroActive = true
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                        btnPlayPause?.setBackgroundResource(R.drawable.bg_circle_close)
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

        mediaProjection?.stop()
        mediaProjection = null
    }
}
