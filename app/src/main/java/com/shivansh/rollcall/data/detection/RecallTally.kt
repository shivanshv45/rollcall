package com.shivansh.rollcall.data.detection

/**
 * Counts what happened to every face the detector returned.
 *
 * Three gates can drop a face and all three are silent: the run just reports one
 * person fewer. This turns "it missed someone" into a number naming the gate.
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
    var duplicateBoxes = 0
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

    fun countDuplicates(count: Int) {
        duplicateBoxes += count
    }

    val kept: Int
        get() = returnedByDetector - duplicateBoxes - droppedForBlur -
            droppedForDegenerateBox - droppedForNoEmbedding

    /**
     * The smallest faces that survived, as a fraction of frame width.
     *
     * If the smallest sits right on the size floor, the floor is probably
     * clipping faces that were never reported at all.
     */
    fun smallestKeptRatios(count: Int = 3): List<Float> =
        sizeRatios.sorted().take(count)

    fun summary(): String = buildString {
        append("faces: detector returned $returnedByDetector, kept $kept")
        if (duplicateBoxes > 0) append(", duplicate boxes $duplicateBoxes")
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
