package com.shivansh.rollcall

import com.shivansh.rollcall.domain.model.Appearance
import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.Person
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ids are both the displayed label and an index into the list.
 *
 * A gap reads as a roster running "A, B, D, E" and puts lookups off the end.
 */
class PersonIdentityTest {

    private fun sample() = FaceSample(
        timestampMs = 0L,
        box = BoundingBox(0, 0, 10, 10),
        sharpness = 1f,
        frontality = 1f,
        eyesOpen = 1f,
        sizeRatio = 0.2f,
        expression = 0.5f,
        isClipped = false,
        trackId = 0,
        embedding = FloatArray(192) { 0.1f },
    )

    private fun person(appearances: List<Appearance>) = Person(
        id = 0,
        appearances = appearances,
        representative = sample(),
        tracklets = emptyList(),
    )

    private fun oneAppearance(start: Long) = listOf(Appearance(start, start + 500))

    /** Mirrors the repository's final ordering step. */
    private fun finalise(people: List<Person>): List<Person> =
        people
            .sortedBy { it.appearances.firstOrNull()?.startMs ?: Long.MAX_VALUE }
            .filter { it.appearances.isNotEmpty() }
            .mapIndexed { index, person -> person.copy(id = index) }

    @Test
    fun `ids stay contiguous when a person has no appearances`() {
        val people = finalise(
            listOf(
                person(oneAppearance(0)),
                person(emptyList()),
                person(oneAppearance(1_000)),
                person(oneAppearance(2_000)),
            )
        )

        assertEquals(3, people.size)
        assertEquals(listOf(0, 1, 2), people.map { it.id })
        assertEquals(listOf("A", "B", "C"), people.map { it.label })
    }

    @Test
    fun `every id indexes inside the list it belongs to`() {
        val people = finalise(
            listOf(
                person(emptyList()),
                person(oneAppearance(0)),
                person(emptyList()),
                person(oneAppearance(1_000)),
            )
        )

        people.forEach { assertEquals(it, people[it.id]) }
    }

    /**
     * The sort parks empty people last, so numbering before filtering only breaks
     * once something is dropped mid-list. Filtering first holds either way.
     */
    @Test
    fun `ids stay contiguous when a dropped person sorts mid-list`() {
        val people = listOf(
            person(oneAppearance(0)),
            person(emptyList()),
            person(oneAppearance(2_000)),
        )
            .filter { it.appearances.isNotEmpty() }
            .mapIndexed { index, p -> p.copy(id = index) }

        assertEquals(listOf(0, 1), people.map { it.id })
        people.forEach { assertEquals(it, people[it.id]) }
    }

    @Test
    fun `ordering still follows first appearance`() {
        val people = finalise(
            listOf(
                person(oneAppearance(5_000)),
                person(oneAppearance(1_000)),
                person(oneAppearance(3_000)),
            )
        )

        assertEquals(listOf(1_000L, 3_000L, 5_000L), people.map { it.appearances.first().startMs })
        assertEquals(listOf(0, 1, 2), people.map { it.id })
    }
}
