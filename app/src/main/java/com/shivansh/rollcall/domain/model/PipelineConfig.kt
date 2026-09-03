package com.shivansh.rollcall.domain.model

/**
 * Every tuned constant in the pipeline, in one place.
 *
 * The thresholds were measured, not chosen: a sweep over the three sample clips
 * (tools/prototype) plotted cluster count against threshold and these sit at the
 * midpoint of the region where all three clips report the same, correct count.
 * Sitting mid-plateau rather than at its edge is what makes them survive footage
 * they weren't tuned on.
 */
data class PipelineConfig(
    val sampleFps: Double = 5.0,

    /** Half of 1080x1920. Detection and embedding gain nothing from full res. */
    val workWidth: Int = 540,
    val workHeight: Int = 960,

    /**
     * Laplacian variance below this means whip-pan blur. The distribution is
     * bimodal on this kind of footage - smears land under 2, usable frames above
     * 5 - so anything in the empty middle works and the exact value doesn't matter.
     */
    val blurFloor: Double = 3.0,

    /** Ignore faces smaller than this fraction of the frame width. */
    val minFaceRatio: Float = 0.10f,

    /** A box within this many pixels of the edge counts as clipped. */
    val edgeMarginPx: Int = 4,

    /** Cosine distance. Below this two tracklets are confidently the same person. */
    val coreThreshold: Double = 0.38,

    /**
     * Relaxed threshold for placing leftover fragments. Short or side-on faces
     * from two-person shots sit 0.5-0.7 from their own identity while distinct
     * people sit above 0.9, and no single threshold spans that.
     */
    val assignThreshold: Double = 0.84,

    /** Fewer tracklets than this makes a fragment, not a person. */
    val minCoreTracks: Int = 3,

    /** Sightings further apart than this are separate appearances. */
    val maxGapSeconds: Double = 0.8,

    /** Frame-to-frame dissimilarity that counts as a hard cut. */
    val sceneCutThreshold: Double = 0.45,

    /** Padding around the detected box before embedding, as a fraction of it. */
    val cropMargin: Float = 0.25f,

    /** How far outside the face box the representative crop reaches. */
    val portraitCropScale: Float = 3.2f,
) {
    val frameIntervalMs: Long get() = (1000.0 / sampleFps).toLong()
}
