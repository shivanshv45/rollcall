package com.shivansh.rollcall.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.shivansh.rollcall.CrashReporter
import com.shivansh.rollcall.data.detection.DetectedFace
import com.shivansh.rollcall.data.detection.ImageAnalysis
import com.shivansh.rollcall.data.detection.MlKitFaceDetector
import com.shivansh.rollcall.data.embedding.FaceEmbedder
import com.shivansh.rollcall.data.video.FrameExtractor
import com.shivansh.rollcall.domain.clustering.AgglomerativeClusterer
import com.shivansh.rollcall.domain.clustering.CosineDistance
import com.shivansh.rollcall.domain.clustering.TrackletBuilder
import com.shivansh.rollcall.domain.model.Failure
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.domain.model.Stage
import com.shivansh.rollcall.domain.model.VideoAnalysis
import com.shivansh.rollcall.domain.segmentation.AppearanceSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

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

        // Embeddings only. A crop per detected face reaches ~67MB over a 30s clip
        // and never gets read again after the previews are filled.
        val detected = mutableListOf<DetectedFace>()
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
                detected += face.copy(sample = face.sample.copy(embedding = embedding))
            }

            // Previews are decoration for the wait. Allocate only the few that
            // are actually shown, at thumbnail size, and never after that.
            if (previews.size < PREVIEW_LIMIT) {
                faces.firstOrNull()?.let { previews += previewOf(frame.bitmap, it) }
            }

            scanned++
            frame.bitmap.recycle()

            emit(
                ProcessingState.Working(
                    stage = Stage.FindingFaces,
                    progress = (scanned.toFloat() / expectedFrames * DETECT_SHARE)
                        .coerceIn(0f, DETECT_SHARE),
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
        trace("detected ${detected.size} faces over $scanned frames, ${cuts.size} cuts")

        val builder = TrackletBuilder(frameIntervalMs = config.frameIntervalMs)
        val tracklets = builder.build(detected.map { it.sample }, cuts)
        trace("built ${tracklets.size} tracklets")
        val labels = AgglomerativeClusterer(
            coreThreshold = config.coreThreshold,
            assignThreshold = config.assignThreshold,
            minCoreSize = config.minCoreTracks,
        ).cluster(
            CosineDistance.matrix(tracklets.map { it.embedding }),
            builder.cannotLink(tracklets),
        )
        trace("clustered into ${labels.toSet().size} identities")

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
            .filter { it.appearances.isNotEmpty() }

        if (people.isEmpty()) {
            emit(ProcessingState.Failed(Failure.NoFacesFound))
            return@flow
        }

        trace("${people.size} people, ${people.sumOf { it.appearanceCount }} appearances " +
            "in ${System.currentTimeMillis() - started}ms")

        emit(ProcessingState.Done(VideoAnalysis(people, duration)))
    }.catch { error ->
        // Cancellation is the user pressing Cancel, not a failure.
        if (error is CancellationException) throw error
        Log.e(TAG, "processing failed", error)
        emit(
            ProcessingState.Failed(
                when (error) {
                    is OutOfMemoryError -> Failure.Unexpected(
                        "Ran out of memory on this video. Try a shorter clip."
                    )
                    else -> Failure.Unexpected(
                        error.message ?: "Something went wrong while processing."
                    )
                }
            )
        )
    }.flowOn(Dispatchers.Default)

    private fun trace(message: String) {
        Log.i(TAG, message)
        CrashReporter.note(message)
    }

    /**
     * Small square crop for the progress strip.
     *
     * Composited into a new bitmap rather than sliced with
     * Bitmap.createBitmap(src, ...), which returns the source when the crop
     * covers it. The caller recycles the frame on the next line, so a slice
     * would leave the UI holding a recycled bitmap.
     */
    private fun previewOf(frame: Bitmap, face: DetectedFace): Bitmap {
        val box = face.sample.box
        val margin = (box.width * PREVIEW_MARGIN).toInt()
        val left = (box.left - margin).coerceIn(0, frame.width - 1)
        val top = (box.top - margin).coerceIn(0, frame.height - 1)
        val right = (box.right + margin).coerceIn(left + 1, frame.width)
        val bottom = (box.bottom + margin).coerceIn(top + 1, frame.height)

        val out = Bitmap.createBitmap(PREVIEW_PX, PREVIEW_PX, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            frame,
            Rect(left, top, right, bottom),
            Rect(0, 0, PREVIEW_PX, PREVIEW_PX),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private companion object {
        const val TAG = "RollCall"
        const val PREVIEW_LIMIT = 12
        const val PREVIEW_MARGIN = 0.35f

        /** Displayed at 56dp, so anything larger is wasted memory. */
        const val PREVIEW_PX = 160
        const val CLIPPED_PENALTY = 0.25f

        /** Detection is most of the work; the rest of the bar covers grouping. */
        const val DETECT_SHARE = 0.85f
        const val CHOOSING_SHARE = 0.95f
    }
}
