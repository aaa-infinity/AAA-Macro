package com.aaa.macro.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
import kotlin.coroutines.resume

/**
 * 2026 Optimized Vision & State Detection Engine.
 *
 * Integrates:
 * - ScreenCaptureManager for low-memory single-buffer frame extraction
 * - Fast StateClassifier for zero-allocation state categorization
 * - Localized ROI-only ML Kit Text Recognition
 * - OpenCV Normalized Cross-Correlation (TM_CCOEFF_NORMED) with immediate Mat disposal
 */
class VisionEngine(
    private val context: Context,
    val resolutionScaler: ResolutionScaler
) {
    companion object {
        private const val TAG = "VisionEngine"
    }

    val captureManager = ScreenCaptureManager(context, resolutionScaler)
    val stateClassifier = StateClassifier()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val isInitialized: Boolean
        get() = captureManager.isInitialized

    fun initializeCapture(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        captureManager.initialize(projection, width, height, densityDpi)
    }

    /**
     * Fast screen state detection using thumbnail color sampling.
     */
    fun classifyCurrentScreen(): DetectedScreenState {
        val bitmap = captureManager.acquireLatestBitmap() ?: return DetectedScreenState.UNKNOWN
        try {
            return stateClassifier.classifyState(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Captures current screen frame as OpenCV BGR Mat with zero-copy ByteBuffer.
     */
    fun captureScreenMat(grayscale: Boolean = false): Mat? {
        val rawMat = captureManager.acquireLatestMat() ?: return null
        val finalMat = Mat()
        try {
            if (grayscale) {
                Imgproc.cvtColor(rawMat, finalMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(rawMat, finalMat, Imgproc.COLOR_RGBA2BGR)
            }
            return finalMat.clone()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting zero-copy raw Mat", e)
            return null
        } finally {
            rawMat.release()
            finalMat.release()
        }
    }

    /**
     * Sub-Region Bounding Box Scanning.
     */
    fun findTemplateInSubRegion(
        templateMat: Mat,
        roi: Rect,
        threshold: Float = 0.85f
    ): Point? {
        val subMat = captureManager.acquireSubRegionMat(org.opencv.core.Rect(roi.left, roi.top, roi.width(), roi.height())) ?: return null
        val bgrSub = Mat()
        try {
            Imgproc.cvtColor(subMat, bgrSub, Imgproc.COLOR_RGBA2BGR)
            val localPoint = findTemplate(bgrSub, templateMat, threshold) ?: return null
            return Point(localPoint.x + roi.left, localPoint.y + roi.top)
        } finally {
            subMat.release()
            bgrSub.release()
        }
    }

    /**
     * Executes Template Matching with TM_CCOEFF_NORMED and strict memory disposal.
     */
    fun findTemplate(
        screenMat: Mat,
        templateMat: Mat,
        threshold: Float = 0.85f
    ): Point? {
        if (screenMat.empty() || templateMat.empty()) {
            return null
        }

        val scaledTemplate = resolutionScaler.scaleTemplate(templateMat)

        if (screenMat.cols() < scaledTemplate.cols() || screenMat.rows() < scaledTemplate.rows()) {
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
     * Localized ROI OCR: Only processes the small cropped Loot Box area without keeping full frames in RAM.
     */
    suspend fun readLootValues(
        cropArea: Rect
    ): Pair<Int, Int> = withContext(Dispatchers.Default) {
        val roiBitmap = captureManager.acquireRoiBitmap(cropArea) ?: return@withContext Pair(0, 0)
        try {
            val image = InputImage.fromBitmap(roiBitmap, 0)

            val visionText = suspendCancellableCoroutine<Text?> { continuation ->
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
            return@withContext Pair(gold, elixir)
        } catch (e: Exception) {
            Log.e(TAG, "Error in readLootValues", e)
            return@withContext Pair(0, 0)
        } finally {
            roiBitmap.recycle()
        }
    }

    fun release() {
        captureManager.release()
    }
}
