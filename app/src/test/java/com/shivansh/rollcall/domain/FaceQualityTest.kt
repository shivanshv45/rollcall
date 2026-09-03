package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceQuality
import com.shivansh.rollcall.domain.model.FaceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceQualityTest {

    private fun sample(
        sharpness: Float = 0.5f,
        frontality: Float = 0.5f,
        eyesOpen: Float = 0.5f,
        sizeRatio: Float = 0.5f,
        expression: Float = 0.5f,
    ) = FaceSample(
        timestampMs = 0,
        box = BoundingBox(0, 0, 100, 100),
        sharpness = sharpness,
        frontality = frontality,
        eyesOpen = eyesOpen,
        sizeRatio = sizeRatio,
        expression = expression,
        isClipped = false,
        trackId = 0,
    )

    @Test
    fun `weights sum to one`() {
        val total = FaceQuality.SHARPNESS + FaceQuality.FRONTALITY +
            FaceQuality.EYES_OPEN + FaceQuality.FACE_SIZE + FaceQuality.EXPRESSION
        assertEquals(1.0f, total, 1e-6f)
    }

    @Test
    fun `all-best scores one and all-worst scores zero`() {
        assertEquals(1f, sample(1f, 1f, 1f, 1f, 1f).quality, 1e-6f)
        assertEquals(0f, sample(0f, 0f, 0f, 0f, 0f).quality, 1e-6f)
    }

    @Test
    fun `sharper is better`() {
        assertTrue(sample(sharpness = 0.9f).quality > sample(sharpness = 0.1f).quality)
    }

    @Test
    fun `more frontal is better`() {
        assertTrue(sample(frontality = 0.9f).quality > sample(frontality = 0.1f).quality)
    }

    @Test
    fun `open eyes are better`() {
        assertTrue(sample(eyesOpen = 1f).quality > sample(eyesOpen = 0f).quality)
    }

    @Test
    fun `sharpness outweighs expression`() {
        // A crisp neutral shot should beat a blurry smiling one - the brief asks
        // for pleasant shots, but not at the cost of a usable image.
        val crispNeutral = sample(sharpness = 1f, expression = 0f)
        val blurrySmile = sample(sharpness = 0f, expression = 1f)
        assertTrue(crispNeutral.quality > blurrySmile.quality)
    }

    @Test
    fun `score stays in range`() {
        for (v in listOf(-1f, 0f, 0.5f, 1f, 2f)) {
            val q = sample(v, v, v, v, v).quality
            assertTrue("q=$q for v=$v", q in 0f..1f)
        }
    }

    @Test
    fun `frontality peaks head-on and falls off to the side`() {
        assertEquals(1f, FaceQuality.frontality(0f, 0f), 1e-6f)
        assertTrue(FaceQuality.frontality(45f, 0f) < FaceQuality.frontality(10f, 0f))
        assertTrue(FaceQuality.frontality(0f, 45f) < FaceQuality.frontality(0f, 10f))
    }

    @Test
    fun `frontality never goes negative`() {
        assertTrue(FaceQuality.frontality(120f, 0f) >= 0f)
        assertTrue(FaceQuality.frontality(0f, 200f) >= 0f)
    }

    @Test
    fun `yaw and pitch compound`() {
        val both = FaceQuality.frontality(40f, 40f)
        assertTrue(both < FaceQuality.frontality(40f, 0f))
        assertTrue(both < FaceQuality.frontality(0f, 40f))
    }
}
