package com.shivansh.rollcall.data.detection

/**
 * Counts what happened to every face the detector returned.
 *
 * A face can leave the pipeline at three separate gates, and all three are
 * silent - the run simply reports one person fewer with no indication of which
 * gate took them. Tallying the drops turns "it missed someone" into a number
 * that says where to look.
 */
class RecallTally {

    var returnedByDetector = 0
        private set
    var droppedForBlur = 0
        private set
    var droppedForDegenerateBox = 0
        private set
    var droppedForNoEmbedding = 0
        private set

    /** Size ratios of faces the detector returned, smallest first. */
    private val sizeRatios = mutableListOf<Float>()

    fun countReturned(widthRatio: Float) {
        returnedByDetector++
        sizeRatios += widthRatio
    }

    fun countBlurDrop() {
        droppedForBlur++
    }

    fun countDegenerateBox() {
        droppedForDegenerateBox++
    }

    fun countEmbeddingDrop() {
        droppedForNoEmbedding++
    }

    val kept: Int
        get() = returnedByDetector - droppedForBlur - droppedForDegenerateBox -
            droppedForNoEmbedding

    /**
     * The smallest faces that survived, as a fraction of frame width.
     *
     * If the smallest kept face sits right on the detector's size floor, the
     * floor is probably clipping faces that were never reported at all - which
     * is invisible from inside the pipeline.
     */
    fun smallestKeptRatios(count: Int = 3): List<Float> =
        sizeRatios.sorted().take(count)

    fun summary(): String = buildString {
        append("faces: detector returned $returnedByDetector, kept $kept")
        if (droppedForBlur > 0) append(", blur dropped $droppedForBlur")
        if (droppedForDegenerateBox > 0) append(", bad box dropped $droppedForDegenerateBox")
        if (droppedForNoEmbedding > 0) append(", no embedding dropped $droppedForNoEmbedding")
        val smallest = smallestKeptRatios()
        if (smallest.isNotEmpty()) {
            append(", smallest widths ")
            append(smallest.joinToString(" ") { "%.3f".format(it) })
        }
    }
}
