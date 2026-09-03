package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.segmentation.AppearanceSegmenter
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceSegmenterTest {

    // 5fps sampling, gap threshold 0.8s - the tuned production values.
    private val segmenter = AppearanceSegmenter(maxGapMs = 800, frameIntervalMs = 200)

    /** Sample times in ms. Integer maths throughout, as the pipeline uses. */
    private fun run(fromSeconds: Double, toSeconds: Double, stepMs: Long = 200) =
        generateSequence((fromSeconds * 1000).toLong()) { it + stepMs }
            .takeWhile { it <= (toSeconds * 1000).toLong() }
            .toList()

    @Test
    fun `a continuous run is one appearance`() {
        assertEquals(1, segmenter.segment(run(0.0, 1.2), emptyList()).size)
    }

    @Test
    fun `the brief's 1_4 second example is one appearance`() {
        // "A and B share the frame at 10.1-11.5s" - a single continuous segment.
        val appearances = segmenter.segment(run(10.2, 11.4), emptyList())
        assertEquals(1, appearances.size)
        assertEquals(10200, appearances.first().startMs)
        assertEquals(11600, appearances.first().endMs)
    }

    @Test
    fun `a long gap splits into two`() {
        assertEquals(2, segmenter.segment(run(0.0, 1.2) + run(5.0, 6.2), emptyList()).size)
    }

    @Test
    fun `a blink-length gap does not split`() {
        // One missed sample: 0.4s apart, inside the 0.8s tolerance.
        val times = run(0.0, 1.0) + run(1.4, 2.0)
        assertEquals(1, segmenter.segment(times, emptyList()).size)
    }

    @Test
    fun `a gap exactly at the threshold does not split`() {
        val times = listOf(0L, 800L)
        assertEquals(1, segmenter.segment(times, emptyList()).size)
    }

    @Test
    fun `a gap just past the threshold splits`() {
        val times = listOf(0L, 1000L)
        assertEquals(2, segmenter.segment(times, emptyList()).size)
    }

    @Test
    fun `a scene cut splits a run with no gap in it`() {
        // The case gap-splitting alone would miss: a cut from close-up to a wider
        // shot that still shows the same person.
        val times = run(0.0, 2.0)
        assertEquals(2, segmenter.segment(times, sceneCutsMs = listOf(1000)).size)
    }

    @Test
    fun `a cut outside the run changes nothing`() {
        assertEquals(1, segmenter.segment(run(0.0, 1.0), sceneCutsMs = listOf(5000)).size)
    }

    @Test
    fun `a single sighting still counts`() {
        // Short appearances in two-person shots are real and must not be dropped.
        val appearances = segmenter.segment(listOf(20400), emptyList())
        assertEquals(1, appearances.size)
        assertEquals(200, appearances.first().durationMs)
    }

    @Test
    fun `no sightings means no appearances`() {
        assertEquals(0, segmenter.segment(emptyList(), emptyList()).size)
    }

    @Test
    fun `unsorted and duplicated input is handled`() {
        val messy = listOf(600L, 0L, 200L, 200L, 400L)
        assertEquals(1, segmenter.segment(messy, emptyList()).size)
    }

    @Test
    fun `four spaced appearances count as four`() {
        // The shape of a real person in the sample clips.
        val times = run(0.0, 1.2) + run(10.0, 11.2) + run(20.0, 21.2) + run(28.0, 29.2)
        assertEquals(4, segmenter.segment(times, emptyList()).size)
    }
}
