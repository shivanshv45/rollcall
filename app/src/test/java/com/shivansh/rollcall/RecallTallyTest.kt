package com.shivansh.rollcall

import com.shivansh.rollcall.data.detection.RecallTally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A missed person is the worst failure this app has, and three separate gates
 * can cause one silently. The tally is what turns "it missed someone" into a
 * number naming the gate, so it has to add up.
 */
class RecallTallyTest {

    @Test
    fun `kept is what the detector returned minus every drop`() {
        val tally = RecallTally()
        repeat(10) { tally.countReturned(0.2f) }
        tally.countBlurDrop()
        tally.countBlurDrop()
        tally.countEmbeddingDrop()
        tally.countDegenerateBox()

        assertEquals(10, tally.returnedByDetector)
        assertEquals(6, tally.kept)
    }

    @Test
    fun `a clean run keeps everything`() {
        val tally = RecallTally()
        repeat(4) { tally.countReturned(0.3f) }
        assertEquals(4, tally.kept)
    }

    @Test
    fun `smallest ratios are reported in ascending order`() {
        val tally = RecallTally()
        listOf(0.30f, 0.06f, 0.51f, 0.12f).forEach { tally.countReturned(it) }

        assertEquals(listOf(0.06f, 0.12f, 0.30f), tally.smallestKeptRatios())
    }

    /**
     * The smallest face the detector reports sitting on the size floor is the
     * signal that the floor is clipping faces upstream, where nothing
     * downstream can recover them.
     */
    @Test
    fun `the smallest ratio exposes a floor that is clipping faces`() {
        val tally = RecallTally()
        listOf(0.051f, 0.052f, 0.40f).forEach { tally.countReturned(it) }

        val floor = 0.05f
        assertTrue(tally.smallestKeptRatios().first() - floor < 0.01f)
    }

    @Test
    fun `summary names each gate that dropped a face`() {
        val tally = RecallTally()
        repeat(5) { tally.countReturned(0.2f) }
        tally.countBlurDrop()
        tally.countEmbeddingDrop()

        val summary = tally.summary()
        assertTrue(summary, summary.contains("returned 5"))
        assertTrue(summary, summary.contains("kept 3"))
        assertTrue(summary, summary.contains("blur dropped 1"))
        assertTrue(summary, summary.contains("no embedding dropped 1"))
    }

    @Test
    fun `summary omits gates that dropped nothing`() {
        val tally = RecallTally()
        repeat(3) { tally.countReturned(0.2f) }

        val summary = tally.summary()
        assertTrue(summary, !summary.contains("blur"))
        assertTrue(summary, !summary.contains("bad box"))
    }

    @Test
    fun `an empty tally does not claim to have kept anything`() {
        val tally = RecallTally()
        assertEquals(0, tally.kept)
        assertTrue(tally.smallestKeptRatios().isEmpty())
    }
}
