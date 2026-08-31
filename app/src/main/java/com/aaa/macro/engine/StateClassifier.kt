package com.aaa.macro.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

/**
 * 2026 AI & Fast-Pixel State Classifier.
 *
 * Employs ultra-low-memory downscaled 160x90 thumbnail sampling and
 * fast color variance heuristics to classify game screen states in <1ms without high RAM usage.
 */
enum class DetectedScreenState {
    UNKNOWN,
    HOME_VILLAGE,
    CLOUDS_SEARCHING,
    MATCH_FOUND,
    IN_BATTLE,
    END_BATTLE_SUMMARY,
    DISCONNECT_POPUP
}

class StateClassifier {

    companion object {
        private const val TAG = "StateClassifier"
        const val THUMBNAIL_WIDTH = 160
        const val THUMBNAIL_HEIGHT = 90
    }

    /**
     * Classifies the current screen state from a full or downsampled Bitmap.
     * Uses Fast-Pixel Color Sampling on a 160x90 thumbnail to maintain <60 MB total RAM footprint.
     *
     * @param fullBitmap Full frame screen capture Bitmap.
     * @return Classified DetectedScreenState.
     */
    fun classifyState(fullBitmap: Bitmap): DetectedScreenState {
        var thumbnail: Bitmap? = null
        try {
            thumbnail = Bitmap.createScaledBitmap(
                fullBitmap,
                THUMBNAIL_WIDTH,
                THUMBNAIL_HEIGHT,
                false
            )

            // Sample key Region-of-Interest points on the 160x90 grid
            // 1. Cloud Detection (Uniform high brightness / near-white or light gray across center)
            val centerColor1 = thumbnail.getPixel(80, 45)
            val centerColor2 = thumbnail.getPixel(60, 45)
            val centerColor3 = thumbnail.getPixel(100, 45)

            if (isCloudPixel(centerColor1) && isCloudPixel(centerColor2) && isCloudPixel(centerColor3)) {
                return DetectedScreenState.CLOUDS_SEARCHING
            }

            // 2. Next Button Zone (Bottom Right: x ~ 145, y ~ 75 on 160x90)
            val nextBtnPixel = thumbnail.getPixel(145, 75)
            val nextBtnRed = Color.red(nextBtnPixel)
            val nextBtnGreen = Color.green(nextBtnPixel)
            val nextBtnBlue = Color.blue(nextBtnPixel)
            val isNextButtonOrangeGold = (nextBtnRed > 160 && nextBtnGreen in 100..180 && nextBtnBlue < 80)

            // 3. Attack Button Zone (Bottom Left: x ~ 10, y ~ 78 on 160x90)
            val attackBtnPixel = thumbnail.getPixel(10, 78)
            val attackBtnRed = Color.red(attackBtnPixel)
            val attackBtnGreen = Color.green(attackBtnPixel)
            val attackBtnBlue = Color.blue(attackBtnPixel)
            val isAttackButtonRedOrange = (attackBtnRed > 150 && attackBtnGreen < 90 && attackBtnBlue < 90)

            // 4. Return Home / End Screen (Center Bottom: x ~ 80, y ~ 76)
            val endSummaryPixel = thumbnail.getPixel(80, 76)
            val isEndSummaryBtn = (Color.red(endSummaryPixel) in 50..180 && Color.green(endSummaryPixel) in 120..220 && Color.blue(endSummaryPixel) in 180..255)

            // 5. Check Disconnect Dialog (Center dark modal box + orange/green button)
            val modalCenter = thumbnail.getPixel(80, 50)
            val isModalDark = (Color.red(modalCenter) < 60 && Color.green(modalCenter) < 60 && Color.blue(modalCenter) < 60)
            if (isModalDark && isNextButtonOrangeGold) {
                return DetectedScreenState.DISCONNECT_POPUP
            }

            return when {
                isNextButtonOrangeGold -> DetectedScreenState.MATCH_FOUND
                isAttackButtonRedOrange -> DetectedScreenState.HOME_VILLAGE
                isEndSummaryBtn -> DetectedScreenState.END_BATTLE_SUMMARY
                else -> DetectedScreenState.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during state classification", e)
            return DetectedScreenState.UNKNOWN
        } finally {
            thumbnail?.recycle()
        }
    }

    private fun isCloudPixel(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // High brightness, low saturation (clouds)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return (r > 190 && g > 190 && b > 190 && (max - min) < 25)
    }
}
