package com.aaa.macro

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aaa.macro.databinding.ActivityMainBinding
import com.aaa.macro.service.FloatingMenuService
import com.aaa.macro.service.MacroAccessibilityService

/**
 * Main Setup Dashboard and Permission Orchestrator for AAA Macro.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun setupListeners() {
        // 1. Accessibility Service Permission
        binding.btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Enable 'AAA Macro Automation Engine' in Accessibility list.", Toast.LENGTH_LONG).show()
        }

        // 2. Floating Window Overlay Permission
        binding.btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        // 3. MediaProjection / Screen Capture Permission
        binding.btnCapturePermission.setOnClickListener {
            CapturePermissionActivity.launch(this)
        }

        // 4. Battery Optimization Whitelist
        binding.btnBattery.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Launch Macro Overlay Button
        binding.btnLaunchMacro.setOnClickListener {
            launchMacroIfReady()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Battery optimization already ignored.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPermissionStates() {
        // Overlay Check
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        updateButtonState(binding.btnOverlay, canOverlay)

        // Accessibility Check
        val hasAccessibility = MacroAccessibilityService.isRunning
        updateButtonState(binding.btnAccessibility, hasAccessibility)

        // Battery Optimization Check
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        updateButtonState(binding.btnBattery, isBatteryIgnored)

        // Floating Service Active Check
        updateButtonState(binding.btnCapturePermission, FloatingMenuService.isRunning)
    }

    private fun updateButtonState(button: Button, isGranted: Boolean) {
        if (isGranted) {
            button.text = getString(R.string.btn_granted)
            button.setBackgroundColor(getColor(R.color.accent_green))
            button.setTextColor(getColor(R.color.background_dark))
            button.isEnabled = false
        } else {
            button.text = getString(R.string.btn_grant)
            button.setBackgroundColor(getColor(R.color.primary))
            button.setTextColor(getColor(R.color.text_primary))
            button.isEnabled = true
        }
    }

    private fun launchMacroIfReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Floating Window overlay permission first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!MacroAccessibilityService.isRunning) {
            Toast.makeText(this, "Please enable AAA Macro Accessibility Service first.", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // Launch CapturePermissionActivity to acquire MediaProjection token and start FloatingMenuService
        CapturePermissionActivity.launch(this)
        Toast.makeText(this, "Starting AAA Macro Overlay...", Toast.LENGTH_SHORT).show()
    }
}
