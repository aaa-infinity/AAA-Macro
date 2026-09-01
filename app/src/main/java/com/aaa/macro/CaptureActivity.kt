package com.aaa.macro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aaa.macro.service.FloatingMenuService

/**
 * Transparent Activity dedicated to requesting MediaProjection screen capture consent
 * and forwarding the permission token to FloatingMenuService.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CaptureActivity"

        fun launch(context: Context) {
            val intent = Intent(context, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection granted.")

                val serviceIntent = Intent(this, FloatingHubService::class.java).apply {
                    action = FloatingHubService.ACTION_START_WITH_PROJECTION
                    putExtra("RESULT_CODE", result.resultCode)
                    putExtra("RESULT_DATA", result.data)
                    putExtra(FloatingHubService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(FloatingHubService.EXTRA_PROJECTION_DATA, result.data)
                }

                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection denied.")
                Toast.makeText(this, "Screen capture permission is required for on-device vision.", Toast.LENGTH_LONG).show()
            }
            finish()
        }

        val captureIntent = projectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }
}
