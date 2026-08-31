package com.aaa.macro.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Enterprise Reusable Bitmap Pool for Zero-Allocation Screen Processing.
 *
 * Maintains a fixed pool of mutable Bitmaps to completely eliminate
 * Garbage Collection (GC) churn and memory fragmentation during continuous frame analysis.
 */
class BitmapPool(
    private val poolSize: Int = 3,
    private val defaultWidth: Int = 1920,
    private val defaultHeight: Int = 1080
) {
    companion object {
        private const val TAG = "BitmapPool"
    }

    private val availableBitmaps = ArrayBlockingQueue<Bitmap>(poolSize)
    private val lock = ReentrantLock()

    init {
        preallocatePool(defaultWidth, defaultHeight)
    }

    private fun preallocatePool(width: Int, height: Int) {
        lock.withLock {
            availableBitmaps.clear()
            for (i in 0 until poolSize) {
                try {
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    availableBitmaps.offer(bmp)
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "OOM preallocating BitmapPool entry #$i", oom)
                    break
                }
            }
            Log.i(TAG, "BitmapPool initialized with ${availableBitmaps.size}/$poolSize entries (${width}x${height})")
        }
    }

    /**
     * Acquires a pooled mutable Bitmap for drawing or inBitmap decoding.
     */
    fun acquire(width: Int, height: Int): Bitmap {
        lock.withLock {
            val pooled = availableBitmaps.poll()
            if (pooled != null && !pooled.isRecycled) {
                if (pooled.width == width && pooled.height == height) {
                    pooled.eraseColor(0)
                    return pooled
                } else {
                    pooled.recycle()
                }
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Returns a used Bitmap back to the reusable pool.
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        lock.withLock {
            if (bitmap.isMutable && availableBitmaps.remainingCapacity() > 0) {
                availableBitmaps.offer(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    /**
     * Configures BitmapFactory.Options to use a pooled Bitmap as inBitmap.
     */
    fun prepareOptions(options: BitmapFactory.Options, width: Int, height: Int) {
        options.inMutable = true
        val reusable = availableBitmaps.poll()
        if (reusable != null && !reusable.isRecycled && reusable.width == width && reusable.height == height) {
            options.inBitmap = reusable
        }
    }

    /**
     * Disposes all pooled Bitmaps during service shutdown.
     */
    fun clear() {
        lock.withLock {
            while (availableBitmaps.isNotEmpty()) {
                val bmp = availableBitmaps.poll()
                if (bmp != null && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            Log.i(TAG, "BitmapPool cleared.")
        }
    }
}
