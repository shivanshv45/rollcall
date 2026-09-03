package com.shivansh.rollcall.domain.model

/** One face in one sampled frame, with the attributes the quality score needs. */
data class FaceSample(
    val timestampMs: Long,
    val box: BoundingBox,
    val sharpness: Float,
    val frontality: Float,
    val eyesOpen: Float,
    val sizeRatio: Float,
    val expression: Float,
    val isClipped: Boolean,
    val trackId: Int,
    val embedding: FloatArray? = null,
    /** Other faces in this frame, so a crop can avoid reaching into them. */
    val coFaces: List<BoundingBox> = emptyList(),
) {
    val quality: Float get() = FaceQuality.score(this)

    // Data class equality compares FloatArray by reference, which would quietly
    // break any set or map keyed on samples.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceSample) return false
        return timestampMs == other.timestampMs && trackId == other.trackId && box == other.box
    }

    override fun hashCode(): Int =
        (timestampMs.hashCode() * 31 + trackId) * 31 + box.hashCode()
}

data class BoundingBox(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
    val area get() = width.toLong() * height
}

/**
 * A run of the same face across consecutive frames, reduced to one embedding.
 *
 * Averaging suppresses per-frame noise and cuts the clustering input by ~10x.
 * Weighting by quality lets the clean frames dominate.
 */
data class Tracklet(
    val id: Int,
    val samples: List<FaceSample>,
) {
    val startMs get() = samples.first().timestampMs
    val endMs get() = samples.last().timestampMs

    val embedding: FloatArray by lazy {
        val dim = samples.first().embedding!!.size
        val sum = FloatArray(dim)
        var weight = 0f
        for (s in samples) {
            val q = s.quality
            val e = s.embedding!!
            for (i in 0 until dim) sum[i] += e[i] * q
            weight += q
        }
        // Dividing by a zero weight gives a vector that normalises to NaN, and
        // that poisons every distance. An unweighted mean still locates the face.
        if (weight <= 0f) {
            java.util.Arrays.fill(sum, 0f)
            for (s in samples) {
                val e = s.embedding!!
                for (i in 0 until dim) sum[i] += e[i]
            }
            for (i in 0 until dim) sum[i] /= samples.size
        } else {
            for (i in 0 until dim) sum[i] /= weight
        }
        sum.l2Normalized()
    }

    /** Best shot in this tracklet, penalising faces that run off the frame edge. */
    val best: FaceSample get() = samples.maxBy { it.quality - if (it.isClipped) 0.25f else 0f }
}

/** One continuous stretch where a person is visible. */
data class Appearance(val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

data class Person(
    val id: Int,
    val appearances: List<Appearance>,
    val representative: FaceSample,
    val tracklets: List<Tracklet>,
) {
    val appearanceCount get() = appearances.size
    val screenTimeMs get() = appearances.sumOf { it.durationMs }
    /** A, B, C... so identity is not carried by colour alone. */
    val label: String get() = ('A' + id).toString()
}

fun FloatArray.l2Normalized(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v.toDouble() * v
    val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-9f)
    return FloatArray(size) { this[it] / norm }
}
