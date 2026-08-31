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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.aaa.macro.R
import com.aaa.macro.databinding.LayoutFloatingMenuBinding
import com.aaa.macro.engine.FarmingFSM
import com.aaa.macro.model.FarmingPreset
import com.aaa.macro.model.MacroState
import com.aaa.macro.model.MacroStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Enterprise Light Floating Mini-Hub Controller.
 *
 * Implements:
 * - Docked / Expanded draggable overlay widget
 * - Army Deployment Preset dropdown selector
 * - Gold, Elixir, and Dark Elixir (+/-) steppers
 * - Auto Wall-Dump toggle
 * - Touch-drag movement across screen boundaries
 */
class FloatingOverlayView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val farmingFSM: FarmingFSM,
    private val onCloseRequested: () -> Unit
) {
    private val binding: LayoutFloatingMenuBinding = LayoutFloatingMenuBinding.inflate(LayoutInflater.from(context))
    private val params: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isExpanded = true

    init {
        setupWindowParams()
        setupListeners()
        setupPresetSpinner()
        observeState()
    }

    private fun setupWindowParams() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params.apply {
            type = windowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 120
        }
    }

    private fun setupPresetSpinner() {
        val presets = FarmingPreset.values()
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            presets.map { it.displayName }
        )
        binding.spinnerArmyPreset.adapter = adapter
        binding.spinnerArmyPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                farmingFSM.setPreset(presets[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.headerDragBar.setOnTouchListener { _, event -> handleDragTouch(event) }
        binding.dockedPillLayout.setOnTouchListener { _, event -> handleDragTouch(event) }

        binding.btnMinimize.setOnClickListener { toggleExpanded(false) }
        binding.btnDockedExpand.setOnClickListener { toggleExpanded(true) }
        binding.dockedPillLayout.setOnClickListener { if (!isDragging) toggleExpanded(true) }

        binding.btnClose.setOnClickListener {
            farmingFSM.stop()
            detach()
            onCloseRequested()
        }

        binding.btnToggleMacro.setOnClickListener {
            if (farmingFSM.state.value == MacroState.IDLE) {
                farmingFSM.start()
            } else {
                farmingFSM.pause()
            }
        }

        binding.btnDockedToggle.setOnClickListener {
            if (farmingFSM.state.value == MacroState.IDLE) {
                farmingFSM.start()
            } else {
                farmingFSM.pause()
            }
        }

        binding.btnEmergencyPause.setOnClickListener { farmingFSM.pause() }

        // Gold +/- 50k
        binding.btnGoldMinus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minGold
            farmingFSM.lootConfig.minGold = (cur - 50_000).coerceAtLeast(0)
            updateLootThresholdText()
        }
        binding.btnGoldPlus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minGold
            farmingFSM.lootConfig.minGold = (cur + 50_000).coerceAtMost(2_000_000)
            updateLootThresholdText()
        }

        // Elixir +/- 50k
        binding.btnElixirMinus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minElixir
            farmingFSM.lootConfig.minElixir = (cur - 50_000).coerceAtLeast(0)
            updateLootThresholdText()
        }
        binding.btnElixirPlus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minElixir
            farmingFSM.lootConfig.minElixir = (cur + 50_000).coerceAtMost(2_000_000)
            updateLootThresholdText()
        }

        // Dark Elixir +/- 500
        binding.btnDeMinus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minDarkElixir
            farmingFSM.lootConfig.minDarkElixir = (cur - 500).coerceAtLeast(0)
            updateLootThresholdText()
        }
        binding.btnDePlus.setOnClickListener {
            val cur = farmingFSM.lootConfig.minDarkElixir
            farmingFSM.lootConfig.minDarkElixir = (cur + 500).coerceAtMost(20_000)
            updateLootThresholdText()
        }

        // Wall Dump Toggle
        binding.cbWallDump.setOnCheckedChangeListener { _, isChecked ->
            farmingFSM.lootConfig.enableWallDump = isChecked
        }

        updateLootThresholdText()
    }

    private fun updateLootThresholdText() {
        binding.tvTargetGoldK.text = "${farmingFSM.lootConfig.minGold / 1000}k"
        binding.tvTargetElixirK.text = "${farmingFSM.lootConfig.minElixir / 1000}k"
        binding.tvTargetDeK.text = "%.1fk".format(farmingFSM.lootConfig.minDarkElixir / 1000.0)
    }

    private fun toggleExpanded(expand: Boolean) {
        isExpanded = expand
        if (expand) {
            binding.dockedPillLayout.visibility = View.GONE
            binding.expandedCardLayout.visibility = View.VISIBLE
        } else {
            binding.expandedCardLayout.visibility = View.GONE
            binding.dockedPillLayout.visibility = View.VISIBLE
        }
        updateWindowLayout()
    }

    private fun handleDragTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                    isDragging = true
                }
                params.x = initialX + dx.toInt()
                params.y = initialY + dy.toInt()
                updateWindowLayout()
                return true
            }
            MotionEvent.ACTION_UP -> {
                return true
            }
        }
        return false
    }

    private fun observeState() {
        scope.launch {
            farmingFSM.state.collectLatest { state ->
                binding.tvStatusState.text = state.name
                when (state) {
                    MacroState.IDLE -> {
                        binding.tvStatusState.setTextColor(context.getColor(R.color.text_secondary))
                        binding.btnToggleMacro.text = "START FARMING"
                        binding.btnToggleMacro.setBackgroundColor(context.getColor(R.color.accent_green))
                        binding.btnDockedToggle.setImageResource(android.R.drawable.ic_media_play)
                    }
                    MacroState.STATE_DEPLOY -> {
                        binding.tvStatusState.setTextColor(context.getColor(R.color.accent_gold))
                        binding.btnToggleMacro.text = "PAUSE"
                        binding.btnToggleMacro.setBackgroundColor(context.getColor(R.color.accent_red))
                        binding.btnDockedToggle.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    else -> {
                        binding.tvStatusState.setTextColor(context.getColor(R.color.accent_green_dark))
                        binding.btnToggleMacro.text = "PAUSE"
                        binding.btnToggleMacro.setBackgroundColor(context.getColor(R.color.accent_red))
                        binding.btnDockedToggle.setImageResource(android.R.drawable.ic_media_pause)
                    }
                }
            }
        }

        scope.launch {
            farmingFSM.latestLoot.collectLatest { loot ->
                binding.tvLiveGold.text = "%,d".format(loot.gold)
                binding.tvLiveElixir.text = "%,d".format(loot.elixir)
                binding.tvLiveDe.text = "%,d".format(loot.darkElixir)
            }
        }

        scope.launch {
            farmingFSM.stats.collectLatest { stats: MacroStats ->
                binding.tvSearchCount.text = "#${stats.totalSearches}"
            }
        }
    }

    fun attach() {
        if (binding.root.windowToken == null) {
            windowManager.addView(binding.root, params)
        }
    }

    fun detach() {
        if (binding.root.windowToken != null) {
            windowManager.removeView(binding.root)
        }
    }

    private fun updateWindowLayout() {
        if (binding.root.windowToken != null) {
            windowManager.updateViewLayout(binding.root, params)
        }
    }
}
