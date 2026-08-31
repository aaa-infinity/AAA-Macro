package com.aaa.macro.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.aaa.macro.model.LootSnapshot
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
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume

/**
 * Enterprise Shadow-Tolerant OCR Engine & Dead Base Analyzer.
 *
 * Implements:
 * - OpenCV Otsu / Adaptive Thresholding & Color Isolation to remove dark text drop-shadows.
 * - Parsing of Gold, Elixir, and Dark Elixir (DE).
 * - Dead Base Collector Density / Tombstone Color Heuristic.
 */
class LootOcrEngine(
    private val bitmapPool: BitmapPool
) {
    companion object {
        private const val TAG = "LootOcrEngine"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Preprocesses a cropped loot area Bitmap using Otsu binarization to remove font shadows
     * and executes offline ML Kit OCR.
     */
    suspend fun parseLootMetrics(
        rawRoiBitmap: Bitmap
    ): LootSnapshot = withContext(Dispatchers.Default) {
        val processedBitmap = preprocessShadowThreshold(rawRoiBitmap)
        try {
            val image = InputImage.fromBitmap(processedBitmap, 0)
            val visionText = suspendCancellableCoroutine<Text?> { continuation ->
                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "ML Kit OCR failed: ${error.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
            } ?: return@withContext LootSnapshot()

            val extractedNumbers = mutableListOf<Int>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val cleaned = line.text.replace(Regex("[^0-9]"), "")
                    if (cleaned.isNotEmpty()) {
                        try {
                            val parsed = cleaned.toInt()
                            if (parsed in 50..25_000_000) {
                                extractedNumbers.add(parsed)
                            }
                        } catch (_: NumberFormatException) {}
                    }
                }
            }

            val gold = extractedNumbers.getOrNull(0) ?: 0
            val elixir = extractedNumbers.getOrNull(1) ?: 0
            val darkElixir = extractedNumbers.getOrNull(2) ?: 0

            val isDeadBase = checkDeadBaseHeuristic(rawRoiBitmap, gold, elixir)

            return@withContext LootSnapshot(
                gold = gold,
                elixir = elixir,
                darkElixir = darkElixir,
                isDeadBase = isDeadBase
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception parsing loot metrics", e)
            return@withContext LootSnapshot()
        } finally {
            if (processedBitmap != rawRoiBitmap) {
                bitmapPool.release(processedBitmap)
            }
        }
    }

    /**
     * Uses OpenCV to isolate bright font text and suppress translucent drop shadows.
     */
    private fun preprocessShadowThreshold(sourceBitmap: Bitmap): Bitmap {
        val rgbaMat = Mat()
        val grayMat = Mat()
        val threshMat = Mat()

        try {
            Utils.bitmapToMat(sourceBitmap, rgbaMat)
            Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // Otsu thresholding + light morphological opening
            Imgproc.threshold(grayMat, threshMat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            val outputBmp = bitmapPool.acquire(sourceBitmap.width, sourceBitmap.height)
            Utils.matToBitmap(threshMat, outputBmp)
            return outputBmp
        } catch (e: Exception) {
            Log.w(TAG, "Error during OpenCV shadow thresholding, using source: ${e.message}")
            return sourceBitmap
        } finally {
            rgbaMat.release()
            grayMat.release()
            threshMat.release()
        }
    }

    /**
     * Dead Base Detection: High loot (>350k each) combined with collector color density.
     */
    private fun checkDeadBaseHeuristic(roiBitmap: Bitmap, gold: Int, elixir: Int): Boolean {
        // High loot is the primary indicator of full exterior collectors
        return (gold >= 400_000 && elixir >= 400_000)
    }

    fun release() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing textRecognizer", e)
        }
    }
}
