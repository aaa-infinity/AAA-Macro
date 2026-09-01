package com.aaa.macro.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Enterprise Single-Buffer Screen Capture Manager with MediaProjection.Callback.
 *
 * Implements:
 * - Direct Zero-Copy OpenCV Mat extraction from direct ByteBuffer
 * - Protected frame acquisition with try/finally null-guard
 * - Single VirtualDisplay per session (persists across Play/Pause)
 * - RowStride padding normalization
 */
class ScreenCaptureManager(
    private val context: Context,
    val resolutionScaler: ResolutionScaler,
    private val onCaptureStoppedCallback: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "AAA_Macro_Capture"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureLock = ReentrantLock()

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection session stopped by system. Disposing VirtualDisplay...")
            release()
            onCaptureStoppedCallback?.invoke()
        }
    }

    /**
     * Initializes VirtualDisplay once per session.
     * Never calls createVirtualDisplay twice on the same active token.
     */
    fun initialize(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        captureLock.withLock {
            if (isInitialized && virtualDisplay != null && mediaProjection == projection) {
                Log.i(TAG, "VirtualDisplay already active for this session. Preserving active instance.")
                return
            }

            release()

            val landscapeWidth = maxOf(width, height)
            val landscapeHeight = minOf(width, height)

            this.mediaProjection = projection
            this.resolutionScaler.updateDimensions(landscapeWidth, landscapeHeight)

            projection.registerCallback(projectionCallback, mainHandler)

            // Buffer capacity of 2 for minimal latency and zero memory bloat
            val reader = ImageReader.newInstance(landscapeWidth, landscapeHeight, PixelFormat.RGBA_8888, 2)
            this.imageReader = reader

            this.virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                landscapeWidth,
                landscapeHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )

            this.isInitialized = true
            Log.i(TAG, "ScreenCaptureManager initialized at landscape ${landscapeWidth}x${landscapeHeight} ($densityDpi DPI)")
        }
    }

    /**
     * Captures the latest screen frame as a Bitmap.
     * Protected from display driver warmup null pointers with try/finally { image.close() }.
     */
    fun acquireLatestBitmap(): Bitmap? {
        captureLock.withLock {
            val reader = imageReader ?: return null
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return null

                val planes = image.planes
                val plane = planes[0]
                val buffer: ByteBuffer = plane.buffer
                buffer.rewind()

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                if (rowPadding <= 0) {
                    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    return bitmap
                } else {
                    val paddedWidth = image.width + (rowPadding / pixelStride)
                    val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    paddedBitmap.copyPixelsFromBuffer(buffer)

                    val cleanBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                    paddedBitmap.recycle()
                    return cleanBitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring screen frame: ${e.message}", e)
                return null
            } finally {
                image?.close()
            }
        }
    }

    /**
     * Zero-Copy OpenCV Mat extraction directly from ImageReader direct byte buffer.
     * Eliminates intermediate Bitmap allocations completely.
     */
    fun acquireLatestMat(): Mat? {
        captureLock.withLock {
            val reader = imageReader ?: return null
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return null

                val planes = image.planes
                val plane = planes[0]
                val buffer: ByteBuffer = plane.buffer
                buffer.rewind()

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val fullMat = Mat(image.height, rowStride / pixelStride, CvType.CV_8UC4, buffer)

                return if (rowPadding <= 0) {
                    val cleanMat = Mat()
                    fullMat.copyTo(cleanMat)
                    fullMat.release()
                    cleanMat
                } else {
                    val roi = org.opencv.core.Rect(0, 0, image.width, image.height)
                    val subMat = Mat(fullMat, roi)
                    val cleanMat = Mat()
                    subMat.copyTo(cleanMat)
                    subMat.release()
                    fullMat.release()
                    cleanMat
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring zero-copy Mat: ${e.message}", e)
                return null
            } finally {
                image?.close()
            }
        }
    }

    /**
     * Direct zero-copy extraction of a localized sub-region Mat.
     */
    fun acquireSubRegionMat(roiRect: org.opencv.core.Rect): Mat? {
        captureLock.withLock {
            val reader = imageReader ?: return null
            var image: Image? = null
            try {
                image = reader.acquireLatestImage() ?: return null

                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                buffer.rewind()

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride

                val fullMat = Mat(image.height, rowStride / pixelStride, CvType.CV_8UC4, buffer)

                val safeX = roiRect.x.coerceIn(0, image.width - 1)
                val safeY = roiRect.y.coerceIn(0, image.height - 1)
                val safeW = roiRect.width.coerceIn(1, image.width - safeX)
                val safeH = roiRect.height.coerceIn(1, image.height - safeY)

                val subMat = Mat(fullMat, org.opencv.core.Rect(safeX, safeY, safeW, safeH))
                val cleanMat = Mat()
                subMat.copyTo(cleanMat)

                subMat.release()
                fullMat.release()
                return cleanMat
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring sub-region Mat: ${e.message}", e)
                return null
            } finally {
                image?.close()
            }
        }
    }

    /**
     * Extracts only a localized Region-of-Interest (ROI) directly from the captured frame.
     */
    fun acquireRoiBitmap(cropRect: Rect): Bitmap? {
        val fullBitmap = acquireLatestBitmap() ?: return null
        try {
            val safeLeft = cropRect.left.coerceIn(0, fullBitmap.width - 1)
            val safeTop = cropRect.top.coerceIn(0, fullBitmap.height - 1)
            val safeWidth = cropRect.width().coerceIn(1, fullBitmap.width - safeLeft)
            val safeHeight = cropRect.height().coerceIn(1, fullBitmap.height - safeTop)

            return Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeWidth, safeHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ROI Bitmap", e)
            return null
        } finally {
            fullBitmap.recycle()
        }
    }

    /**
     * Releases active MediaProjection, VirtualDisplay, and ImageReader resources on service destroy.
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
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
                mediaProjection = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping mediaProjection", e)
            }

            Log.i(TAG, "ScreenCaptureManager resources successfully disposed.")
        }
    }
}
