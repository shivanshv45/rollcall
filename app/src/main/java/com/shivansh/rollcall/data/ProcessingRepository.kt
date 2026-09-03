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
import com.shivansh.rollcall.data.detection.RecallTally
import com.shivansh.rollcall.data.embedding.FaceEmbedder
import com.shivansh.rollcall.data.video.FrameExtractor
import com.shivansh.rollcall.domain.clustering.AgglomerativeClusterer
import com.shivansh.rollcall.domain.clustering.CosineDistance
import com.shivansh.rollcall.domain.clustering.TrackletBuilder
import com.shivansh.rollcall.domain.model.FaceQuality
import com.shivansh.rollcall.domain.model.Failure
import com.shivansh.rollcall.domain.model.PersonEvidence
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import com.shivansh.rollcall.domain.model.ProcessingState
import com.shivansh.rollcall.domain.model.RunReport
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
 * Emits states rather than returning a result at the end, so the UI can show
 * what has been found so far. Cancelling the collector cancels the work.
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

        // Embeddings only. Keeping a crop per face reaches ~67MB over a 30s clip
        // and nothing reads them once the previews are filled.
        val detected = mutableListOf<DetectedFace>()
        val cuts = mutableListOf<Long>()
        val previews = mutableListOf<Bitmap>()
        var previousThumb: IntArray? = null
        var scanned = 0
        val tally = RecallTally()

        frames.frames(uri).collect { frame ->
            val thumb = ImageAnalysis.thumbnail(frame.bitmap)
            previousThumb?.let {
                if (ImageAnalysis.frameDifference(it, thumb) > config.sceneCutThreshold) {
                    cuts += frame.timestampMs
                }
            }
            previousThumb = thumb

            // Every face in the frame, not just the first. Two people on screen
            // together is where the appearance counts are decided, and it also
            // tells the clusterer they cannot be the same person.
            val faces = detector.detect(frame.bitmap, frame.timestampMs, tally)
            for (face in faces) {
                val embedding = embedder.embed(frame.bitmap, face)
                if (embedding == null) {
                    tally.countEmbeddingDrop()
                    continue
                }
                detected += face.copy(sample = face.sample.copy(embedding = embedding))
            }

            // Previews are decoration for the wait, so only allocate the few
            // actually shown.
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
        trace(tally.summary())

        val builder = TrackletBuilder(frameIntervalMs = config.frameIntervalMs)
        val tracklets = builder.build(detected.map { it.sample }, cuts)
        trace("built ${tracklets.size} tracklets")
        val cannotLink = builder.cannotLink(tracklets)
        val labels = AgglomerativeClusterer(
            coreThreshold = config.coreThreshold,
            assignThreshold = config.assignThreshold,
            minCoreSize = config.minCoreTracks,
        ).cluster(CosineDistance.matrix(tracklets.map { it.embedding }), cannotLink)
        trace("clustered into ${labels.toSet().size} identities")

        emit(ProcessingState.Working(Stage.ChoosingShots, CHOOSING_SHARE, scanned, detected.size, previews.toList()))

        val segmenter = AppearanceSegmenter(
            maxGapMs = (config.maxGapSeconds * 1000).toLong(),
            frameIntervalMs = config.frameIntervalMs,
        )
        val groups = tracklets.withIndex()
            .groupBy({ labels[it.index] }, { it.value })
            .values
        val (real, junk) = groups.partition { PersonEvidence.isEnough(it, config.minCoreTracks) }
        if (junk.isNotEmpty()) {
            trace("dropped ${junk.size} leftovers with too few samples to be a person")
        }

        val people = real
            .map { group ->
                val times = group.flatMap { t -> t.samples.map { it.timestampMs } }
                val best = group.map { it.best }.maxBy { FaceQuality.portraitScore(it) }
                Person(
                    id = 0,
                    appearances = segmenter.segment(times, cuts),
                    representative = best,
                    tracklets = group,
                )
            }
            .sortedBy { it.appearances.firstOrNull()?.startMs ?: Long.MAX_VALUE }
            // Drop before numbering: the id is both the label and an index into
            // the list, so numbering first leaves gaps.
            .filter { it.appearances.isNotEmpty() }
            .mapIndexed { index, person -> person.copy(id = index) }

        if (people.isEmpty()) {
            emit(ProcessingState.Failed(Failure.NoFacesFound))
            return@flow
        }

        val elapsed = System.currentTimeMillis() - started
        trace("${people.size} people, ${people.sumOf { it.appearanceCount }} appearances in ${elapsed}ms")

        val report = RunReport.build(
            recall = tally.summary(),
            tracklets = tracklets,
            labels = labels,
            people = people,
            cannotLink = cannotLink,
            durationMs = duration,
            elapsedMs = elapsed,
        )
        emit(ProcessingState.Done(VideoAnalysis(people, duration), report))
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
     * Composited rather than sliced: createBitmap returns the source when the
     * crop covers it, and the frame is recycled on the next line.
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


        /** Detection is most of the work; the rest of the bar covers grouping. */
        const val DETECT_SHARE = 0.85f
        const val CHOOSING_SHARE = 0.95f
    }
}
