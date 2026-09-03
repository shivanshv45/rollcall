package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceQuality
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.PersonEvidence
import com.shivansh.rollcall.domain.model.Tracklet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which frame becomes a person's tile, and which clusters become people at all. */
class PortraitChoiceTest {

    private fun sample(
        t: Long = 0,
        sharpness: Float = 0.9f,
        frontality: Float = 0.95f,
        clipped: Boolean = false,
        coFaces: List<BoundingBox> = emptyList(),
    ) = FaceSample(
        timestampMs = t,
        box = BoundingBox(100, 100, 120, 120),
        sharpness = sharpness,
        frontality = frontality,
        eyesOpen = 1f,
        sizeRatio = 0.5f,
        expression = 0.5f,
        isClipped = clipped,
        trackId = 0,
        embedding = FloatArray(4) { 0.5f },
        coFaces = coFaces,
    )

    private val neighbour = listOf(BoundingBox(300, 100, 120, 120))

    @Test
    fun `a solo frame beats a sharper frame shared with someone else`() {
        val shared = sample(sharpness = 1f, coFaces = neighbour)
        val solo = sample(sharpness = 0.7f)
        assertTrue(FaceQuality.portraitScore(solo) > FaceQuality.portraitScore(shared))
    }

    @Test
    fun `a clean face beats a clipped close-up that scores higher on size`() {
        val clipped = sample(sharpness = 0.6f, clipped = true)
        val clean = sample(sharpness = 0.6f)
        assertTrue(FaceQuality.portraitScore(clean) > FaceQuality.portraitScore(clipped))
    }

    @Test
    fun `a soft frame loses to a sharp one`() {
        val soft = sample(sharpness = 0.1f)
        val sharp = sample(sharpness = 0.8f)
        assertTrue(FaceQuality.portraitScore(sharp) > FaceQuality.portraitScore(soft))
    }

    @Test
    fun `a profile loses to a front-on face`() {
        val profile = sample(frontality = 0.3f)
        val front = sample(frontality = 0.95f)
        assertTrue(FaceQuality.portraitScore(front) > FaceQuality.portraitScore(profile))
    }

    @Test
    fun `tracklet best picks the solo frame`() {
        val shared = sample(t = 0, sharpness = 1f, coFaces = neighbour)
        val solo = sample(t = 200, sharpness = 0.8f)
        assertEquals(solo, Tracklet(0, listOf(shared, solo)).best)
    }

    @Test
    fun `a strong cluster is always a person`() {
        val tracklets = (0 until 3).map { Tracklet(it, listOf(sample(t = it * 200L))) }
        assertTrue(PersonEvidence.isEnough(tracklets, minCoreTracks = 3))
    }

    @Test
    fun `a briefly seen person with several samples is still a person`() {
        val run = (0 until 5).map { sample(t = it * 200L) }
        assertTrue(PersonEvidence.isEnough(listOf(Tracklet(0, run)), minCoreTracks = 3))
    }

    @Test
    fun `a two-sample stub is not a person`() {
        val stub = Tracklet(0, listOf(sample(t = 0), sample(t = 200)))
        assertFalse(PersonEvidence.isEnough(listOf(stub), minCoreTracks = 3))
    }

    @Test
    fun `two one-sample stubs together are still not a person`() {
        val stubs = listOf(Tracklet(0, listOf(sample(t = 0))), Tracklet(1, listOf(sample(t = 5000))))
        assertFalse(PersonEvidence.isEnough(stubs, minCoreTracks = 3))
    }
}
