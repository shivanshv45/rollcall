package com.shivansh.rollcall

import android.graphics.Bitmap
import com.shivansh.rollcall.data.video.NeighbourBox
import com.shivansh.rollcall.data.video.PortraitCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs on every representative shot, at sizes not known until decode time, with
 * boxes that can sit hard against an edge.
 */
@RunWith(RobolectricTestRunner::class)
class PortraitCropperTest {

    private fun frame(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun crop(
        frame: Bitmap,
        centerX: Float,
        centerY: Float,
        boxW: Float = 80f,
        boxH: Float = 80f,
        faceScale: Float = 1f,
        cropScale: Float = 2.6f,
        aspect: Float = 1.25f,
    ) = PortraitCropper.crop(
        frame = frame,
        boxCenterX = centerX,
        boxCenterY = centerY,
        boxWidth = boxW,
        boxHeight = boxH,
        faceScale = faceScale,
        cropScale = cropScale,
        aspect = aspect,
    )

    @Test
    fun `returns a new bitmap rather than the source`() {
        val source = frame(400, 700)
        // A crop covering the frame is where createBitmap hands back the source.
        val out = crop(source, 200f, 350f, boxW = 400f, boxH = 400f, cropScale = 4f)

        assertFalse(out === source)
        source.recycle()
        assertFalse("crop must survive the frame being recycled", out.isRecycled)
    }

    @Test
    fun `crop never exceeds the frame`() {
        val source = frame(300, 500)
        val out = crop(source, 150f, 250f, boxW = 300f, boxH = 300f, cropScale = 8f)

        assertTrue(out.width <= source.width)
        assertTrue(out.height <= source.height)
    }

    @Test
    fun `a face against the left edge still yields a valid crop`() {
        val out = crop(frame(400, 700), centerX = 5f, centerY = 350f)
        assertTrue(out.width > 0)
        assertTrue(out.height > 0)
    }

    @Test
    fun `a face against the top edge still yields a valid crop`() {
        // Headroom pushes the crop above the frame, giving a negative origin.
        val out = crop(frame(400, 700), centerX = 200f, centerY = 2f)
        assertTrue(out.width > 0)
        assertTrue(out.height > 0)
    }

    @Test
    fun `a face against the bottom right corner still yields a valid crop`() {
        val out = crop(frame(400, 700), centerX = 399f, centerY = 699f)
        assertTrue(out.width > 0)
        assertTrue(out.height > 0)
    }

    @Test
    fun `crop is wider than the face box`() {
        val out = crop(frame(1080, 1920), centerX = 540f, centerY = 960f, boxW = 100f)
        // The brief rules out cropping tight to the detected box.
        assertTrue("expected head and shoulders, got ${out.width}px", out.width > 100)
    }

    @Test
    fun `aspect ratio is honoured away from the edges`() {
        val out = crop(frame(2000, 2000), centerX = 1000f, centerY = 1000f, aspect = 1.25f)
        assertEquals(1.25f, out.height.toFloat() / out.width, 0.02f)
    }

    @Test
    fun `face scale maps a working frame box onto a larger frame`() {
        // Box measured at 540 wide, frame decoded at 1080: same face, twice the pixels.
        val small = crop(frame(1080, 1920), 270f, 480f, faceScale = 1f)
        val scaled = crop(frame(1080, 1920), 270f, 480f, faceScale = 2f)

        assertEquals(2f, scaled.width.toFloat() / small.width, 0.05f)
    }

    @Test
    fun `a square aspect gives a square thumbnail`() {
        val out = crop(frame(1200, 1200), 600f, 600f, aspect = 1f)
        assertEquals(out.width, out.height)
    }

    /**
     * A wide crop in a two-person shot used to swallow the other person, so the
     * tile showed two faces and neither read as a portrait of one.
     */
    @Test
    fun `crop stops short of a neighbouring face`() {
        val frame = frame(1000, 1000)
        // Subject at x=300, neighbour centred at x=600 with a 100px half-width,
        // so the neighbour's near edge is at 500.
        val out = PortraitCropper.crop(
            frame = frame,
            boxCenterX = 300f,
            boxCenterY = 500f,
            boxWidth = 120f,
            boxHeight = 120f,
            faceScale = 1f,
            cropScale = 3.2f,
            aspect = 1f,
            neighbours = listOf(NeighbourBox(centerX = 600f, halfWidth = 100f)),
        )

        // Without the limit this would be 384px wide and reach past x=492.
        assertTrue("crop was ${out.width}px, expected under 384", out.width < 384)
    }

    @Test
    fun `a neighbour on the left is avoided too`() {
        val out = PortraitCropper.crop(
            frame = frame(1000, 1000),
            boxCenterX = 700f,
            boxCenterY = 500f,
            boxWidth = 120f,
            boxHeight = 120f,
            faceScale = 1f,
            cropScale = 3.2f,
            aspect = 1f,
            neighbours = listOf(NeighbourBox(centerX = 400f, halfWidth = 100f)),
        )
        assertTrue("crop was ${out.width}px", out.width < 384)
    }

    @Test
    fun `no neighbours means the full wide crop`() {
        val wide = crop(frame(1000, 1000), 500f, 500f, boxW = 120f, boxH = 120f)
        val limited = PortraitCropper.crop(
            frame = frame(1000, 1000),
            boxCenterX = 500f,
            boxCenterY = 500f,
            boxWidth = 120f,
            boxHeight = 120f,
            faceScale = 1f,
            cropScale = 2.6f,
            aspect = 1.25f,
            neighbours = emptyList(),
        )
        assertEquals(wide.width, limited.width)
    }

    /** Even a close neighbour must not squeeze the crop down to a mugshot. */
    @Test
    fun `a very close neighbour still leaves more than the face`() {
        val out = PortraitCropper.crop(
            frame = frame(1000, 1000),
            boxCenterX = 500f,
            boxCenterY = 500f,
            boxWidth = 100f,
            boxHeight = 100f,
            faceScale = 1f,
            cropScale = 3.2f,
            aspect = 1f,
            neighbours = listOf(NeighbourBox(centerX = 560f, halfWidth = 20f)),
        )
        assertTrue("crop was ${out.width}px, expected wider than the face", out.width > 100)
    }

    /** What the picker relies on: a neighbour's centre never lands inside the rect. */
    @Test
    fun `rect excludes the centre of a neighbouring face`() {
        val rect = PortraitCropper.rectFor(
            frameWidth = 1000, frameHeight = 1000,
            boxCenterX = 300f, boxCenterY = 500f, boxWidth = 120f, boxHeight = 120f,
            faceScale = 1f, cropScale = 3.2f, aspect = 1f,
            neighbours = listOf(NeighbourBox(centerX = 520f, halfWidth = 60f)),
        )
        assertFalse(rect.contains(520, 500))
    }
}
