package com.shivansh.rollcall.data.detection

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.shivansh.rollcall.domain.model.BoundingBox
import com.shivansh.rollcall.domain.model.FaceQuality
import com.shivansh.rollcall.domain.model.FaceSample
import com.shivansh.rollcall.domain.model.PipelineConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class MlKitFaceDetector @Inject constructor(
    private val config: PipelineConfig,
) : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // Offline batch work, not a camera preview, so spend on accuracy.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(config.minFaceRatio)
            // Tracking IDs are a continuity hint only. They are motion-based, not
            // recognition, and do not survive a whip-pan.
            .enableTracking()
            .build()
    )

    suspend fun detect(
        bitmap: Bitmap,
        timestampMs: Long,
        tally: RecallTally? = null,
    ): List<DetectedFace> {
        val faces = suspendCancellableCoroutine { cont ->
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        faces.forEach { tally?.countReturned(it.boundingBox.width().toFloat() / bitmap.width) }
        val detected = faces.mapNotNull { it.toDetected(bitmap, timestampMs, tally) }

        // Record who else was on screen. Two faces in one frame are different
        // people, and a portrait crop has to stop short of the neighbour.
        if (detected.size < 2) return detected
        val boxes = detected.map { it.sample.box }
        return detected.mapIndexed { i, face ->
            face.copy(
                sample = face.sample.copy(
                    coFaces = boxes.filterIndexed { j, _ -> j != i },
                )
            )
        }
    }

    private fun Face.toDetected(
        bitmap: Bitmap,
        timestampMs: Long,
        tally: RecallTally?,
    ): DetectedFace? {
        val box = BoundingBox(
            left = boundingBox.left,
            top = boundingBox.top,
            width = boundingBox.width(),
            height = boundingBox.height(),
        )
        if (box.width <= 0 || box.height <= 0) {
            tally?.countDegenerateBox()
            return null
        }

        val sharpness = ImageAnalysis.laplacianVariance(bitmap, box)
        if (sharpness < config.blurFloor) {
            tally?.countBlurDrop()
            return null
        }

        val frameArea = bitmap.width.toFloat() * bitmap.height
        val margin = config.edgeMarginPx
        val sample = FaceSample(
            timestampMs = timestampMs,
            box = box,
            sharpness = (sharpness / SHARPNESS_CEILING).toFloat().coerceIn(0f, 1f),
            frontality = FaceQuality.frontality(headEulerAngleY, headEulerAngleX),
            eyesOpen = minOf(
                leftEyeOpenProbability ?: DEFAULT_PROBABILITY,
                rightEyeOpenProbability ?: DEFAULT_PROBABILITY,
            ),
            sizeRatio = (box.area / frameArea / TYPICAL_FACE_FRACTION).coerceIn(0f, 1f),
            expression = (smilingProbability ?: DEFAULT_PROBABILITY).coerceIn(0f, 1f),
            isClipped = box.left <= margin || box.top <= margin ||
                box.right >= bitmap.width - margin || box.bottom >= bitmap.height - margin,
            trackId = trackingId ?: -1,
        )
        return DetectedFace(sample, leftEye = eyePosition(true), rightEye = eyePosition(false))
    }

    private fun Face.eyePosition(left: Boolean): Pair<Float, Float>? {
        val type = if (left) {
            com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE
        } else {
            com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE
        }
        return getLandmark(type)?.position?.let { it.x to it.y }
    }

    override fun close() = detector.close()

    private companion object {
        /** Laplacian variance at which a face counts as fully sharp. */
        const val SHARPNESS_CEILING = 60.0

        /** Face area as a fraction of the frame that counts as a full-size face. */
        const val TYPICAL_FACE_FRACTION = 0.15f

        /** Used when ML Kit cannot classify; neutral rather than optimistic. */
        const val DEFAULT_PROBABILITY = 0.5f
    }
}

data class DetectedFace(
    val sample: FaceSample,
    val leftEye: Pair<Float, Float>?,
    val rightEye: Pair<Float, Float>?,
)
