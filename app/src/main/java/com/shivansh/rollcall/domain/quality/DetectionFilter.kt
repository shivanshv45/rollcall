package com.shivansh.rollcall.domain.model

/**
 * Cleans up one frame's detections before they become samples.
 *
 * The detector sometimes returns two boxes for one face: a duplicate, or a small
 * box nested inside a large close-up. Left in, the pair shares a timestamp, so
 * the clusterer is told they are two different people and can never merge them.
 * That is how one face ends up on four collage tiles.
 */
object DetectionFilter {

    /** Boxes overlapping this much are one face. Real neighbours sit far below it. */
    const val MAX_OVERLAP = 0.3f

    /** A box mostly inside another is a nested detection of the same face. */
    const val MAX_CONTAINMENT = 0.6f

    /**
     * Keeps the largest of any overlapping group.
     *
     * @return the indices to keep, in the original order.
     */
    fun suppressOverlaps(boxes: List<BoundingBox>): List<Int> {
        val bySize = boxes.indices.sortedByDescending { boxes[it].area }
        val kept = mutableListOf<Int>()
        for (i in bySize) {
            val box = boxes[i]
            val duplicate = kept.any { k -> sameFace(boxes[k], box) }
            if (!duplicate) kept += i
        }
        return kept.sorted()
    }

    private fun sameFace(larger: BoundingBox, smaller: BoundingBox): Boolean {
        val overlap = intersection(larger, smaller)
        if (overlap <= 0L) return false
        val iou = overlap.toFloat() / (larger.area + smaller.area - overlap)
        val contained = overlap.toFloat() / smaller.area
        return iou > MAX_OVERLAP || contained > MAX_CONTAINMENT
    }

    private fun intersection(a: BoundingBox, b: BoundingBox): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }
}
