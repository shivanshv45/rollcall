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

    /** Fraction of the gap to a neighbouring face the crop may use. */
    private const val NEIGHBOUR_GAP = 0.9f

    /** Floor on crop width relative to the face, so it never becomes a mugshot. */
    private const val MIN_CROP_SCALE = 1.3f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @param faceScale maps the box, measured on the working frame, onto [frame].
     * @param aspect height / width of the wanted crop.
     * @param neighbours other face boxes in the same frame, in working-frame
     *   coordinates. The crop is pulled in so it does not swallow them.
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
        neighbours: List<NeighbourBox> = emptyList(),
    ): Bitmap = crop(
        frame,
        rectFor(
            frame.width, frame.height, boxCenterX, boxCenterY, boxWidth, boxHeight,
            faceScale, cropScale, aspect, neighbours,
        ),
    )

    /** Cuts [rect] out of [frame] into a fresh bitmap. */
    fun crop(frame: Bitmap, rect: Rect): Bitmap {
        // Composited, not sliced: createBitmap hands back the source when the
        // crop covers it, and callers recycle the frame.
        val out = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(frame, rect, Rect(0, 0, rect.width(), rect.height()), paint)
        return out
    }

    /** The crop rectangle alone, so a caller can check what falls inside it. */
    fun rectFor(
        frameWidth: Int,
        frameHeight: Int,
        boxCenterX: Float,
        boxCenterY: Float,
        boxWidth: Float,
        boxHeight: Float,
        faceScale: Float,
        cropScale: Float,
        aspect: Float,
        neighbours: List<NeighbourBox> = emptyList(),
    ): Rect {
        val faceW = boxWidth * faceScale
        val faceH = boxHeight * faceScale
        val centerX = boxCenterX * faceScale
        val centerY = boxCenterY * faceScale - faceH * HEADROOM

        // Never ask for more than the frame holds, or the clamp below slides
        // the crop off centre.
        var cropW = (faceW * cropScale).coerceAtMost(frameWidth.toFloat())
        cropW = limitToNeighbours(cropW, faceW, centerX, faceScale, neighbours)
        val cropH = (cropW * aspect).coerceAtMost(frameHeight.toFloat())

        val x = (centerX - cropW / 2).coerceIn(0f, (frameWidth - cropW).coerceAtLeast(0f))
        val y = (centerY - cropH / 2).coerceIn(0f, (frameHeight - cropH).coerceAtLeast(0f))

        val left = x.roundToInt().coerceIn(0, frameWidth - 1)
        val top = y.roundToInt().coerceIn(0, frameHeight - 1)
        val right = (left + cropW.roundToInt()).coerceIn(left + 1, frameWidth)
        val bottom = (top + cropH.roundToInt()).coerceIn(top + 1, frameHeight)
        return Rect(left, top, right, bottom)
    }

    /**
     * Shrinks the crop so it stops short of any other face in the frame.
     *
     * A wide crop is what stops the tile looking like a mugshot, but in a
     * two-person shot the same width reaches straight into the other person and
     * the tile ends up showing both. Halving the gap to the nearest neighbour
     * keeps the subject alone in their own tile.
     */
    private fun limitToNeighbours(
        cropW: Float,
        faceW: Float,
        centerX: Float,
        faceScale: Float,
        neighbours: List<NeighbourBox>,
    ): Float {
        if (neighbours.isEmpty()) return cropW

        var half = cropW / 2f
        for (n in neighbours) {
            val nearEdge = if (n.centerX * faceScale < centerX) {
                centerX - (n.centerX + n.halfWidth) * faceScale
            } else {
                (n.centerX - n.halfWidth) * faceScale - centerX
            }
            if (nearEdge > 0f) half = minOf(half, nearEdge * NEIGHBOUR_GAP)
        }
        // Still show more than the face itself, or the crop becomes the mugshot
        // the wide framing exists to avoid.
        return (half * 2f).coerceAtLeast(faceW * MIN_CROP_SCALE)
    }
}

/** Another face in the same frame, in working-frame coordinates. */
data class NeighbourBox(val centerX: Float, val halfWidth: Float)

