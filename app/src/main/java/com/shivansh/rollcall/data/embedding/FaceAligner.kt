package com.shivansh.rollcall.data.embedding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.shivansh.rollcall.domain.model.BoundingBox
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Puts a face into the fixed geometry the embedding model was trained on.
 *
 * Rotates so the eye line is level and scales so the pupils land on the same
 * two points in every crop. Removing in-plane rotation and normalising scale is
 * the cheapest accuracy win available, and it is why the detector is asked for
 * landmarks at all.
 */
object FaceAligner {

    const val SIZE = 112

    /**
     * Pupil separation as a fraction of the crop width.
     *
     * The prototype used 0.42 against MediaPipe's outer eye corners. ML Kit
     * reports pupil centres, which span about 70% of that on the same face, so
     * holding 0.42 here zooms every crop in by a third and cuts the head off.
     * 0.31 puts the pupils where the model's own 112px alignment template puts
     * them, and matches what the prototype's thresholds were measured against.
     */
    const val EYE_SPAN = 0.31f

    /** Where the eye line sits vertically, leaving room for chin and hair. */
    const val EYE_HEIGHT = 0.42f

    private const val MIN_EYE_DISTANCE_PX = 2f
    private const val FALLBACK_MARGIN = 0.25f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @return a fresh SIZE x SIZE crop, or null when the eyes are too close to
     *   align from. [frame] is untouched and stays the caller's to recycle.
     */
    fun align(
        frame: Bitmap,
        box: BoundingBox,
        leftEye: Pair<Float, Float>?,
        rightEye: Pair<Float, Float>?,
    ): Bitmap? {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        if (leftEye != null && rightEye != null) {
            val matrix = matrixFor(leftEye, rightEye) ?: run { out.recycle(); return null }
            canvas.drawBitmap(frame, matrix, paint)
        } else {
            // No eyes to align to, so take the box with a generous margin. Drawn
            // straight from the source rect: an intermediate createBitmap crop
            // can alias the frame.
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
     * Frame-to-crop transform that levels the eyes and pins them to the template.
     *
     * The two eyes can arrive in either order. ML Kit names them from the
     * subject's point of view, so its LEFT_EYE is the one further right in the
     * image; taken as image-left it makes the eye vector point backwards and
     * the rotation come out near 180 degrees, which turns every face upside
     * down. Sorting by x makes the result independent of the naming.
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
