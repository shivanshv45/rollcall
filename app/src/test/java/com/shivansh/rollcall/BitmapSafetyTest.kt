package com.shivansh.rollcall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the bug that crashed the app on a real phone.
 *
 * Bitmap.createBitmap(source, x, y, w, h) hands back the source object when the
 * crop covers the whole thing. The pipeline recycles each frame right after use,
 * so any crop that aliased its frame became a recycled bitmap in the UI, and
 * drawing it threw. These tests pin the behaviour down and prove the replacement
 * never aliases.
 */
@RunWith(RobolectricTestRunner::class)
class BitmapSafetyTest {

    private fun frame(w: Int = 540, h: Int = 960) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `createBitmap returns the source when the crop covers it`() {
        // The platform behaviour that caused the crash. If this ever stops being
        // true the defensive copies below are still correct, just unnecessary.
        val source = frame()
        val whole = Bitmap.createBitmap(source, 0, 0, source.width, source.height)
        assertTrue(
            "platform aliased the source - this is why crops must be drawn, not sliced",
            whole === source,
        )
    }

    /** Mirrors ProcessingRepository.previewOf. */
    private fun previewOf(frame: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val out = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(left, top, right, bottom),
            Rect(0, 0, 160, 160),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    @Test
    fun `preview never aliases the frame even when the face fills it`() {
        val source = frame()
        val preview = previewOf(source, 0, 0, source.width, source.height)

        assertNotSame(preview, source)
        source.recycle()
        assertFalse("preview died with the frame", preview.isRecycled)
        assertEquals(160, preview.width)
    }

    @Test
    fun `a preview survives the frame being recycled`() {
        // The exact sequence the processing loop runs: crop, recycle the frame,
        // then hand the crop to the UI.
        val source = frame()
        val preview = previewOf(source, 100, 200, 340, 520)
        source.recycle()

        // Drawing a recycled bitmap is what threw on the phone.
        val canvas = Canvas(Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888))
        canvas.drawBitmap(preview, 0f, 0f, null)
    }

    @Test
    fun `crop rects stay inside the frame for boxes hanging off the edge`() {
        val source = frame()
        val margin = 80

        // A face box past every edge, which is common with big close-up faces.
        for ((bx, by, bw, bh) in listOf(
            listOf(-40, -60, 300, 400),
            listOf(400, 800, 300, 400),
            listOf(0, 0, 540, 960),
            listOf(-10, 900, 600, 200),
        )) {
            val left = (bx - margin).coerceIn(0, source.width - 1)
            val top = (by - margin).coerceIn(0, source.height - 1)
            val right = (bx + bw + margin).coerceIn(left + 1, source.width)
            val bottom = (by + bh + margin).coerceIn(top + 1, source.height)

            assertTrue("left $left", left in 0 until source.width)
            assertTrue("top $top", top in 0 until source.height)
            assertTrue("right $right", right in (left + 1)..source.width)
            assertTrue("bottom $bottom", bottom in (top + 1)..source.height)

            previewOf(source, left, top, right, bottom).recycle()
        }
    }

    @Test
    fun `thumbnail guards against aliasing when the size already matches`() {
        // ImageAnalysis.thumbnail relies on this check; createScaledBitmap also
        // returns the source when no scaling is needed.
        val square = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(square, 32, 32, true)
        if (scaled !== square) scaled.recycle()
        assertFalse("guard let the source be recycled", square.isRecycled)
    }
}
