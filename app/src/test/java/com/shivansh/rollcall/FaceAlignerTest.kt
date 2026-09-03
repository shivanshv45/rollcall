package com.shivansh.rollcall

import android.graphics.Bitmap
import com.shivansh.rollcall.data.embedding.FaceAligner
import com.shivansh.rollcall.data.embedding.FaceAligner.EYE_HEIGHT
import com.shivansh.rollcall.data.embedding.FaceAligner.EYE_SPAN
import com.shivansh.rollcall.data.embedding.FaceAligner.SIZE
import com.shivansh.rollcall.domain.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * The alignment is the one place the port diverges from the prototype in what
 * it is handed: MediaPipe gave the prototype eyes named by image side, ML Kit
 * names them by the subject's side. Getting that wrong embeds every face upside
 * down, which is invisible in a unit of code and only shows up as one person
 * clustering into three.
 */
@RunWith(RobolectricTestRunner::class)
class FaceAlignerTest {

    /** Where the pupils should land in the crop, in pixels. */
    private val leftTarget = SIZE / 2f - SIZE * EYE_SPAN / 2f to SIZE * EYE_HEIGHT
    private val rightTarget = SIZE / 2f + SIZE * EYE_SPAN / 2f to SIZE * EYE_HEIGHT

    private fun mapped(eyeA: Pair<Float, Float>, eyeB: Pair<Float, Float>): Pair<FloatArray, FloatArray> {
        val m = FaceAligner.matrixFor(eyeA, eyeB) ?: error("expected a matrix")
        val a = floatArrayOf(eyeA.first, eyeA.second).also { m.mapPoints(it) }
        val b = floatArrayOf(eyeB.first, eyeB.second).also { m.mapPoints(it) }
        return a to b
    }

    private fun assertAt(expected: Pair<Float, Float>, actual: FloatArray) {
        assertEquals("x", expected.first, actual[0], 0.05f)
        assertEquals("y", expected.second, actual[1], 0.05f)
    }

    @Test
    fun `image-left then image-right lands the pupils on the template`() {
        val (imageLeft, imageRight) = mapped(100f to 200f, 160f to 200f)
        assertAt(leftTarget, imageLeft)
        assertAt(rightTarget, imageRight)
    }

    @Test
    fun `ML Kit order - subject's left first - gives the identical result`() {
        // ML Kit's LEFT_EYE is the subject's left, which sits further right in
        // the image. Passing it first must not flip the face.
        val (subjectLeft, subjectRight) = mapped(160f to 200f, 100f to 200f)
        assertAt(rightTarget, subjectLeft)
        assertAt(leftTarget, subjectRight)
    }

    @Test
    fun `a tilted face is levelled`() {
        // Eyes 60px apart, rotated 25 degrees about their midpoint.
        val cx = 300f
        val cy = 400f
        val r = 30f
        val t = Math.toRadians(25.0)
        val left = (cx - r * cos(t)).toFloat() to (cy - r * sin(t)).toFloat()
        val right = (cx + r * cos(t)).toFloat() to (cy + r * sin(t)).toFloat()

        val (l, rt) = mapped(left, right)
        assertAt(leftTarget, l)
        assertAt(rightTarget, rt)
        assertEquals("eye line must be level", l[1], rt[1], 0.05f)
    }

    @Test
    fun `a tilted face passed in ML Kit order is levelled the same way`() {
        val cx = 300f
        val cy = 400f
        val r = 30f
        val t = Math.toRadians(-40.0)
        val imageLeft = (cx - r * cos(t)).toFloat() to (cy - r * sin(t)).toFloat()
        val imageRight = (cx + r * cos(t)).toFloat() to (cy + r * sin(t)).toFloat()

        val (a, b) = mapped(imageRight, imageLeft)
        assertAt(rightTarget, a)
        assertAt(leftTarget, b)
    }

    @Test
    fun `scale follows pupil distance`() {
        // Twice the pupil distance in the frame must scale down by half, so
        // both faces end up the same size in the crop.
        val (nearL, nearR) = mapped(100f to 100f, 140f to 100f)
        val (farL, farR) = mapped(100f to 100f, 180f to 100f)
        assertEquals(nearR[0] - nearL[0], farR[0] - farL[0], 0.05f)
        assertEquals(SIZE * EYE_SPAN, farR[0] - farL[0], 0.05f)
    }

    @Test
    fun `eyes on top of each other cannot be aligned`() {
        assertNull(FaceAligner.matrixFor(50f to 50f, 50.5f to 50f))
    }

    @Test
    fun `aligned crop is a fresh bitmap of template size`() {
        val frame = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val out = FaceAligner.align(frame, BoundingBox(200, 300, 120, 120), 230f to 350f, 290f to 350f)

        assertNotNull(out)
        assertEquals(SIZE, out!!.width)
        assertEquals(SIZE, out.height)
        assertFalse(out === frame)
        frame.recycle()
        assertFalse(out.isRecycled)
    }

    @Test
    fun `missing eyes fall back to the box without failing`() {
        val frame = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val out = FaceAligner.align(frame, BoundingBox(0, 0, 80, 80), null, null)

        assertNotNull(out)
        assertEquals(SIZE, out!!.width)
    }
}
