package com.shivansh.rollcall.data.detection

import android.graphics.Bitmap
import android.graphics.Color
import com.shivansh.rollcall.domain.model.BoundingBox
import kotlin.math.abs

/**
 * Pixel measurements the pipeline needs: how sharp a face is, and where the
 * video cuts.
 */
object ImageAnalysis {

    /**
     * Variance of the Laplacian over the face region, as a blur measure.
     *
     * Measured on the face box rather than the whole frame: these clips are shot
     * with shallow depth of field, so whole-frame variance mostly reports how
     * blurred the background is, and a sharp face against soft bokeh would be
     * thrown away.
     */
    fun laplacianVariance(bitmap: Bitmap, box: BoundingBox): Double {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 3 || h < 3) return 0.0

        val gray = IntArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, left, top + y, w, 1)
            for (x in 0 until w) gray[y * w + x] = luminance(row[x])
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val v = (gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4 * gray[i])
                    .toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /**
     * How different two frames are, in [0,1].
     *
     * Downscaled grey thumbnails, mean absolute difference. A hard cut and a
     * whip-pan both spike this; they separate later because whip-pan frames fail
     * the blur gate and cuts do not.
     */
    fun frameDifference(a: IntArray, b: IntArray): Double {
        var total = 0L
        for (i in a.indices) total += abs(a[i] - b[i])
        return total.toDouble() / (a.size * 255.0)
    }

    /** Coarse greyscale signature used for cut detection. */
    fun thumbnail(bitmap: Bitmap, size: Int = 32): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        return IntArray(pixels.size) { luminance(pixels[it]) }
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
}
