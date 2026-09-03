package com.shivansh.rollcall.data

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.shivansh.rollcall.data.detection.DetectedFace
import com.shivansh.rollcall.data.detection.ImageAnalysis
import com.shivansh.rollcall.data.detection.MlKitFaceDetector
import com.shivansh.rollcall.data.embedding.FaceEmbedder
import com.shivansh.rollcall.data.video.FrameExtractor
import com.shivansh.rollcall.domain.clustering.AgglomerativeClusterer
import com.shivansh.rollcall.domain.clustering.CosineDistance
import com.shivansh.rollcall.domain.model.Failure
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.domain.model.Stage
import com.shivansh.rollcall.domain.model.Tracklet
import com.shivansh.rollcall.domain.model.VideoAnalysis
import com.shivansh.rollcall.domain.segmentation.AppearanceSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.hypot

/**
 * Runs the whole pipeline and reports progress as it goes.
 *
 * Emits a stream of states rather than returning a result at the end, so the UI
 * can show which stage is running and what has been found so far. Everything
 * happens on Dispatchers.Default; cancelling the collector cancels the work.
 */
class ProcessingRepository @Inject constructor(
    private val frames: FrameExtractor,
    private val detector: MlKitFaceDetector,
    private val embedder: FaceEmbedder,
    private val config: PipelineConfig,
) {

    fun analyse(uri: Uri): Flow<ProcessingState> = flow {
        val started = System.currentTimeMillis()
        val duration = frames.durationMs(uri)
        if (duration <= 0L) {
            emit(ProcessingState.Failed(Failure.UnreadableVideo))
            return@flow
        }

        val expectedFrames = (duration / config.frameIntervalMs).toInt().coerceAtLeast(1)
        val detected = mutableListOf<Pair<DetectedFace, Bitmap>>()
        val cuts = mutableListOf<Long>()
        val previews = mutableListOf<Bitmap>()
        var previousThumb: IntArray? = null
        var scanned = 0

        frames.frames(uri).collect { frame ->
            val thumb = ImageAnalysis.thumbnail(frame.bitmap)
            previousThumb?.let {
                if (ImageAnalysis.frameDifference(it, thumb) > config.sceneCutThreshold) {
                    cuts += frame.timestampMs
                }
            }
            previousThumb = thumb

            val faces = detector.detect(frame.bitmap, frame.timestampMs)
            for (face in faces) {
                val embedding = embedder.embed(frame.bitmap, face) ?: continue
                detected += face.copy(sample = face.sample.copy(embedding = embedding)) to
                    cropPreview(frame.bitmap, face)
            }

            scanned++
            if (previews.size < PREVIEW_LIMIT && faces.isNotEmpty()) {
                previews += detected.last().second
            }
            frame.bitmap.recycle()

            emit(
                ProcessingState.Working(
                    stage = if (scanned < expectedFrames) Stage.FindingFaces else Stage.GroupingPeople,
                    progress = (scanned.toFloat() / expectedFrames).coerceIn(0f, DETECT_SHARE),
                    framesScanned = scanned,
                    facesFound = detected.size,
                    previews = previews.toList(),
                )
            )
        }

        if (detected.isEmpty()) {
            emit(ProcessingState.Failed(Failure.NoFacesFound))
            return@flow
        }

        emit(ProcessingState.Working(Stage.GroupingPeople, DETECT_SHARE, scanned, detected.size, previews.toList()))

        val samples = detected.map { it.first.sample }
        val tracklets = buildTracklets(samples, cuts)
        val labels = AgglomerativeClusterer(
            coreThreshold = config.coreThreshold,
            assignThreshold = config.assignThreshold,
            minCoreSize = config.minCoreTracks,
        ).cluster(
            CosineDistance.matrix(tracklets.map { it.embedding }),
            cannotLink(tracklets),
        )

        emit(ProcessingState.Working(Stage.ChoosingShots, CHOOSING_SHARE, scanned, detected.size, previews.toList()))

        val segmenter = AppearanceSegmenter(
            maxGapMs = (config.maxGapSeconds * 1000).toLong(),
            frameIntervalMs = config.frameIntervalMs,
        )
        val people = tracklets.withIndex()
            .groupBy({ labels[it.index] }, { it.value })
            .entries
            .map { (_, group) ->
                val times = group.flatMap { t -> t.samples.map { it.timestampMs } }
                val best = group.map { it.best }
                    .maxBy { it.quality - if (it.isClipped) CLIPPED_PENALTY else 0f }
                Person(
                    id = 0,
                    appearances = segmenter.segment(times, cuts),
                    representative = best,
                    tracklets = group,
                )
            }
            .sortedBy { it.appearances.firstOrNull()?.startMs ?: Long.MAX_VALUE }
            .mapIndexed { index, person -> person.copy(id = index) }

        Log.i(TAG, "${people.size} people, ${people.sumOf { it.appearanceCount }} appearances " +
            "from $scanned frames in ${System.currentTimeMillis() - started}ms")

        emit(ProcessingState.Done(VideoAnalysis(people, duration)))
    }.flowOn(Dispatchers.Default)

    /**
     * Chains faces across consecutive frames into runs, then averages each run
     * into one embedding.
     *
     * Clustering a few dozen tracklets is far more reliable than clustering
     * hundreds of individual frames: averaging cancels per-frame noise, and
     * weighting by quality lets the clean frames dominate.
     *
     * ML Kit's tracking id is used as a hint where it is stable, with position
     * and appearance as the fallback, because the id does not survive a cut.
     */
    private fun buildTracklets(samples: List<FaceSample>, cuts: List<Long>): List<Tracklet> {
        val byTime = samples.groupBy { it.timestampMs }.toSortedMap()
        val open = mutableListOf<MutableList<FaceSample>>()
        val closed = mutableListOf<List<FaceSample>>()
        val step = config.frameIntervalMs

        for ((time, atTime) in byTime) {
            val isCut = time in cuts
            val unclaimed = atTime.toMutableList()
            val carried = mutableListOf<MutableList<FaceSample>>()

            if (!isCut) {
                for (track in open) {
                    val last = track.last()
                    if (time - last.timestampMs > step * MAX_TRACK_SKIP) continue
                    val match = unclaimed
                        .filter { it.trackId == last.trackId && it.trackId >= 0 }
                        .minByOrNull { boxGap(last, it) }
                        ?: unclaimed
                            .filter { boxGap(last, it) < MAX_BOX_GAP && appearanceGap(last, it) < MAX_APPEARANCE_GAP }
                            .minByOrNull { boxGap(last, it) }
                    if (match != null) {
                        track += match
                        unclaimed -= match
                        carried += track
                    }
                }
            }

            closed += open.filter { it !in carried }
            open.clear()
            open += carried
            open += unclaimed.map { mutableListOf(it) }
        }
        closed += open

        return closed.filter { it.isNotEmpty() }
            .mapIndexed { index, run -> Tracklet(index, run) }
    }

    /** Two faces in the same frame are different people, whatever the vectors say. */
    private fun cannotLink(tracklets: List<Tracklet>): Set<Pair<Int, Int>> {
        val times = tracklets.map { t -> t.samples.map { it.timestampMs }.toHashSet() }
        val out = mutableSetOf<Pair<Int, Int>>()
        for (i in tracklets.indices) {
            for (j in i + 1 until tracklets.size) {
                if (times[i].any { it in times[j] }) out += i to j
            }
        }
        return out
    }

    private fun boxGap(a: FaceSample, b: FaceSample): Float {
        val span = ((a.box.width + b.box.width) / 2f).coerceAtLeast(1f)
        return hypot(a.box.centerX - b.box.centerX, a.box.centerY - b.box.centerY) / span
    }

    private fun appearanceGap(a: FaceSample, b: FaceSample): Double {
        val x = a.embedding ?: return Double.MAX_VALUE
        val y = b.embedding ?: return Double.MAX_VALUE
        return CosineDistance.between(x, y)
    }

    private fun cropPreview(frame: Bitmap, face: DetectedFace): Bitmap {
        val box = face.sample.box
        val margin = (box.width * PREVIEW_MARGIN).toInt()
        val x = (box.left - margin).coerceAtLeast(0)
        val y = (box.top - margin).coerceAtLeast(0)
        val w = (box.width + margin * 2).coerceAtMost(frame.width - x)
        val h = (box.height + margin * 2).coerceAtMost(frame.height - y)
        return Bitmap.createBitmap(frame, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    private companion object {
        const val TAG = "RollCall"
        const val PREVIEW_LIMIT = 12
        const val PREVIEW_MARGIN = 0.35f
        const val CLIPPED_PENALTY = 0.25f

        /** Detection is most of the work; the rest of the bar covers grouping. */
        const val DETECT_SHARE = 0.85f
        const val CHOOSING_SHARE = 0.95f

        /** How many sampled frames a face may vanish for and still be the same run. */
        const val MAX_TRACK_SKIP = 2

        const val MAX_BOX_GAP = 0.5f
        const val MAX_APPEARANCE_GAP = 0.45
    }
}
