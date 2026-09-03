package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.clustering.AgglomerativeClusterer
import com.shivansh.rollcall.domain.clustering.CosineDistance
import com.shivansh.rollcall.domain.model.l2Normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgglomerativeClustererTest {

    private fun clusterer(core: Double = 0.38, assign: Double = 0.84, minCore: Int = 3) =
        AgglomerativeClusterer(core, assign, minCore)

    private fun distinctGroups(perGroup: Int, groups: Int): List<FloatArray> =
        (0 until groups).flatMap { g ->
            (0 until perGroup).map { i ->
                FloatArray(groups) { axis -> if (axis == g) 1f else 0f }
                    .also { it[g] += i * 0.01f }
                    .l2Normalized()
            }
        }

    @Test
    fun `finds the right number of well-separated groups`() {
        val labels = clusterer().cluster(
            CosineDistance.matrix(distinctGroups(perGroup = 4, groups = 3)),
            emptySet(),
        )
        assertEquals(3, labels.toSet().size)
    }

    @Test
    fun `count is discovered, not assumed`() {
        for (expected in 1..5) {
            val labels = clusterer().cluster(
                CosineDistance.matrix(distinctGroups(perGroup = 4, groups = expected)),
                emptySet(),
            )
            assertEquals("with $expected groups", expected, labels.toSet().size)
        }
    }

    @Test
    fun `average linkage resists chaining through a bridge point`() {
        // Two tight groups plus one point sitting between them. Single linkage
        // would merge everything through the bridge; average linkage should not.
        val a = (0 until 4).map { floatArrayOf(1f, 0.02f * it, 0f).l2Normalized() }
        val b = (0 until 4).map { floatArrayOf(0f, 0.02f * it, 1f).l2Normalized() }
        val bridge = listOf(floatArrayOf(0.7f, 0f, 0.7f).l2Normalized())

        val labels = clusterer().cluster(CosineDistance.matrix(a + b + bridge), emptySet())
        assertNotEquals("bridge welded the groups together", 1, labels.toSet().size)
    }

    @Test
    fun `cannot-link keeps co-occurring faces apart even when identical`() {
        // Same vector twice: nothing but the constraint can separate them.
        val identical = listOf(
            floatArrayOf(1f, 0f, 0f).l2Normalized(),
            floatArrayOf(1f, 0f, 0f).l2Normalized(),
        )
        val labels = clusterer(core = 0.9, assign = 0.9, minCore = 1)
            .cluster(CosineDistance.matrix(identical), setOf(0 to 1))
        assertNotEquals(labels[0], labels[1])
    }

    @Test
    fun `fragments are absorbed rather than reported as extra people`() {
        val core = (0 until 4).map { floatArrayOf(1f, 0.01f * it, 0f).l2Normalized() }
        // A single loose sample of the same person: outside the strict threshold,
        // inside the relaxed one.
        val fragment = listOf(floatArrayOf(0.80f, 0.60f, 0f).l2Normalized())

        val labels = clusterer().cluster(CosineDistance.matrix(core + fragment), emptySet())
        assertEquals(1, labels.toSet().size)
    }

    @Test
    fun `a tied fragment goes to the smaller identity`() {
        // Four tracklets for one person, three for another, and a fragment sitting
        // almost exactly between them. Distance alone is a coin flip, so it should
        // land on the shorter list rather than making an already-long one longer.
        val big = (0 until 4).map { floatArrayOf(1f, 0f, 0.01f * it).l2Normalized() }
        val small = (0 until 3).map { floatArrayOf(0f, 1f, 0.01f * it).l2Normalized() }
        val fragment = listOf(floatArrayOf(0.707f, 0.707f, 0f).l2Normalized())

        val labels = clusterer(assign = 0.9)
            .cluster(CosineDistance.matrix(big + small + fragment), emptySet())

        val fragmentLabel = labels.last()
        assertEquals("fragment should join the 3-member group", labels[4], fragmentLabel)
        assertEquals(2, labels.toSet().size)
    }

    @Test
    fun `a clear winner is not overridden by the tie-break`() {
        val near = (0 until 4).map { floatArrayOf(1f, 0f, 0.01f * it).l2Normalized() }
        val far = (0 until 3).map { floatArrayOf(0f, 0f, 1f).l2Normalized() }
        val fragment = listOf(floatArrayOf(0.99f, 0.14f, 0f).l2Normalized())

        val labels = clusterer(assign = 0.9)
            .cluster(CosineDistance.matrix(near + far + fragment), emptySet())

        assertEquals("fragment belongs with the group it is close to", labels[0], labels.last())
    }

    @Test
    fun `labels are contiguous from zero`() {
        val labels = clusterer().cluster(
            CosineDistance.matrix(distinctGroups(perGroup = 3, groups = 4)),
            emptySet(),
        )
        assertEquals((0 until labels.toSet().size).toSet(), labels.toSet())
    }

    @Test
    fun `empty input is handled`() {
        assertEquals(0, clusterer().cluster(emptyArray(), emptySet()).size)
    }

    @Test
    fun `cosine distance behaves`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0, CosineDistance.between(a, a), 1e-6)
        assertEquals(1.0, CosineDistance.between(a, b), 1e-6)
        assertEquals(
            CosineDistance.between(a, b),
            CosineDistance.between(b, a),
            1e-9,
        )
        assertTrue(CosineDistance.between(a, floatArrayOf(-1f, 0f, 0f)) > 1.9)
    }

    /**
     * A lone tracklet sitting just outside the assign threshold used to survive
     * as its own cluster, which the UI then showed as a duplicate person with
     * one appearance.
     */
    @Test
    fun `a lone leftover is absorbed rather than reported as a person`() {
        val people = distinctGroups(perGroup = 4, groups = 2).toMutableList()

        // A hard angle on the first person: same direction, well off the core.
        val stray = FloatArray(2) { axis -> if (axis == 0) 1f else 0.85f }.l2Normalized()
        people += stray

        val labels = clusterer().cluster(CosineDistance.matrix(people), emptySet())
        assertEquals(2, labels.toSet().size)
    }

    @Test
    fun `an absorbed leftover joins the identity it resembles`() {
        val people = distinctGroups(perGroup = 4, groups = 2).toMutableList()
        val stray = FloatArray(2) { axis -> if (axis == 0) 1f else 0.85f }.l2Normalized()
        people += stray

        val labels = clusterer().cluster(CosineDistance.matrix(people), emptySet())
        assertEquals("stray belongs with the first group", labels[0], labels.last())
    }

    /** Absorbing leftovers must not override the same-frame constraint. */
    @Test
    fun `a leftover is never absorbed into someone it shared a frame with`() {
        val people = distinctGroups(perGroup = 4, groups = 2).toMutableList()
        val stray = FloatArray(2) { axis -> if (axis == 0) 1f else 0.85f }.l2Normalized()
        people += stray
        val strayIndex = people.lastIndex

        // Block the stray from the group it would otherwise join.
        val blocked = (0 until 4).map { it to strayIndex }.toSet()
        val labels = clusterer().cluster(CosineDistance.matrix(people), blocked)

        assertNotEquals(labels[0], labels[strayIndex])
    }

    /** Four unit vectors on axis 0, plus a small spread so they are distinct. */
    private fun strongPerson(dim: Int = 4): List<FloatArray> =
        (0 until 4).map { i ->
            FloatArray(dim) { axis -> if (axis == 0) 1f else 0f }
                .also { it[1] += i * 0.01f }
                .l2Normalized()
        }

    /**
     * Four shots of one person spread around a cone about axis 1. At 39 degrees
     * each adjacent pair is ~0.40 apart, just over the strict threshold, and
     * opposite pairs ~0.79, just under the relaxed one. No three of them are
     * close enough to form a core.
     */
    private fun fragmentedPerson(): List<FloatArray> {
        val theta = Math.toRadians(39.0)
        return listOf(0.0, 90.0, 180.0, 270.0).map { phi ->
            val p = Math.toRadians(phi)
            floatArrayOf(
                0f,
                Math.cos(theta).toFloat(),
                (Math.sin(theta) * Math.cos(p)).toFloat(),
                (Math.sin(theta) * Math.sin(p)).toFloat(),
            ).l2Normalized()
        }
    }

    private fun unit(axis: Int, dim: Int = 4) =
        FloatArray(dim) { if (it == axis) 1f else 0f }.l2Normalized()

    /**
     * A person seen only in fragments has no strong cluster to be folded into,
     * and used to be reported once per fragment, each with one appearance.
     */
    @Test
    fun `fragments of one person merge even with no strong core`() {
        val vectors = strongPerson() + fragmentedPerson()

        val labels = clusterer().cluster(CosineDistance.matrix(vectors), emptySet())
        assertEquals(2, labels.toSet().size)
        assertEquals("all four fragments in one identity", 1, labels.drop(4).toSet().size)
    }

    @Test
    fun `leftovers of two different people are not merged together`() {
        val vectors = strongPerson() + unit(1) + unit(2)

        val labels = clusterer().cluster(CosineDistance.matrix(vectors), emptySet())
        assertNotEquals(labels[4], labels[5])
    }

    @Test
    fun `leftovers that shared a frame stay apart`() {
        val t = Math.toRadians(20.0)
        val a = unit(1)
        val b = floatArrayOf(0f, Math.cos(t).toFloat(), Math.sin(t).toFloat(), 0f).l2Normalized()
        val vectors = strongPerson() + a + b

        // Alike enough to merge on sight; the same-frame block must still win.
        val labels = clusterer().cluster(CosineDistance.matrix(vectors), setOf(4 to 5))
        assertNotEquals(labels[4], labels[5])
    }
}
