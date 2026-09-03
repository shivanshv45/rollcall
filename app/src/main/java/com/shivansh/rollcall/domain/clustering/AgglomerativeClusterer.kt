package com.shivansh.rollcall.domain.clustering

/**
 * Groups tracklets into people without being told how many there are.
 *
 * Average linkage, because single linkage chains: one borderline pair is enough
 * to weld two similar-looking people together. k-means needs the count up front,
 * and DBSCAN would write off a briefly-seen person as noise.
 */
class AgglomerativeClusterer(
    private val coreThreshold: Double,
    private val assignThreshold: Double,
    private val minCoreSize: Int,
) {

    /**
     * @param cannotLink pairs that must never merge. Two faces in one frame are
     *   different people, whatever the embeddings say.
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
     * Fragments from two-person shots embed poorly and land outside the strict
     * threshold. Reporting one as an extra person is worse than attaching it to
     * the nearest real identity.
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
            val ranked = strong.mapNotNull { other ->
                val to = members[other] ?: return@mapNotNull null
                if (isBlocked(group, to, blocked)) null
                else averageDistance(group, to, distances) to other
            }.sortedBy { it.first }

            val best = ranked.firstOrNull() ?: continue
            if (best.first >= assignThreshold) continue

            // On a near-tie the embedding can't separate them, so prefer the
            // smaller identity. Picking on a 0.01 margin leaves one person
            // over-counted and the other short.
            val runnerUp = ranked.getOrNull(1)
            val target = if (runnerUp != null && runnerUp.first - best.first < TIE_MARGIN) {
                listOf(best, runnerUp).minWith(
                    compareBy({ members[it.second]!!.size }, { it.first })
                ).second
            } else {
                best.second
            }

            members[target]!!.addAll(group)
            members.remove(key)
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

    private companion object {
        /** Gap below which two candidates count as tied. Same result from 0.04 to 0.20. */
        const val TIE_MARGIN = 0.08
    }
}
