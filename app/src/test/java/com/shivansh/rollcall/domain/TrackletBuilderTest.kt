package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.clustering.TrackletBuilder
import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.l2Normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackletBuilderTest {

    private val builder = TrackletBuilder(frameIntervalMs = 200)

    private fun face(
        timeMs: Long,
        x: Int = 200,
        y: Int = 400,
        trackId: Int = -1,
        vector: FloatArray = floatArrayOf(1f, 0f, 0f),
        quality: Float = 1f,
    ) = FaceSample(
        timestampMs = timeMs,
        box = BoundingBox(x, y, 120, 150),
        sharpness = quality,
        frontality = quality,
        eyesOpen = quality,
        sizeRatio = quality,
        expression = quality,
        isClipped = false,
        trackId = trackId,
        embedding = vector.l2Normalized(),
    )

    @Test
    fun `a steady run becomes one tracklet`() {
        val samples = (0..6).map { face(it * 200L) }
        val tracks = builder.build(samples, emptyList())
        assertEquals(1, tracks.size)
        assertEquals(7, tracks.first().samples.size)
    }

    @Test
    fun `a scene cut starts a new tracklet`() {
        val samples = (0..6).map { face(it * 200L) }
        val tracks = builder.build(samples, sceneCutsMs = listOf(600))
        assertEquals(2, tracks.size)
    }

    @Test
    fun `two people in the same frames make two tracklets`() {
        val samples = (0..5).flatMap {
            listOf(
                face(it * 200L, x = 60, vector = floatArrayOf(1f, 0f, 0f)),
                face(it * 200L, x = 380, vector = floatArrayOf(0f, 1f, 0f)),
            )
        }
        val tracks = builder.build(samples, emptyList())
        assertEquals(2, tracks.size)
        tracks.forEach { assertEquals(6, it.samples.size) }
    }

    @Test
    fun `co-occurring tracklets cannot link`() {
        val samples = (0..5).flatMap {
            listOf(
                face(it * 200L, x = 60, vector = floatArrayOf(1f, 0f, 0f)),
                face(it * 200L, x = 380, vector = floatArrayOf(0f, 1f, 0f)),
            )
        }
        val tracks = builder.build(samples, emptyList())
        assertEquals(setOf(0 to 1), builder.cannotLink(tracks))
    }

    @Test
    fun `tracklets that never share a frame are free to link`() {
        val first = (0..3).map { face(it * 200L) }
        val second = (10..13).map { face(it * 200L) }
        val tracks = builder.build(first + second, emptyList())
        assertTrue(builder.cannotLink(tracks).isEmpty())
    }

    @Test
    fun `identical parallel runs stay separate`() {
        // Regression: closing open tracks compared them by value, so two tracks
        // holding equal samples collapsed into one and a person was lost.
        val samples = (0..4).flatMap {
            listOf(
                face(it * 200L, x = 100, trackId = 1),
                face(it * 200L, x = 100, trackId = 2),
            )
        }
        val tracks = builder.build(samples, emptyList())
        assertEquals("a parallel track was dropped", 2, tracks.size)
    }

    @Test
    fun `a long absence splits the run`() {
        val before = (0..3).map { face(it * 200L) }
        val after = (10..13).map { face(it * 200L) }
        assertEquals(2, builder.build(before + after, emptyList()).size)
    }

    @Test
    fun `one skipped frame does not split the run`() {
        val samples = listOf(0L, 200L, 600L, 800L).map { face(it) }
        assertEquals(1, builder.build(samples, emptyList()).size)
    }

    @Test
    fun `a detector track id keeps a fast-moving face together`() {
        // Big positional jumps that would fail the distance check, but the
        // detector says it is the same face.
        val samples = listOf(
            face(0, x = 40, trackId = 7),
            face(200, x = 300, trackId = 7),
            face(400, x = 60, trackId = 7),
        )
        assertEquals(1, builder.build(samples, emptyList()).size)
    }

    @Test
    fun `the averaged embedding is a unit vector`() {
        val samples = (0..4).map { face(it * 200L) }
        val e = builder.build(samples, emptyList()).first().embedding
        val norm = kotlin.math.sqrt(e.sumOf { it.toDouble() * it })
        assertEquals(1.0, norm, 1e-5)
    }

    @Test
    fun `an all-zero-quality tracklet still yields a finite embedding`() {
        // Every weight is zero here; without a guarded divisor this is NaN and
        // it poisons every distance in the clustering step.
        val samples = (0..2).map { face(it * 200L, quality = 0f) }
        val e = builder.build(samples, emptyList()).first().embedding
        assertTrue("embedding was NaN", e.all { it.isFinite() })
    }

    @Test
    fun `no samples means no tracklets`() {
        assertTrue(builder.build(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `the best sample avoids clipped faces`() {
        val clean = face(0).copy(sharpness = 0.6f)
        val sharperButClipped = face(200).copy(sharpness = 1f, isClipped = true)
        val track = builder.build(listOf(clean, sharperButClipped), emptyList()).first()
        assertEquals(clean.timestampMs, track.best.timestampMs)
    }
}
