package com.shivansh.rollcall.data.embedding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.shivansh.rollcall.domain.model.BoundingBox
import kotlin.math.atan2
import kotlin.math.hypot

/** Levels the eye line and scales the face to the geometry the model expects. */
object FaceAligner {

    const val SIZE = 112

    // Pupil span as a fraction of crop width. The prototype's 0.42 was for
    // MediaPipe's eye corners; ML Kit gives pupil centres, which sit closer.
    const val EYE_SPAN = 0.31f

    /** Vertical position of the eye line, leaving room for chin and hair. */
    const val EYE_HEIGHT = 0.42f

    private const val MIN_EYE_DISTANCE_PX = 2f
    private const val FALLBACK_MARGIN = 0.25f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Always returns a fresh SIZE x SIZE crop; [frame] stays the caller's to recycle.
     *
     * Faces with no usable eye landmarks fall back to the box. A rough crop still
     * embeds well enough to cluster, and dropping one costs a whole appearance.
     */
    fun align(
        frame: Bitmap,
        box: BoundingBox,
        leftEye: Pair<Float, Float>?,
        rightEye: Pair<Float, Float>?,
    ): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val matrix = if (leftEye != null && rightEye != null) matrixFor(leftEye, rightEye) else null
        if (matrix != null) {
            canvas.drawBitmap(frame, matrix, paint)
        } else {
            // Straight from the source rect. An intermediate createBitmap crop
            // can alias the frame, which the caller still owns.
            val margin = (box.width * FALLBACK_MARGIN).toInt()
            val srcLeft = (box.left - margin).coerceIn(0, frame.width - 1)
            val srcTop = (box.top - margin).coerceIn(0, frame.height - 1)
            val srcRight = (box.right + margin).coerceIn(srcLeft + 1, frame.width)
            val srcBottom = (box.bottom + margin).coerceIn(srcTop + 1, frame.height)
            canvas.drawBitmap(
                frame,
                Rect(srcLeft, srcTop, srcRight, srcBottom),
                Rect(0, 0, SIZE, SIZE),
                paint,
            )
        }
        return out
    }

    /**
     * Transform that levels the eyes and pins them to the template.
     *
     * Eyes are sorted by x rather than trusted by name: ML Kit's LEFT_EYE is the
     * subject's left, so it sits further right in the image. Taking it as
     * image-left flips every face 180 degrees.
     */
    fun matrixFor(eyeA: Pair<Float, Float>, eyeB: Pair<Float, Float>): Matrix? {
        val (near, far) = if (eyeA.first <= eyeB.first) eyeA to eyeB else eyeB to eyeA

        val dx = far.first - near.first
        val dy = far.second - near.second
        val eyeDistance = hypot(dx, dy)
        if (eyeDistance < MIN_EYE_DISTANCE_PX) return null

        val centerX = (near.first + far.first) / 2f
        val centerY = (near.second + far.second) / 2f
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val scale = SIZE * EYE_SPAN / eyeDistance

        return Matrix().apply {
            postTranslate(-centerX, -centerY)
            postRotate(-angle)
            postScale(scale, scale)
            postTranslate(SIZE / 2f, SIZE * EYE_HEIGHT)
        }
    }
}
