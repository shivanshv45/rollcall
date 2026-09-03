package com.shivansh.rollcall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks the collage geometry and that drawing into it works.
 *
 * The layout maths mirrors CollageRenderer.layoutFor, which is private and needs
 * a video Uri a JVM test cannot supply. Worth the duplication to catch
 * overlapping or off-canvas tiles.
 */
@RunWith(RobolectricTestRunner::class)
class CollageLayoutTest {

    private val width = 1080
    private val height = 1920
    private val margin = 48f
    private val gutter = 16f
    private val headerHeight = 150f
    private val footerHeight = 120f

    private fun layoutFor(count: Int): List<RectF> {
        val left = margin
        val right = width - margin
        val top = headerHeight
        val bottom = height - footerHeight
        val w = right - left
        val h = bottom - top
        val g = gutter

        fun grid(columns: Int, rows: Int): List<RectF> {
            val cellW = (w - g * (columns - 1)) / columns
            val cellH = (h - g * (rows - 1)) / rows
            return (0 until rows).flatMap { r ->
                (0 until columns).map { c ->
                    RectF(
                        left + c * (cellW + g),
                        top + r * (cellH + g),
                        left + c * (cellW + g) + cellW,
                        top + r * (cellH + g) + cellH,
                    )
                }
            }
        }

        return when (count) {
            0 -> emptyList()
            1 -> listOf(RectF(left, top, right, bottom))
            2 -> grid(1, 2)
            3 -> {
                val heroH = h * 0.46f
                listOf(RectF(left, top, right, top + heroH)) +
                    grid(2, 1).map { RectF(it.left, top + heroH + g, it.right, bottom) }
            }
            4 -> grid(2, 2)
            5 -> {
                val heroH = h * 0.34f
                val restTop = top + heroH + g
                val cellW = (w - g) / 2
                val cellH = (bottom - restTop - g) / 2
                listOf(RectF(left, top, right, top + heroH)) +
                    (0 until 2).flatMap { r ->
                        (0 until 2).map { c ->
                            RectF(
                                left + c * (cellW + g),
                                restTop + r * (cellH + g),
                                left + c * (cellW + g) + cellW,
                                restTop + r * (cellH + g) + cellH,
                            )
                        }
                    }
            }
            6 -> grid(2, 3)
            in 7..9 -> grid(3, 3).take(count)
            else -> {
                val columns = 3
                grid(columns, (count + columns - 1) / columns).take(count)
            }
        }
    }

    private fun RectF.overlaps(other: RectF) =
        left < other.right - 0.01f && other.left < right - 0.01f &&
            top < other.bottom - 0.01f && other.top < bottom - 0.01f

    @Test
    fun `every count produces exactly that many tiles`() {
        for (n in 0..12) assertEquals("count $n", n, layoutFor(n).size)
    }

    @Test
    fun `tiles never overlap`() {
        for (n in 1..12) {
            val tiles = layoutFor(n)
            for (i in tiles.indices) {
                for (j in i + 1 until tiles.size) {
                    assertFalse(
                        "count $n: tile $i overlaps $j",
                        tiles[i].overlaps(tiles[j]),
                    )
                }
            }
        }
    }

    @Test
    fun `tiles stay inside the safe area`() {
        for (n in 1..12) {
            for (tile in layoutFor(n)) {
                assertTrue("count $n left", tile.left >= margin - 0.01f)
                assertTrue("count $n top", tile.top >= headerHeight - 0.01f)
                assertTrue("count $n right", tile.right <= width - margin + 0.01f)
                assertTrue("count $n bottom", tile.bottom <= height - footerHeight + 0.01f)
            }
        }
    }

    @Test
    fun `no tile is too small to show a face`() {
        for (n in 1..12) {
            for (tile in layoutFor(n)) {
                assertTrue("count $n width ${tile.width()}", tile.width() >= 100f)
                assertTrue("count $n height ${tile.height()}", tile.height() >= 100f)
            }
        }
    }

    @Test
    fun `five people get a hero tile`() {
        // The common case for these clips, and the layout that should not look
        // like a plain grid.
        val tiles = layoutFor(5)
        val hero = tiles.first()
        assertTrue("hero should span the full width", hero.width() > width * 0.8f)
        tiles.drop(1).forEach {
            assertTrue("the rest should be smaller", it.width() < hero.width())
        }
    }

    @Test
    fun `the canvas renders at story size and takes tile drawing`() {
        val collage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        canvas.drawColor(Color.parseColor("#0B0B0F"))

        val portrait = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
        Canvas(portrait).drawColor(Color.RED)

        for (tile in layoutFor(5)) {
            canvas.drawBitmap(
                portrait,
                Rect(0, 0, portrait.width, portrait.height),
                Rect(
                    tile.left.toInt(), tile.top.toInt(),
                    tile.right.toInt(), tile.bottom.toInt(),
                ),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        portrait.recycle()

        assertEquals(1080, collage.width)
        assertEquals(1920, collage.height)
        assertFalse(collage.isRecycled)
    }
}
