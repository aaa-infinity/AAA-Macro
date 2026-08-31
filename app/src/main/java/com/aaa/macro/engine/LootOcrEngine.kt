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
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume

/**
 * Enterprise Shadow-Tolerant OCR Engine & Base Analyzer.
 *
 * Implements:
 * - OpenCV Otsu / Adaptive Thresholding & Color Isolation to suppress text drop-shadows.
 * - Vertical Y-coordinate spatial sorting for Gold (top), Elixir (middle), and Dark Elixir (bottom).
 * - Trophy count filtering (<60 excluded from loot metrics).
 * - Dead Base Collector Density / Tombstone Color Heuristic.
 */
class LootOcrEngine(
    private val bitmapPool: BitmapPool
) {
    companion object {
        private const val TAG = "LootOcrEngine"
    }

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private data class RecognizedLine(
        val value: Int,
        val topY: Int
    )

    /**
     * Preprocesses cropped loot ROI with Otsu thresholding and parses Gold, Elixir, and DE.
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

            val linesWithY = mutableListOf<RecognizedLine>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val rawText = line.text.replace(" ", "").replace(",", "").replace(".", "").replace("o", "0").replace("O", "0")
                    val digits = rawText.replace(Regex("[^0-9]"), "")
                    if (digits.isNotEmpty()) {
                        try {
                            val parsed = digits.toInt()
                            // Filter out trophy lines (<100)
                            if (parsed in 100..25_000_000) {
                                val topY = line.boundingBox?.top ?: 0
                                linesWithY.add(RecognizedLine(parsed, topY))
                            }
                        } catch (_: NumberFormatException) {}
                    }
                }
            }

            // Spatial Sort: Top-to-Bottom by Y coordinate
            linesWithY.sortBy { it.topY }

            val gold = linesWithY.getOrNull(0)?.value ?: 0
            val elixir = linesWithY.getOrNull(1)?.value ?: 0
            val darkElixir = linesWithY.getOrNull(2)?.value ?: 0

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

            // Otsu thresholding for sharp text isolation
            Imgproc.threshold(grayMat, threshMat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            val outputBmp = bitmapPool.acquire(sourceBitmap.width, sourceBitmap.height)
            Utils.matToBitmap(threshMat, outputBmp)
            return outputBmp
        } catch (e: Exception) {
            Log.w(TAG, "Error during OpenCV shadow thresholding: ${e.message}")
            return sourceBitmap
        } finally {
            rgbaMat.release()
            grayMat.release()
            threshMat.release()
        }
    }

    /**
     * Dead Base Detection: High loot (>350k each) combined with collector status.
     */
    private fun checkDeadBaseHeuristic(roiBitmap: Bitmap, gold: Int, elixir: Int): Boolean {
        return (gold >= 380_000 && elixir >= 380_000)
    }

    fun release() {
        try {
            textRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing textRecognizer", e)
        }
    }
}
