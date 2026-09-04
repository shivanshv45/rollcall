package com.shivansh.rollcall

import com.shivansh.rollcall.domain.model.CollageBackground
import com.shivansh.rollcall.domain.model.CollageBorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The border catalogue is data, so what is worth checking is that a new style
 * cannot quietly break the collage: an id collision would make the picker
 * select the wrong entry, and geometry past what CollageLayoutTest covers would
 * push a tile off the sheet.
 */
class CollageBorderTest {

    /** Matches the widest values CollageLayoutTest proves are safe. */
    private val maxMargin = 64f
    private val maxInset = 14f

    @Test
    fun `ids are unique`() {
        val ids = CollageBorder.ALL.map { it.id }
        assertEquals("two styles share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `every style has a label`() {
        for (border in CollageBorder.ALL) {
            assertTrue("${border.id} needs a label", border.label.isNotBlank())
        }
    }

    @Test
    fun `no style exceeds the geometry the layout is tested for`() {
        for (border in CollageBorder.ALL) {
            assertTrue(
                "${border.id} margin ${border.margin} is wider than tested",
                border.margin <= maxMargin,
            )
            assertTrue(
                "${border.id} inset ${border.tileInset} is wider than tested",
                border.tileInset <= maxInset,
            )
            assertTrue("${border.id} inset cannot be negative", border.tileInset >= 0f)
            assertTrue("${border.id} radius cannot be negative", border.tileRadius >= 0f)
        }
    }

    @Test
    fun `a stroke colour and a stroke width come as a pair`() {
        for (border in CollageBorder.ALL) {
            val hasColor = border.tileStrokeArgb != null
            val hasWidth = border.tileStrokeWidth > 0f
            assertEquals(
                "${border.id} declares one half of a stroke",
                hasColor,
                hasWidth,
            )
        }
    }

    @Test
    fun `gradients have a stop for every colour`() {
        for (border in CollageBorder.ALL) {
            val background = border.background
            if (background is CollageBackground.Gradient) {
                assertEquals(
                    "${border.id} gradient is mismatched",
                    background.colors.size,
                    background.stops.size,
                )
                assertTrue("${border.id} needs two colours", background.colors.size >= 2)
            }
        }
    }

    @Test
    fun `the signature style leads the strip`() {
        // It is what the collage looked like before there was a choice, so it
        // has to stay the one an untouched render uses.
        assertSame(CollageBorder.DEFAULT, CollageBorder.ALL.first())
    }

    @Test
    fun `byId finds every style`() {
        for (border in CollageBorder.ALL) {
            assertSame(border, CollageBorder.byId(border.id))
        }
    }

    @Test
    fun `byId falls back rather than failing on an unknown id`() {
        // An id saved by a build with a style this one does not have.
        assertSame(CollageBorder.DEFAULT, CollageBorder.byId("retired-style"))
        assertSame(CollageBorder.DEFAULT, CollageBorder.byId(null))
    }

    @Test
    fun `identical gradients compare equal`() {
        // Arrays default to reference equality, which would break the view
        // model's check for whether the border actually changed.
        val one = CollageBackground.Gradient(
            base = 0xFF000000.toInt(),
            colors = intArrayOf(1, 2),
            stops = floatArrayOf(0f, 1f),
        )
        val two = CollageBackground.Gradient(
            base = 0xFF000000.toInt(),
            colors = intArrayOf(1, 2),
            stops = floatArrayOf(0f, 1f),
        )
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertEquals(CollageBorder.NEON, CollageBorder.byId("neon"))
    }

    @Test
    fun `the strip offers a real choice`() {
        assertTrue("too few styles to be worth a picker", CollageBorder.ALL.size >= 3)
        val backgrounds = CollageBorder.ALL.map { it.background }.toSet()
        assertEquals(
            "styles should not share a background",
            CollageBorder.ALL.size,
            backgrounds.size,
        )
        assertNotNull(CollageBorder.ALL.firstOrNull { it.tileStrokeArgb != null })
    }
}
