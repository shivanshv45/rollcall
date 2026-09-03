package com.shivansh.rollcall.domain.clustering

/** Cosine distance on L2-normalised vectors, so it reduces to 1 - dot product. */
object CosineDistance {
    fun between(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return 1.0 - dot
    }

    fun matrix(vectors: List<FloatArray>): Array<DoubleArray> {
        val n = vectors.size
        val d = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val v = between(vectors[i], vectors[j])
                d[i][j] = v
                d[j][i] = v
            }
        }
        return d
    }
}
