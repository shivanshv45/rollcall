package com.shivansh.rollcall.domain.segmentation

import com.shivansh.rollcall.domain.model.Appearance

/**
 * Turns a person's sighting times into appearances.
 *
 * The brief defines an appearance as one continuous visible segment, so this
 * splits on two things: a gap longer than [maxGapMs], and a scene cut crossed
 * without a gap. The second matters because a cut from a close-up to a wider
 * shot that still contains the same person starts a new appearance even though
 * the person never left the screen - gap-splitting alone would miss it and
 * silently undercount.
 */
class AppearanceSegmenter(
    private val maxGapMs: Long,
    private val frameIntervalMs: Long,
) {
    fun segment(timestampsMs: List<Long>, sceneCutsMs: List<Long>): List<Appearance> {
        if (timestampsMs.isEmpty()) return emptyList()

        val times = timestampsMs.distinct().sorted()
        val cuts = sceneCutsMs.sorted()
        val out = mutableListOf<Appearance>()

        var start = times.first()
        var previous = times.first()

        for (t in times.drop(1)) {
            val gapTooLong = t - previous > maxGapMs
            val crossedCut = cuts.any { it > previous && it <= t }
            if (gapTooLong || crossedCut) {
                out += Appearance(start, previous + frameIntervalMs)
                start = t
            }
            previous = t
        }
        out += Appearance(start, previous + frameIntervalMs)

        // No minimum duration: a person can legitimately be visible for a single
        // sample in a two-person shot, and filtering those out loses real
        // appearances. The blur gate already removes genuine flicker.
        return out
    }
}
