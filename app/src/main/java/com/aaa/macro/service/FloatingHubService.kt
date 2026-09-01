package com.aaa.macro.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
 * Enterprise Foreground Service hosting the Macrorify Circular Floating Dock Widget.
 *
 * Implements:
 * - 50x50dp circular floating emblem (dock_pill) with drag vs click tap detection.
 * - Horizontal expandable action toolbar (expanded_menu) with Play/Pause, Status HUD, and Close buttons.
 * - Instant token extra extraction (EXTRA_RESULT_CODE, EXTRA_RESULT_DATA) to prevent uninitialized capture.
 * - Direct Attack button targeting on Play tap.
 */
open class FloatingHubService : Service() {

    companion object {
        private const val TAG = "FloatingHubService"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "aaa_macro_dock"

        const val ACTION_START_WITH_PROJECTION = "com.aaa.macro.ACTION_START_WITH_PROJECTION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "EXTRA_RESULT_DATA"

        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var wakeManager: WakeManager

    private var mediaProjection: MediaProjection? = null
    private var isMacroRunning = false
    private var isExpanded = false

    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        if (!isExpanded) {
            rootView?.animate()?.alpha(0.4f)?.setDuration(300)?.start()
        }
    }

    private fun resetIdleFade() {
        rootView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, 3000)
    }

    private var dockPill: FrameLayout? = null
    private var expandedMenu: LinearLayout? = null
    private var btnPlay: ImageButton? = null
    private var btnClose: ImageButton? = null
    private var tvStatusTitle: TextView? = null
    private var tvStrategySubtitle: TextView? = null

    private var cutoutManager: CutoutManager? = null
    private var viewportDetector: ViewportDetector? = null
    private var visionEngine: OfflineVisionEngine? = null
    private var gestureDispatcher: HumanGestureDispatcher? = null
    private var farmingFSM: FarmingFSM? = null

    private var selectedPreset = FarmingPreset.DRAGON_EDRAG_WAVE

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.i(TAG, "FloatingHubService onCreate()")

        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV native library init check.")
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(applicationContext)
        selectedPreset = settingsRepository.loadSelectedPreset()
        wakeManager = WakeManager(applicationContext)
        wakeManager.acquireWakeLock()

        startForegroundServiceNotification()
        setupFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "FloatingHubService onStartCommand()")
        startForegroundServiceNotification()

        // Extract MediaProjection tokens passed from MainActivity
        if (intent != null) {
            val resultCode = intent.getIntExtra(
                "EXTRA_RESULT_CODE",
                intent.getIntExtra("RESULT_CODE", intent.getIntExtra(EXTRA_RESULT_CODE, 0))
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

            if (resultCode != 0 && resultData != null && mediaProjection == null) {
                Log.i(TAG, "Acquiring MediaProjection from token extras...")
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpManager.getMediaProjection(resultCode, resultData)
                this.mediaProjection = projection

                // Initialize ScreenCaptureManager virtual display immediately
                ScreenCaptureManager.init(this, projection)

                // Initialize vision engine, gesture dispatcher, and FSM
                setupEngines(projection)
            }
        }

        if (rootView == null) {
            setupFloatingView()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AAA Floating Controller",
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
            .setContentTitle("AAA Macro Running")
            .setContentText("Dock active over game canvas")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
            Log.i(TAG, "Foreground notification started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating startForeground", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingView() {
        if (rootView != null) return

        try {
            val themedContext = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
            rootView = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_hub, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val (savedX, savedY) = settingsRepository.loadOverlayPosition()
            val initialX = if (savedX > 0) savedX else 60
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

            val view = rootView ?: return

            dockPill = view.findViewById(R.id.dock_pill)
            expandedMenu = view.findViewById(R.id.expanded_menu)
            btnPlay = view.findViewById(R.id.btn_action_play)
            btnClose = view.findViewById(R.id.btn_action_close)
            tvStatusTitle = view.findViewById(R.id.tv_status_title)
            tvStrategySubtitle = view.findViewById(R.id.tv_strategy_subtitle)

            tvStrategySubtitle?.text = selectedPreset.shortName

            // Drag & Click Logic for the Circular Dock with Auto-Edge Magnetic Snapping
            dockPill?.setOnTouchListener(object : View.OnTouchListener {
                private var initX = 0
                private var initY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var isClick = false

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    resetIdleFade()
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = params.x
                            initY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            isClick = true
                            rootView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
                            fadeHandler.removeCallbacks(fadeRunnable)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - touchX).toInt()
                            val dy = (event.rawY - touchY).toInt()
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isClick = false
                            }
                            params.x = initX + dx
                            params.y = initY + dy
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating floating view layout", e)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val screenHeight = resources.displayMetrics.heightPixels
                            val middle = screenWidth / 2
                            val pillWidth = dockPill?.width ?: 50
                            val pillHeight = dockPill?.height ?: 50
                            val safeTop = 40
                            val safeBottom = (screenHeight - pillHeight - 40).coerceAtLeast(safeTop)
                            params.y = params.y.coerceIn(safeTop, safeBottom)

                            if (isClick) {
                                // Tactile Haptic Feedback
                                dockPill?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                // Toggle Expanded Menu on Click
                                isExpanded = !isExpanded
                                if (isExpanded) {
                                    expandedMenu?.visibility = View.VISIBLE
                                    rootView?.animate()?.alpha(1.0f)?.setDuration(150)?.start()
                                    fadeHandler.removeCallbacks(fadeRunnable)

                                    // If docked near right edge, adjust params.x so toolbar does not overflow screen
                                    if (params.x > middle) {
                                        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                                        val totalWidth = view.measuredWidth
                                        val targetExpandedX = (screenWidth - totalWidth - 16).coerceAtLeast(16)
                                        ValueAnimator.ofInt(params.x, targetExpandedX).apply {
                                            duration = 150
                                            addUpdateListener { anim ->
                                                params.x = anim.animatedValue as Int
                                                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                                            }
                                        }.start()
                                    }
                                } else {
                                    expandedMenu?.visibility = View.GONE
                                    // If collapsed and docked on right, snap back to right wall
                                    if (params.x > middle) {
                                        val targetCollapsedX = screenWidth - pillWidth - 16
                                        ValueAnimator.ofInt(params.x, targetCollapsedX).apply {
                                            duration = 150
                                            addUpdateListener { anim ->
                                                params.x = anim.animatedValue as Int
                                                try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                                            }
                                            addListener(object : AnimatorListenerAdapter() {
                                                override fun onAnimationEnd(animation: Animator) {
                                                    settingsRepository.saveOverlayPosition(params.x, params.y)
                                                }
                                            })
                                        }.start()
                                    }
                                    resetIdleFade()
                                }
                            } else {
                                // 1. Auto-Edge Magnetic Snapping (Nearest Left/Right Border)
                                val targetX = if (params.x + pillWidth / 2 < middle) 16 else screenWidth - pillWidth - 16

                                val animator = ValueAnimator.ofInt(params.x, targetX).apply {
                                    duration = 180
                                    addUpdateListener { animation ->
                                        params.x = animation.animatedValue as Int
                                        try {
                                            windowManager.updateViewLayout(view, params)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error updating snapped view layout", e)
                                        }
                                    }
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            settingsRepository.saveOverlayPosition(params.x, params.y)
                                        }
                                    })
                                }
                                animator.start()
                                resetIdleFade()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            // Play / Pause Button Listener with Tactile Haptic Feedback
            btnPlay?.setOnClickListener {
                btnPlay?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                resetIdleFade()
                onPlayPauseClicked()
            }

            // Strategy subtitle tap cycles preset with Haptic Feedback
            tvStrategySubtitle?.setOnClickListener {
                cycleStrategyPreset()
            }

            // Close Button Listener with Tactile Haptic Feedback
            btnClose?.setOnClickListener {
                btnClose?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                farmingFSM?.stop()
                stopSelf()
            }

            windowManager.addView(view, params)
            resetIdleFade()
            Log.i(TAG, "Circular floating dock attached to WindowManager successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating widget", e)
        }
    }

    private fun onPlayPauseClicked() {
        if (!ScreenCaptureManager.isReady() || mediaProjection == null) {
            Toast.makeText(
                this,
                "Screen capture not yet initialized. Please launch from AAA Macro dashboard.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val fsm = farmingFSM ?: return

        isMacroRunning = !isMacroRunning
        if (isMacroRunning) {
            btnPlay?.setImageResource(android.R.drawable.ic_media_pause)
            btnPlay?.setColorFilter(0xFFF59E0B.toInt())
            tvStatusTitle?.text = "RUNNING"
            Toast.makeText(this, "Macro Started [${selectedPreset.shortName}]", Toast.LENGTH_SHORT).show()

            // 1. Launch FarmingFSM
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
            fsm.pause()
            btnPlay?.setImageResource(android.R.drawable.ic_media_play)
            btnPlay?.setColorFilter(0xFF10B981.toInt())
            tvStatusTitle?.text = "PAUSED"
            Toast.makeText(this, "Macro Paused", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleStrategyPreset() {
        tvStrategySubtitle?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        resetIdleFade()
        val presets = FarmingPreset.values()
        val nextIndex = (presets.indexOf(selectedPreset) + 1) % presets.size
        selectedPreset = presets[nextIndex]

        farmingFSM?.setPreset(selectedPreset)
        settingsRepository.saveSelectedPreset(selectedPreset)
        tvStrategySubtitle?.text = selectedPreset.shortName
        Toast.makeText(this, "Strategy: ${selectedPreset.shortName}", Toast.LENGTH_SHORT).show()
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
            tvStrategySubtitle?.text = selectedPreset.displayName

            // Observe FSM state and dynamically update floating widget HUD
            serviceScope.launch {
                fsm.state.collectLatest { state ->
                    tvStatusTitle?.text = when (state) {
                        MacroState.IDLE -> "IDLE"
                        MacroState.STATE_HOME -> "HOME"
                        MacroState.STATE_SEARCHING -> "SEARCH"
                        MacroState.STATE_EVALUATE -> "EVAL"
                        MacroState.STATE_DEPLOY -> "DEPLOY"
                        MacroState.STATE_RETURN_HOME -> "RETURN"
                        MacroState.STATE_RECOVERY -> "RECOVER"
                    }

                    if (state == MacroState.IDLE) {
                        isMacroRunning = false
                        btnPlay?.setImageResource(android.R.drawable.ic_media_play)
                        btnPlay?.setColorFilter(0xFF10B981.toInt())
                    } else {
                        isMacroRunning = true
                        btnPlay?.setImageResource(android.R.drawable.ic_media_pause)
                        btnPlay?.setColorFilter(0xFFF59E0B.toInt())
                    }
                }
            }

            Log.i(TAG, "Engines and FSM successfully initialized with MediaProjection.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup engines", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FloatingHubService onDestroy()")
        isRunning = false

        fadeHandler.removeCallbacks(fadeRunnable)
        wakeManager.releaseWakeLock()
        serviceScope.cancel()

        if (rootView != null) {
            try {
                windowManager.removeView(rootView)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating view on destroy", e)
            }
            rootView = null
        }

        farmingFSM?.release()
        farmingFSM = null

        visionEngine?.release()
        visionEngine = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
