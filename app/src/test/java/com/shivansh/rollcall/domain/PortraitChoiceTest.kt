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

    /**
     * Mirrors PortraitPicker's candidate order: solo frames are a hard
     * preference, not a scoring penalty, so a sharper two-shot never wins over
     * a usable solo frame.
     */
    private fun candidateOrder(samples: List<FaceSample>): List<FaceSample> {
        val (solo, shared) = samples.partition { it.coFaces.isEmpty() }
        val byScore = compareByDescending<FaceSample> { FaceQuality.portraitScore(it) }
        return if (solo.any { FaceQuality.isUsablePortrait(it) }) {
            solo.sortedWith(byScore) + shared.sortedWith(byScore)
        } else {
            samples.sortedWith(byScore)
        }
    }

    @Test
    fun `a solo frame is tried before a sharper shared one`() {
        val sharpShared = sample(t = 0, sharpness = 1f, coFaces = neighbour)
        val softSolo = sample(t = 200, sharpness = 0.4f)

        assertEquals(softSolo, candidateOrder(listOf(sharpShared, softSolo)).first())
    }

    @Test
    fun `every solo frame is tried before any shared one`() {
        val samples = listOf(
            sample(t = 0, sharpness = 1f, coFaces = neighbour),
            sample(t = 200, sharpness = 0.9f, coFaces = neighbour),
            sample(t = 400, sharpness = 0.3f),
            sample(t = 600, sharpness = 0.5f),
        )
        val order = candidateOrder(samples)
        assertTrue(order.take(2).all { it.coFaces.isEmpty() })
        assertTrue(order.drop(2).none { it.coFaces.isEmpty() })
    }

    @Test
    fun `someone only ever seen with others still gets a candidate`() {
        val samples = listOf(
            sample(t = 0, sharpness = 0.6f, coFaces = neighbour),
            sample(t = 200, sharpness = 0.9f, coFaces = neighbour),
        )
        val order = candidateOrder(samples)
        assertEquals(2, order.size)
        assertEquals(200L, order.first().timestampMs)
    }

    @Test
    fun `solo frames are still ranked among themselves`() {
        val dull = sample(t = 0, sharpness = 0.3f)
        val best = sample(t = 200, sharpness = 0.95f)
        assertEquals(best, candidateOrder(listOf(dull, best)).first())
    }

    /**
     * The solo preference is worth having only while the solo frame is worth
     * looking at. A smear the person has to themselves makes a worse tile than
     * a clean frame cropped out of a two-shot.
     */
    @Test
    fun `a clean shared frame beats an unusable solo one`() {
        val smear = sample(t = 0, sharpness = 0.05f, frontality = 0.2f)
        val cleanShared = sample(t = 200, sharpness = 0.95f, coFaces = neighbour)

        assertEquals(cleanShared, candidateOrder(listOf(smear, cleanShared)).first())
    }

    @Test
    fun `a merely soft solo frame still wins, being usable`() {
        val soft = sample(t = 0, sharpness = 0.55f)
        val sharpShared = sample(t = 200, sharpness = 1f, coFaces = neighbour)

        assertEquals(soft, candidateOrder(listOf(soft, sharpShared)).first())
    }

    @Test
    fun `an unusable solo frame is still offered as a fallback`() {
        val smear = sample(t = 0, sharpness = 0.05f, frontality = 0.2f)
        val cleanShared = sample(t = 200, sharpness = 0.95f, coFaces = neighbour)

        assertEquals(2, candidateOrder(listOf(smear, cleanShared)).size)
    }

    @Test
    fun `a back-of-the-head solo frame does not count as usable`() {
        assertFalse(FaceQuality.isUsablePortrait(sample(frontality = 0.1f)))
    }

    @Test
    fun `an ordinary solo frame counts as usable`() {
        assertTrue(FaceQuality.isUsablePortrait(sample(sharpness = 0.6f, frontality = 0.8f)))
    }
}
