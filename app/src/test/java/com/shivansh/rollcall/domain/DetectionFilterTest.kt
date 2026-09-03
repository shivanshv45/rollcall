package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.DetectionFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two boxes on one face share a timestamp, so the clusterer is told they are two
 * people and can never merge them. That put one woman on four collage tiles.
 */
class DetectionFilterTest {

    @Test
    fun `a nested box inside a close-up is dropped`() {
        val face = BoundingBox(50, 50, 400, 400)
        val nested = BoundingBox(150, 180, 120, 120)
        assertEquals(listOf(0), DetectionFilter.suppressOverlaps(listOf(face, nested)))
    }

    @Test
    fun `a duplicate box shifted a few pixels is dropped`() {
        val a = BoundingBox(100, 100, 200, 200)
        val b = BoundingBox(110, 105, 200, 200)
        assertEquals(1, DetectionFilter.suppressOverlaps(listOf(a, b)).size)
    }

    @Test
    fun `the larger of a duplicate pair is the one kept`() {
        val small = BoundingBox(100, 100, 180, 180)
        val large = BoundingBox(95, 95, 200, 200)
        assertEquals(listOf(1), DetectionFilter.suppressOverlaps(listOf(small, large)))
    }

    @Test
    fun `two people side by side are both kept`() {
        val left = BoundingBox(40, 200, 200, 200)
        val right = BoundingBox(300, 210, 200, 200)
        assertEquals(listOf(0, 1), DetectionFilter.suppressOverlaps(listOf(left, right)))
    }

    @Test
    fun `touching neighbours are still two people`() {
        // Cheek to cheek: edges meet, no overlap.
        val left = BoundingBox(0, 0, 100, 100)
        val right = BoundingBox(100, 0, 100, 100)
        assertEquals(listOf(0, 1), DetectionFilter.suppressOverlaps(listOf(left, right)))
    }

    @Test
    fun `a slight overlap between neighbours does not merge them`() {
        // Two faces overlapping by a sliver, as happens in a tight two-shot.
        val left = BoundingBox(0, 0, 100, 100)
        val right = BoundingBox(90, 0, 100, 100)
        assertEquals(listOf(0, 1), DetectionFilter.suppressOverlaps(listOf(left, right)))
    }

    @Test
    fun `three boxes on one face collapse to one`() {
        val boxes = listOf(
            BoundingBox(100, 100, 300, 300),
            BoundingBox(120, 110, 280, 290),
            BoundingBox(200, 200, 100, 100),
        )
        assertEquals(listOf(0), DetectionFilter.suppressOverlaps(boxes))
    }

    @Test
    fun `order is preserved for what survives`() {
        val boxes = listOf(
            BoundingBox(300, 0, 100, 100),
            BoundingBox(0, 0, 100, 100),
            BoundingBox(600, 0, 100, 100),
        )
        assertEquals(listOf(0, 1, 2), DetectionFilter.suppressOverlaps(boxes))
    }

    @Test
    fun `empty in, empty out`() {
        assertEquals(emptyList<Int>(), DetectionFilter.suppressOverlaps(emptyList()))
    }
}
