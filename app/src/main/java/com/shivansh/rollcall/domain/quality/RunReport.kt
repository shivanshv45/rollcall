package com.shivansh.rollcall.domain.model

import com.shivansh.rollcall.domain.clustering.CosineDistance

/**
 * What the pipeline saw, as text.
 *
 * A wrong count on a phone gives nothing to go on by itself. This is the set of
 * numbers that separates the possible causes: how many faces were found and
 * kept, how they grouped, and how far apart the final people are from each
 * other. Shown from the results screen and shareable.
 */
object RunReport {

    fun build(
        recall: String,
        tracklets: List<Tracklet>,
        labels: IntArray,
        people: List<Person>,
        cannotLink: Set<Pair<Int, Int>>,
        durationMs: Long,
        elapsedMs: Long,
    ): String = buildString {
        appendLine("Roll Call run report")
        appendLine("clip ${durationMs / 1000}s, processed in ${elapsedMs / 1000}s")
        appendLine()
        appendLine(recall)
        appendLine()

        val sizes = tracklets.map { it.samples.size }
        appendLine("tracklets: ${tracklets.size}, samples per tracklet " +
            "min ${sizes.minOrNull() ?: 0} median ${median(sizes)} max ${sizes.maxOrNull() ?: 0}")
        appendLine("identities after clustering: ${labels.toSet().size}")
        appendLine("same-frame pairs: ${cannotLink.size}")
        appendLine()

        appendLine("people:")
        for (p in people) {
            val samples = p.tracklets.sumOf { it.samples.size }
            val rep = p.representative
            appendLine(
                "  ${p.label}  tracklets ${p.tracklets.size}  samples $samples  " +
                    "appearances ${p.appearanceCount}  screen ${p.screenTimeMs / 1000}s  " +
                    "shot at ${rep.timestampMs}ms" +
                    (if (rep.coFaces.isNotEmpty()) " (shared frame)" else "")
            )
        }
        appendLine()

        if (people.size > 1) {
            appendLine("distance between people (lower = more alike):")
            val means = people.map { meanOf(it.tracklets) }
            val blocked = blockedPeople(people, cannotLink)
            append("     ")
            people.forEach { append(it.label.padStart(6)) }
            appendLine()
            for (i in people.indices) {
                append("  ${people[i].label}  ")
                for (j in people.indices) {
                    val cell = when {
                        i == j -> "-"
                        else -> "%.2f".format(CosineDistance.between(means[i], means[j])) +
                            if ((i to j) in blocked || (j to i) in blocked) "*" else ""
                    }
                    append(cell.padStart(6))
                }
                appendLine()
            }
            appendLine("  * shared a frame, so never merged")
        }
    }

    private fun meanOf(tracklets: List<Tracklet>): FloatArray {
        val dim = tracklets.first().embedding.size
        val sum = FloatArray(dim)
        for (t in tracklets) {
            val e = t.embedding
            for (i in 0 until dim) sum[i] += e[i]
        }
        return sum.l2Normalized()
    }

    private fun blockedPeople(people: List<Person>, cannotLink: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        val owner = HashMap<Int, Int>()
        people.forEachIndexed { pi, p -> p.tracklets.forEach { owner[it.id] = pi } }
        val out = HashSet<Pair<Int, Int>>()
        for ((a, b) in cannotLink) {
            val pa = owner[a] ?: continue
            val pb = owner[b] ?: continue
            if (pa != pb) out += pa to pb
        }
        return out
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return s[s.size / 2]
    }
}
