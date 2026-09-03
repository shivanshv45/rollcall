package com.shivansh.rollcall.data.video

import android.graphics.Bitmap
import android.net.Uri
import com.shivansh.rollcall.data.detection.MlKitFaceDetector
import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceQuality
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import javax.inject.Inject
import kotlin.math.hypot

/**
 * Chooses and cuts the one portrait a person is shown with.
 *
 * Scoring alone was not enough to keep a second face out of the tile: the
 * working-resolution pass can miss a neighbour, and then nothing limits the
 * crop. So the frame is re-detected at output resolution and the crop is
 * checked. A candidate whose crop holds anyone else is passed over for the
 * next best, and only if every candidate fails does the best one stand.
 */
class PortraitPicker @Inject constructor(
    private val frames: FrameExtractor,
    private val detector: MlKitFaceDetector,
    private val config: PipelineConfig,
) {

    suspend fun pick(
        uri: Uri,
        person: Person,
        maxWidth: Int,
        maxHeight: Int,
        aspect: Float,
    ): Bitmap? {
        // Anyone seen alone should be shown alone, so solo frames lead even when
        // a shared frame is sharper. That only holds while a solo frame is worth
        // looking at: if every one is a smear, a clean shared frame cropped to
        // this person makes the better tile, so the two lists compete on score.
        val samples = person.tracklets.flatMap { it.samples }
        val (solo, shared) = samples.partition { it.coFaces.isEmpty() }
        val byScore = compareByDescending<FaceSample> { FaceQuality.portraitScore(it) }

        val candidates = if (solo.any { FaceQuality.isUsablePortrait(it) }) {
            solo.sortedWith(byScore).take(CANDIDATES) + shared.sortedWith(byScore).take(CANDIDATES)
        } else {
            samples.sortedWith(byScore).take(CANDIDATES * 2)
        }

        var fallback: Bitmap? = null
        for (sample in candidates) {
            val frame = frames.frameAt(uri, sample.timestampMs, maxWidth, maxHeight) ?: continue
            val scale = frame.width.toFloat() / config.workWidth
            val expected = sample.box.scaled(scale)

            // Boxes in this frame at this resolution. The subject is whichever
            // one sits where the pipeline said the face was.
            val boxes = detector.boxesIn(frame)
            val subject = boxes.minByOrNull { it.distanceTo(expected) }
                ?.takeIf { it.distanceTo(expected) < expected.width }
                ?: expected
            val others = boxes.filter { it != subject }

            val rect = PortraitCropper.rectFor(
                frameWidth = frame.width,
                frameHeight = frame.height,
                boxCenterX = subject.centerX,
                boxCenterY = subject.centerY,
                boxWidth = subject.width.toFloat(),
                boxHeight = subject.height.toFloat(),
                faceScale = 1f,
                cropScale = config.portraitCropScale,
                aspect = aspect,
                neighbours = others.map { NeighbourBox(it.centerX, it.width / 2f) },
            )
            val crop = PortraitCropper.crop(frame, rect)
            frame.recycle()

            val alone = others.none { rect.contains(it.centerX.toInt(), it.centerY.toInt()) }
            if (alone) {
                fallback?.recycle()
                return crop
            }
            if (fallback == null) fallback = crop else crop.recycle()
        }
        return fallback
    }

    private fun BoundingBox.scaled(s: Float) = BoundingBox(
        (left * s).toInt(), (top * s).toInt(), (width * s).toInt(), (height * s).toInt(),
    )

    private fun BoundingBox.distanceTo(other: BoundingBox) =
        hypot(centerX - other.centerX, centerY - other.centerY)

    private companion object {
        /** Frames tried from each of the solo and shared lists. */
        const val CANDIDATES = 4
    }
}
