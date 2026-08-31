package com.aaa.macro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.aaa.macro.R
import com.aaa.macro.engine.MacroStateMachine
import com.aaa.macro.model.MacroState
import kotlin.math.abs

/**
 * Encapsulates the Draggable Floating Overlay Window and UI controls.
 */
class FloatingOverlayView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val stateMachine: MacroStateMachine,
    private val onCloseRequested: () -> Unit
) {
    private val rootView: View = LayoutInflater.from(context).inflate(R.layout.layout_floating_menu, null)
    private val windowLayoutParams: WindowManager.LayoutParams

    private val collapsedPill: LinearLayout = rootView.findViewById(R.id.pill_collapsed_view)
    private val expandedPill: LinearLayout = rootView.findViewById(R.id.pill_expanded_view)
    private val dragHeader: LinearLayout = rootView.findViewById(R.id.layout_drag_header)

    private val tvPillStatus: TextView = rootView.findViewById(R.id.tv_pill_status)
    private val btnPillExpand: ImageView = rootView.findViewById(R.id.btn_pill_expand)

    private val btnMinimize: ImageView = rootView.findViewById(R.id.btn_minimize)
    private val btnClose: ImageView = rootView.findViewById(R.id.btn_close)
    private val tvStatusState: TextView = rootView.findViewById(R.id.tv_status_state)
    private val tvSearchCount: TextView = rootView.findViewById(R.id.tv_search_count)
    private val tvLiveGold: TextView = rootView.findViewById(R.id.tv_live_gold)
    private val tvLiveElixir: TextView = rootView.findViewById(R.id.tv_live_elixir)

    private val etTargetGold: EditText = rootView.findViewById(R.id.et_target_gold)
    private val etTargetElixir: EditText = rootView.findViewById(R.id.et_target_elixir)
    private val btnToggle: Button = rootView.findViewById(R.id.btn_toggle_start_pause)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    init {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 150
        }

        setupInteractions()
        populateDefaults()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        // Drag listener for header
        val touchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowLayoutParams.x
                    initialY = windowLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        isDragging = true
                        windowLayoutParams.x = initialX + dx
                        windowLayoutParams.y = initialY + dy
                        windowManager.updateViewLayout(rootView, windowLayoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging
                }
                else -> false
            }
        }

        dragHeader.setOnTouchListener(touchListener)
        collapsedPill.setOnTouchListener(touchListener)

        // Minimize / Expand toggles
        btnMinimize.setOnClickListener {
            showCollapsed()
        }

        btnPillExpand.setOnClickListener {
            showExpanded()
        }

        collapsedPill.setOnClickListener {
            if (!isDragging) {
                showExpanded()
            }
        }

        // Close action
        btnClose.setOnClickListener {
            onCloseRequested()
        }

        // Target Gold & Elixir inputs focus management
        etTargetGold.setOnFocusChangeListener { _, hasFocus ->
            updateFocusable(hasFocus)
        }
        etTargetElixir.setOnFocusChangeListener { _, hasFocus ->
            updateFocusable(hasFocus)
        }

        etTargetGold.doAfterTextChanged { text ->
            val value = text?.toString()?.toIntOrNull()
            if (value != null) {
                stateMachine.lootConfig.minGold = value
            }
        }

        etTargetElixir.doAfterTextChanged { text ->
            val value = text?.toString()?.toIntOrNull()
            if (value != null) {
                stateMachine.lootConfig.minElixir = value
            }
        }

        // Start / Pause Toggle
        btnToggle.setOnClickListener {
            if (stateMachine.state.value == MacroState.IDLE) {
                stateMachine.start()
            } else {
                stateMachine.pause()
            }
        }
    }

    private fun populateDefaults() {
        etTargetGold.setText(stateMachine.lootConfig.minGold.toString())
        etTargetElixir.setText(stateMachine.lootConfig.minElixir.toString())
    }

    private fun updateFocusable(focusable: Boolean) {
        if (focusable) {
            windowLayoutParams.flags = windowLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            windowLayoutParams.flags = windowLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(rootView, windowLayoutParams)
    }

    fun showExpanded() {
        collapsedPill.visibility = View.GONE
        expandedPill.visibility = View.VISIBLE
    }

    fun showCollapsed() {
        expandedPill.visibility = View.GONE
        collapsedPill.visibility = View.VISIBLE
        updateFocusable(false)
    }

    fun updateState(state: MacroState) {
        tvStatusState.text = state.displayName
        tvPillStatus.text = state.displayName

        if (state == MacroState.IDLE) {
            btnToggle.text = "▶ START MACRO"
            btnToggle.setBackgroundResource(R.drawable.bg_button_start)
            tvStatusState.setTextColor(context.getColor(R.color.accent_green))
            tvPillStatus.setTextColor(context.getColor(R.color.accent_green))
        } else {
            btnToggle.text = "⏸ PAUSE MACRO"
            btnToggle.setBackgroundResource(R.drawable.bg_button_stop)
            tvStatusState.setTextColor(context.getColor(R.color.accent_gold))
            tvPillStatus.setTextColor(context.getColor(R.color.accent_gold))
        }
    }

    fun updateLoot(gold: Int, elixir: Int) {
        tvLiveGold.text = "Gold: %,d".format(gold)
        tvLiveElixir.text = "Elixir: %,d".format(elixir)
    }

    fun updateSearches(count: Int) {
        tvSearchCount.text = "#$count"
    }

    fun attach() {
        try {
            if (rootView.windowToken == null) {
                windowManager.addView(rootView, windowLayoutParams)
            }
        } catch (_: Exception) {}
    }

    fun detach() {
        try {
            if (rootView.windowToken != null) {
                windowManager.removeView(rootView)
            }
        } catch (_: Exception) {}
    }
}
