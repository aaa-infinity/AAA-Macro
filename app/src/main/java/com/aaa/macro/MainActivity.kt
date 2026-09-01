package com.aaa.macro

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aaa.macro.databinding.ActivityMainBinding
import com.aaa.macro.service.FloatingHubService
import com.aaa.macro.service.MacroAccessibilityService
import org.opencv.android.OpenCVLoader

/**
 * Modern Light Setup Dashboard with One-Tap Permission Diagnostics and Direct MediaProjection Launcher.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    private val projectionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "Screen capture permission granted. Starting FloatingHubService...")
            val serviceIntent = Intent(this, FloatingHubService::class.java).apply {
                action = FloatingHubService.ACTION_START_WITH_PROJECTION
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_RESULT_DATA", result.data)
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("RESULT_DATA", result.data)
                putExtra(FloatingHubService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingHubService.EXTRA_PROJECTION_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Automatically launch Clash of Clans over foreground
            val launchIntent = packageManager.getLaunchIntentForPackage("com.supercell.clashofclans")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                moveTaskToBack(true)
            }
        } else {
            Toast.makeText(this, "Screen capture permission is required for auto-farming", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native OpenCV Initialization
        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "Internal OpenCV library loading notification.")
        } else {
            Log.i(TAG, "OpenCV native library loaded successfully.")
        }

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

    /**
     * String-safe check if Accessibility Service is enabled in Android settings.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
        val expectedId = "${context.packageName}/${serviceClass.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedId, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isAccessibilityEnabled(): Boolean {
        return MacroAccessibilityService.isRunning ||
                isAccessibilityServiceEnabled(this, MacroAccessibilityService::class.java)
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

        onLaunchFloatingHubClicked()
    }

    private fun onLaunchFloatingHubClicked() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
