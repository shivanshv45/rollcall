package com.shivansh.rollcall.domain.model

/**
 * Whether a cluster has enough behind it to be reported as a person.
 *
 * A strong cluster always is. A weak one that could not be absorbed is either a
 * real person seen briefly, who still leaves several samples behind, or a stub
 * that got blocked from its own identity by a stray detection. The stub has a
 * couple of samples at most, and reporting it puts a ninth tile on a five-person
 * collage.
 */
object PersonEvidence {

    /** Fewer samples than this, in a weak cluster, is not a person. About 0.6s at 5 fps. */
    const val MIN_SAMPLES = 3

    fun isEnough(tracklets: List<Tracklet>, minCoreTracks: Int): Boolean {
        if (tracklets.size >= minCoreTracks) return true
        return tracklets.sumOf { it.samples.size } >= MIN_SAMPLES
    }
}
