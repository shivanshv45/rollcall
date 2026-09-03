package com.shivansh.rollcall.domain

import com.shivansh.rollcall.domain.clustering.AgglomerativeClusterer
import com.shivansh.rollcall.domain.model.PipelineConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks the Kotlin clusterer against the Python prototype it came from.
 *
 * The fixture holds real distance matrices from the three sample clips with the
 * cluster counts the tuned prototype produced. Synthetic tests show the algorithm
 * is sensible; this shows the port did not drift.
 *
 * Regenerate with:  python tools/prototype/pipeline.py 8
 */
@RunWith(RobolectricTestRunner::class)
class PortParityTest {

    private val config = PipelineConfig()

    private fun fixture(): JSONObject {
        val text = javaClass.classLoader!!
            .getResourceAsStream("prototype_clusters.json")!!
            .bufferedReader().readText()
        return JSONObject(text)
    }

    private fun clusterer() = AgglomerativeClusterer(
        coreThreshold = config.coreThreshold,
        assignThreshold = config.assignThreshold,
        minCoreSize = config.minCoreTracks,
    )

    @Test
    fun `kotlin finds the same number of people as the prototype`() {
        val data = fixture()
        for (clip in data.keys()) {
            val entry = data.getJSONObject(clip)
            val matrix = entry.getJSONArray("distances")
            val n = matrix.length()

            val distances = Array(n) { i ->
                val row = matrix.getJSONArray(i)
                DoubleArray(n) { j -> row.getDouble(j) }
            }
            val cannotLink = mutableSetOf<Pair<Int, Int>>()
            val pairs = entry.getJSONArray("cannotLink")
            for (k in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(k)
                cannotLink += pair.getInt(0) to pair.getInt(1)
            }

            val labels = clusterer().cluster(distances, cannotLink)
            assertEquals(
                "$clip: cluster count drifted from the prototype",
                entry.getInt("expectedClusters"),
                labels.toSet().size,
            )
        }
    }

    @Test
    fun `every sample clip resolves to five people`() {
        // The cast is the same five people across all three clips. Nothing tells
        // the algorithm that - it is discovered from the embeddings.
        val data = fixture()
        for (clip in data.keys()) {
            val entry = data.getJSONObject(clip)
            val matrix = entry.getJSONArray("distances")
            val n = matrix.length()
            val distances = Array(n) { i ->
                val row = matrix.getJSONArray(i)
                DoubleArray(n) { j -> row.getDouble(j) }
            }
            val cannotLink = mutableSetOf<Pair<Int, Int>>()
            val pairs = entry.getJSONArray("cannotLink")
            for (k in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(k)
                cannotLink += pair.getInt(0) to pair.getInt(1)
            }
            assertEquals(clip, 5, clusterer().cluster(distances, cannotLink).toSet().size)
        }
    }

    @Test
    fun `co-occurring tracklets never share an identity`() {
        val data = fixture()
        for (clip in data.keys()) {
            val entry = data.getJSONObject(clip)
            val matrix = entry.getJSONArray("distances")
            val n = matrix.length()
            val distances = Array(n) { i ->
                val row = matrix.getJSONArray(i)
                DoubleArray(n) { j -> row.getDouble(j) }
            }
            val pairs = entry.getJSONArray("cannotLink")
            val cannotLink = mutableSetOf<Pair<Int, Int>>()
            for (k in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(k)
                cannotLink += pair.getInt(0) to pair.getInt(1)
            }

            val labels = clusterer().cluster(distances, cannotLink)
            for ((a, b) in cannotLink) {
                assertEquals(
                    "$clip: tracklets $a and $b share a frame but got the same identity",
                    false,
                    labels[a] == labels[b],
                )
            }
        }
    }

    @Test
    fun `the threshold sits in a stable range, not on a cliff`() {
        // The tuned value came from the midpoint of a wide plateau. If a change
        // narrows that plateau the thresholds stop generalising, so check the
        // count holds across it rather than only at the chosen point.
        val data = fixture()
        for (clip in data.keys()) {
            val entry = data.getJSONObject(clip)
            val matrix = entry.getJSONArray("distances")
            val n = matrix.length()
            val distances = Array(n) { i ->
                val row = matrix.getJSONArray(i)
                DoubleArray(n) { j -> row.getDouble(j) }
            }
            val pairs = entry.getJSONArray("cannotLink")
            val cannotLink = mutableSetOf<Pair<Int, Int>>()
            for (k in 0 until pairs.length()) {
                val pair = pairs.getJSONArray(k)
                cannotLink += pair.getInt(0) to pair.getInt(1)
            }

            for (core in listOf(0.30, 0.34, 0.38, 0.42, 0.46)) {
                val labels = AgglomerativeClusterer(core, config.assignThreshold, config.minCoreTracks)
                    .cluster(distances, cannotLink)
                assertEquals(
                    "$clip: count changed at core=$core, the plateau is too narrow",
                    5,
                    labels.toSet().size,
                )
            }
        }
    }
}
