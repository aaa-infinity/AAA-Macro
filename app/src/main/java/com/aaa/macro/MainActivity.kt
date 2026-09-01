package com.aaa.macro

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aaa.macro.databinding.ActivityMainBinding
import com.aaa.macro.service.MacroAccessibilityService

/**
 * Modern Light Setup Dashboard with One-Tap Permission Diagnostics.
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
        binding.btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Enable 'AAA Macro Automation Engine' in the Accessibility list.", Toast.LENGTH_LONG).show()
        }

        // 2. Floating Window Overlay Permission
        binding.btnGrantOverlay.setOnClickListener {
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

        // 3. Battery Optimization Whitelist
        binding.btnGrantBattery.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // 4. Launch Floating Hub
        binding.btnStartService.setOnClickListener {
            launchFarmingHub()
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

    private fun isAccessibilityEnabled(): Boolean {
        if (MacroAccessibilityService.isRunning) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun refreshPermissionStates() {
        // Overlay Check
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        updatePermissionCard(binding.btnGrantOverlay, binding.iconStatusOverlay, canOverlay)

        // Accessibility Check
        val hasAccessibility = isAccessibilityEnabled()
        updatePermissionCard(binding.btnGrantAccessibility, binding.iconStatusAccessibility, hasAccessibility)

        // Battery Optimization Check
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        updatePermissionCard(binding.btnGrantBattery, binding.iconStatusBattery, isBatteryIgnored)
    }

    private fun updatePermissionCard(button: Button, icon: ImageView, isGranted: Boolean) {
        if (isGranted) {
            button.text = "Enabled"
            button.isEnabled = false
            button.setTextColor(getColor(R.color.text_secondary))
            icon.setImageResource(android.R.drawable.checkbox_on_background)
            icon.setColorFilter(getColor(R.color.accent_green))
        } else {
            button.text = "Grant"
            button.isEnabled = true
            button.setTextColor(getColor(R.color.accent_green_dark))
            icon.setImageResource(android.R.drawable.presence_offline)
            icon.setColorFilter(getColor(R.color.accent_red))
        }
    }

    private fun launchFarmingHub() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Floating Window overlay permission first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service first.", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        CapturePermissionActivity.launch(this)
        Toast.makeText(this, "Launching AAA Farming Mini-Hub...", Toast.LENGTH_SHORT).show()
    }
}
