package com.shivansh.rollcall.domain.model

/**
 * Every tuned constant in the pipeline.
 *
 * These came out of a threshold sweep over the sample clips (see tools/prototype).
 * Each sits mid-plateau rather than at an edge, so it holds on other footage.
 */
data class PipelineConfig(
    val sampleFps: Double = 5.0,

    /** Half of 1080x1920. Detection and embedding gain nothing from full res. */
    val workWidth: Int = 540,
    val workHeight: Int = 960,

    // Laplacian variance below this is whip-pan blur. The distribution is
    // bimodal (smears under 2, usable frames above 5) so anything between works.
    val blurFloor: Double = 3.0,

    /**
     * Smallest face ML Kit will report, as a fraction of frame width.
     *
     * A recall floor, not a quality one: nothing downstream can recover a face
     * the detector never returned. Low enough for two people sharing a frame,
     * but not so low that a 25px smudge gets an embedding.
     */
    val minFaceRatio: Float = 0.07f,

    /** A box within this many pixels of the edge counts as clipped. */
    val edgeMarginPx: Int = 4,

    /** Cosine distance. Below this two tracklets are confidently the same person. */
    val coreThreshold: Double = 0.38,

    // Relaxed threshold for leftover fragments. Side-on faces sit 0.5-0.7 from
    // their own identity while different people sit above 0.9.
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
