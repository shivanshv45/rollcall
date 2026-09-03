package com.shivansh.rollcall

import android.graphics.Bitmap
import com.shivansh.rollcall.data.video.PortraitCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The crop runs on every representative shot, on frames whose size is not known
 * until decode time, with boxes that can sit hard against an edge. Getting it
 * wrong shows up as a crash or a face half out of the tile.
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
        // A crop wide enough to cover the frame is where createBitmap would
        // hand back the source, which the caller then recycles.
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
        // Headroom pushes the crop above the frame here, which is the case that
        // produced a negative origin before clamping.
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
}
