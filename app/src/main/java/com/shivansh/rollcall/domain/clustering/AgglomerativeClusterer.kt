package com.shivansh.rollcall.domain.clustering

/**
 * Groups tracklets into people without being told how many there are.
 *
 * Average linkage rather than single linkage: single linkage chains, so one
 * borderline pair welds two similar-looking people into a single identity, and
 * the sample clips have exactly that hazard - two people in near-identical
 * headscarves. Average linkage needs overall similarity to merge, which resists
 * it. k-means and DBSCAN are both unusable here: k-means needs the count up
 * front, and DBSCAN would label a briefly-seen person as noise and drop them.
 */
class AgglomerativeClusterer(
    private val coreThreshold: Double,
    private val assignThreshold: Double,
    private val minCoreSize: Int,
) {

    /**
     * @param cannotLink pairs that must never merge. Two faces visible in the
     *   same frame are provably different people, which is a free hard constraint
     *   the embeddings can't override.
     * @return cluster index per input, contiguous from 0.
     */
    fun cluster(distances: Array<DoubleArray>, cannotLink: Set<Pair<Int, Int>>): IntArray {
        val n = distances.size
        if (n == 0) return IntArray(0)

        val blocked = Array(n) { BooleanArray(n) }
        for ((a, b) in cannotLink) {
            blocked[a][b] = true
            blocked[b][a] = true
        }

        val members = LinkedHashMap<Int, MutableList<Int>>(n)
        for (i in 0 until n) members[i] = mutableListOf(i)

        // Strict pass: merge only what is confidently the same person.
        while (true) {
            val pair = closestPair(members, distances, blocked, coreThreshold) ?: break
            members[pair.first]!!.addAll(members.remove(pair.second)!!)
        }

        absorbFragments(members, distances, blocked)

        val labels = IntArray(n)
        members.values.sortedBy { it.min() }.forEachIndexed { label, group ->
            for (i in group) labels[i] = label
        }
        return labels
    }

    /**
     * Second pass: fold weak clusters into strong ones.
     *
     * A fragment left over from a two-person shot embeds poorly - the face is
     * small or side-on - so it sits well outside the strict threshold from its
     * own identity. Left alone it would be reported as an extra person, which is
     * a worse error than attaching it to the nearest real one.
     */
    private fun absorbFragments(
        members: MutableMap<Int, MutableList<Int>>,
        distances: Array<DoubleArray>,
        blocked: Array<BooleanArray>,
    ) {
        val strong = members.filterValues { it.size >= minCoreSize }.keys.toList()
        if (strong.isEmpty()) return

        val weak = members.filterValues { it.size < minCoreSize }.keys
            .sortedBy { members[it]!!.size }

        for (key in weak) {
            val group = members[key] ?: continue
            var bestDistance = assignThreshold
            var target: Int? = null
            for (other in strong) {
                val to = members[other] ?: continue
                if (isBlocked(group, to, blocked)) continue
                val d = averageDistance(group, to, distances)
                if (d < bestDistance) {
                    bestDistance = d
                    target = other
                }
            }
            target?.let {
                members[it]!!.addAll(group)
                members.remove(key)
            }
        }
    }

    private fun closestPair(
        members: Map<Int, List<Int>>,
        distances: Array<DoubleArray>,
        blocked: Array<BooleanArray>,
        limit: Double,
    ): Pair<Int, Int>? {
        val keys = members.keys.toList()
        var best = limit
        var found: Pair<Int, Int>? = null
        for (x in keys.indices) {
            for (y in x + 1 until keys.size) {
                val a = members[keys[x]]!!
                val b = members[keys[y]]!!
                if (isBlocked(a, b, blocked)) continue
                val d = averageDistance(a, b, distances)
                if (d < best) {
                    best = d
                    found = keys[x] to keys[y]
                }
            }
        }
        return found
    }

    private fun isBlocked(a: List<Int>, b: List<Int>, blocked: Array<BooleanArray>) =
        a.any { i -> b.any { j -> blocked[i][j] } }

    private fun averageDistance(a: List<Int>, b: List<Int>, d: Array<DoubleArray>): Double {
        var sum = 0.0
        for (i in a) for (j in b) sum += d[i][j]
        return sum / (a.size * b.size)
    }
}
