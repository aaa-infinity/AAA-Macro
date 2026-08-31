package com.aaa.macro.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance asset loader for OpenCV Mat templates.
 * Caches loaded templates and converts them directly into optimized BGR/Grayscale OpenCV matrices.
 */
object AssetHelper {
    private const val TAG = "AssetHelper"
    private val matCache = ConcurrentHashMap<String, Mat>()

    /**
     * Loads an image asset from assets/ folder and converts it to an OpenCV Mat.
     *
     * @param context Application context.
     * @param assetPath Path relative to assets/ directory (e.g., "templates/attack_button.png").
     * @param grayscale If true, converts to single-channel CV_8UC1. Otherwise BGR (CV_8UC3).
     * @param useCache Whether to cache the loaded Mat.
     * @return Loaded OpenCV Mat, or null if loading failed.
     */
    fun loadMatFromAsset(
        context: Context,
        assetPath: String,
        grayscale: Boolean = false,
        useCache: Boolean = true
    ): Mat? {
        val cacheKey = "$assetPath|gray=$grayscale"
        if (useCache && matCache.containsKey(cacheKey)) {
            val cached = matCache[cacheKey]
            if (cached != null && !cached.empty()) {
                return cached.clone()
            }
        }

        var inputStream: InputStream? = null
        var bitmap: Bitmap? = null
        val rgbaMat = Mat()
        val finalMat = Mat()

        try {
            inputStream = context.assets.open(assetPath)
            bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from asset: $assetPath")
                return null
            }

            Utils.bitmapToMat(bitmap, rgbaMat)

            if (grayscale) {
                Imgproc.cvtColor(rgbaMat, finalMat, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(rgbaMat, finalMat, Imgproc.COLOR_RGBA2BGR)
            }

            if (useCache) {
                matCache[cacheKey] = finalMat.clone()
            }

            return finalMat.clone()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Mat from asset: $assetPath", e)
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}

            bitmap?.recycle()
            rgbaMat.release()
            finalMat.release()
        }
    }

    /**
     * Clears all cached Mat objects and explicitly releases their native memory pointers.
     */
    fun clearCache() {
        for ((_, mat) in matCache) {
            if (!mat.empty()) {
                mat.release()
            }
        }
        matCache.clear()
        Log.d(TAG, "Asset Mat cache cleared.")
    }
}
