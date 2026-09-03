package com.shivansh.rollcall.domain.clustering

import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.Tracklet
import kotlin.math.hypot

/**
 * Chains faces across consecutive frames into runs, so clustering works on a few
 * dozen averaged embeddings instead of hundreds of noisy per-frame ones.
 *
 * A detector's own tracking id is used where it is stable, with position and
 * appearance as the fallback - ML Kit's ids are motion-based and do not survive
 * a cut, so they are a hint rather than an identity.
 */
class TrackletBuilder(
    private val frameIntervalMs: Long,
    private val maxSkippedFrames: Int = 2,
    private val maxBoxGap: Float = 0.5f,
    private val maxAppearanceGap: Double = 0.45,
) {

    fun build(samples: List<FaceSample>, sceneCutsMs: List<Long>): List<Tracklet> {
        val cuts = sceneCutsMs.toHashSet()
        val byTime = samples.groupBy { it.timestampMs }.toSortedMap()
        val open = mutableListOf<MutableList<FaceSample>>()
        val closed = mutableListOf<List<FaceSample>>()

        for ((time, atTime) in byTime) {
            val unclaimed = atTime.toMutableList()
            val carried = mutableListOf<MutableList<FaceSample>>()

            if (time !in cuts) {
                for (track in open) {
                    val last = track.last()
                    if (time - last.timestampMs > frameIntervalMs * maxSkippedFrames) continue
                    val match = matchFor(last, unclaimed) ?: continue
                    track += match
                    unclaimed -= match
                    carried += track
                }
            }

            // Identity, not equality: two tracks holding equal samples are still
            // separate tracks, and comparing by value would drop one of them.
            closed += open.filter { track -> carried.none { it === track } }
            open.clear()
            open += carried
            open += unclaimed.map { mutableListOf(it) }
        }
        closed += open

        return closed.filter { it.isNotEmpty() }
            .mapIndexed { index, run -> Tracklet(index, run) }
    }

    private fun matchFor(last: FaceSample, candidates: List<FaceSample>): FaceSample? =
        candidates
            .filter { it.trackId == last.trackId && it.trackId >= 0 }
            .minByOrNull { boxGap(last, it) }
            ?: candidates
                .filter { boxGap(last, it) < maxBoxGap && appearanceGap(last, it) < maxAppearanceGap }
                .minByOrNull { boxGap(last, it) }

    private fun boxGap(a: FaceSample, b: FaceSample): Float {
        val span = ((a.box.width + b.box.width) / 2f).coerceAtLeast(1f)
        return hypot(a.box.centerX - b.box.centerX, a.box.centerY - b.box.centerY) / span
    }

    private fun appearanceGap(a: FaceSample, b: FaceSample): Double {
        val x = a.embedding ?: return Double.MAX_VALUE
        val y = b.embedding ?: return Double.MAX_VALUE
        return CosineDistance.between(x, y)
    }

    /**
     * Pairs that share a frame. Two faces visible at the same instant belong to
     * different people, which is a hard fact the embeddings cannot override.
     */
    fun cannotLink(tracklets: List<Tracklet>): Set<Pair<Int, Int>> {
        val times = tracklets.map { t -> t.samples.mapTo(HashSet()) { it.timestampMs } }
        val out = mutableSetOf<Pair<Int, Int>>()
        for (i in tracklets.indices) {
            for (j in i + 1 until tracklets.size) {
                if (times[i].any { it in times[j] }) out += i to j
            }
        }
        return out
    }
}
