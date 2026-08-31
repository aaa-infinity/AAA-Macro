package com.aaa.macro.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume

/**
 * Production-Grade Vision & State Detection Engine.
 *
 * Integrates:
 * - Real-time thread-safe MediaProjection screen capture with ImageReader
 * - OpenCV Normalized Cross-Correlation template matching (TM_CCOEFF_NORMED)
 * - Explicit native Mat & Bitmap lifecycle management to prevent memory leaks / OOM
 * - Google ML Kit On-Device Text Recognition for parsing Gold, Elixir, and Dark Elixir numbers
 */
class VisionEngine(
    private val context: Context,
    val resolutionScaler: ResolutionScaler
) {
    companion object {
        private const val TAG = "VisionEngine"
        private const val VIRTUAL_DISPLAY_NAME = "AAA_Macro_Display"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val captureLock = ReentrantLock()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * Initializes MediaProjection and attaches a VirtualDisplay to ImageReader.
     */
    fun initializeCapture(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        captureLock.withLock {
            release()

            this.mediaProjection = projection
            this.resolutionScaler.updateDimensions(width, height)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            this.imageReader = reader

            this.virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            this.isInitialized = true
            Log.i(TAG, "VisionEngine capture initialized at ${width}x${height} ($densityDpi dpi)")
        }
    }

    /**
     * Captures the latest screen frame and converts it into an Android Bitmap.
     * Accurately handles rowStride padding to avoid skewed/slanted frame buffers.
     */
    fun captureScreenBitmap(): Bitmap? {
        captureLock.withLock {
            val reader = imageReader ?: return null
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) return null

                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // If row padding exists, crop out the excess buffer padding cleanly
                return if (rowPadding == 0) {
                    bitmap
                } else {
                    val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    bitmap.recycle()
                    cleanBitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen bitmap", e)
                return null
            } finally {
                image?.close()
            }
        }
    }

    /**
     * Captures the latest screen frame directly as an OpenCV BGR Mat.
     */
    fun captureScreenMat(grayscale: Boolean = false): Mat? {
        val bitmap = captureScreenBitmap() ?: return null
        val rgbaMat = Mat()
        val finalMat = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgbaMat)
            if (grayscale) {
                Imgproc.cvtColor(rgbaMat, finalMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(rgbaMat, finalMat, Imgproc.COLOR_RGBA2BGR)
            }
            return finalMat.clone()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting captured bitmap to Mat", e)
            return null
        } finally {
            bitmap.recycle()
            rgbaMat.release()
            finalMat.release()
        }
    }

    /**
     * Template Matching using OpenCV Normalized Correlation Coefficient (TM_CCOEFF_NORMED).
     * Automatically scales reference template to target screen density and releases all intermediate buffers.
     *
     * @param screenMat Full or cropped screen Mat.
     * @param templateMat Reference template Mat.
     * @param threshold Minimum match confidence score (0.0 to 1.0, standard 0.85).
     * @return Point representing the center of the detected template match in screen coordinates, or null.
     */
    fun findTemplate(
        screenMat: Mat,
        templateMat: Mat,
        threshold: Float = 0.85f
    ): Point? {
        if (screenMat.empty() || templateMat.empty()) {
            return null
        }

        // Dynamically scale template if resolutions differ
        val scaledTemplate = resolutionScaler.scaleTemplate(templateMat)

        if (screenMat.cols() < scaledTemplate.cols() || screenMat.rows() < scaledTemplate.rows()) {
            Log.w(TAG, "Screen Mat (${screenMat.cols()}x${screenMat.rows()}) smaller than Template (${scaledTemplate.cols()}x${scaledTemplate.rows()})")
            scaledTemplate.release()
            return null
        }

        val resultCols = screenMat.cols() - scaledTemplate.cols() + 1
        val resultRows = screenMat.rows() - scaledTemplate.rows() + 1
        val resultMat = Mat(resultRows, resultCols, CvType.CV_32FC1)

        try {
            Imgproc.matchTemplate(screenMat, scaledTemplate, resultMat, Imgproc.TM_CCOEFF_NORMED)
            val minMaxResult = Core.minMaxLoc(resultMat)

            val maxVal = minMaxResult.maxVal.toFloat()
            if (maxVal >= threshold) {
                val matchLoc = minMaxResult.maxLoc
                val centerX = matchLoc.x + scaledTemplate.cols() / 2.0
                val centerY = matchLoc.y + scaledTemplate.rows() / 2.0
                Log.d(TAG, "Template matched with confidence $maxVal at ($centerX, $centerY)")
                return Point(centerX, centerY)
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OpenCV matchTemplate", e)
            return null
        } finally {
            resultMat.release()
            scaledTemplate.release()
        }
    }

    /**
     * Reads Gold and Elixir quantities from the designated loot HUD region using ML Kit OCR.
     *
     * @param screenBitmap Full frame bitmap.
     * @param cropArea Bounding box of the loot HUD region (will be scaled if not already).
     * @return Pair of parsed integers (Gold, Elixir).
     */
    suspend fun readLootValues(
        screenBitmap: Bitmap,
        cropArea: Rect
    ): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val safeLeft = cropArea.left.coerceIn(0, screenBitmap.width - 1)
        val safeTop = cropArea.top.coerceIn(0, screenBitmap.height - 1)
        val safeWidth = cropArea.width().coerceIn(1, screenBitmap.width - safeLeft)
        val safeHeight = cropArea.height().coerceIn(1, screenBitmap.height - safeTop)

        var cropped: Bitmap? = null
        try {
            cropped = Bitmap.createBitmap(screenBitmap, safeLeft, safeTop, safeWidth, safeHeight)
            val image = InputImage.fromBitmap(cropped, 0)

            val visionText = suspendCancellableCoroutine { continuation ->
                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "ML Kit OCR failed: ${error.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
            } ?: return@withContext Pair(0, 0)

            val numbers = mutableListOf<Int>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val raw = line.text
                    // Extract numbers, ignoring commas, periods, spaces, letters
                    val cleaned = raw.replace(Regex("[^0-9]"), "")
                    if (cleaned.isNotEmpty()) {
                        try {
                            val parsedVal = cleaned.toInt()
                            if (parsedVal in 100..20000000) {
                                numbers.add(parsedVal)
                            }
                        } catch (_: NumberFormatException) {}
                    }
                }
            }

            val gold = numbers.getOrNull(0) ?: 0
            val elixir = numbers.getOrNull(1) ?: 0
            Log.d(TAG, "OCR parsed loot - Gold: $gold, Elixir: $elixir from text: '${visionText.text.replace("\n", " ")}'")
            return@withContext Pair(gold, elixir)
        } catch (e: Exception) {
            Log.e(TAG, "Error in readLootValues", e)
            return@withContext Pair(0, 0)
        } finally {
            cropped?.recycle()
        }
    }

    /**
     * Releases all active MediaProjection, VirtualDisplay, ImageReader, and ML Kit instances.
     */
    fun release() {
        captureLock.withLock {
            isInitialized = false
            try {
                virtualDisplay?.release()
                virtualDisplay = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing virtualDisplay", e)
            }

            try {
                imageReader?.close()
                imageReader = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing imageReader", e)
            }

            try {
                mediaProjection?.stop()
                mediaProjection = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping mediaProjection", e)
            }

            Log.i(TAG, "VisionEngine capture resources successfully released.")
        }
    }
}
