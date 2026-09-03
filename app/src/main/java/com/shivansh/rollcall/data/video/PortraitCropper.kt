package com.shivansh.rollcall.data.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Cuts a head-and-shoulders portrait out of a frame.
 *
 * [cropScale] reaches well outside the detected box, since a box-tight crop of a
 * 90px face gives a soft, unusable tile. The crop sits slightly high because
 * centring a face exactly reads as a mugshot.
 *
 * Shared by the collage and the results roster so both frame a person the same.
 */
object PortraitCropper {

    /** How far above centre the face sits, as a fraction of face height. */
    private const val HEADROOM = 0.12f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @param faceScale maps the box, measured on the working frame, onto [frame].
     * @param aspect height / width of the wanted crop.
     * @return a new bitmap; [frame] is the caller's to recycle.
     */
    fun crop(
        frame: Bitmap,
        boxCenterX: Float,
        boxCenterY: Float,
        boxWidth: Float,
        boxHeight: Float,
        faceScale: Float,
        cropScale: Float,
        aspect: Float,
    ): Bitmap {
        val faceW = boxWidth * faceScale
        val faceH = boxHeight * faceScale
        val centerX = boxCenterX * faceScale
        val centerY = boxCenterY * faceScale - faceH * HEADROOM

        // Never ask for more than the frame holds, or the clamp below slides
        // the crop off centre.
        val cropW = (faceW * cropScale).coerceAtMost(frame.width.toFloat())
        val cropH = (cropW * aspect).coerceAtMost(frame.height.toFloat())

        val x = (centerX - cropW / 2).coerceIn(0f, (frame.width - cropW).coerceAtLeast(0f))
        val y = (centerY - cropH / 2).coerceIn(0f, (frame.height - cropH).coerceAtLeast(0f))

        val left = x.roundToInt().coerceIn(0, frame.width - 1)
        val top = y.roundToInt().coerceIn(0, frame.height - 1)
        val right = (left + cropW.roundToInt()).coerceIn(left + 1, frame.width)
        val bottom = (top + cropH.roundToInt()).coerceIn(top + 1, frame.height)

        // Composited, not sliced: createBitmap hands back the source when the
        // crop covers it, and callers recycle the frame.
        val out = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(left, top, right, bottom),
            Rect(0, 0, right - left, bottom - top),
            paint,
        )
        return out
    }
}
