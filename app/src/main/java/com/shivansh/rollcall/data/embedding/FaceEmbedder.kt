package com.shivansh.rollcall.data.embedding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.shivansh.rollcall.data.detection.DetectedFace
import com.shivansh.rollcall.domain.model.l2Normalized
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns a face into a 192-dimensional vector using MobileFaceNet.
 *
 * Model input is [1,112,112,3] float32 and output [1,192] float32, both verified
 * against the .tflite rather than taken from documentation.
 */
@Singleton
class FaceEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoCloseable {

    private val interpreter by lazy {
        Interpreter(loadModel(), Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
        })
    }

    private val input = ByteBuffer
        .allocateDirect(SIZE * SIZE * CHANNELS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(DIMENSIONS) }

    // Reused across faces; a per-call array is 50KB of churn each time.
    private val pixels = IntArray(SIZE * SIZE)

    fun embed(frame: Bitmap, face: DetectedFace): FloatArray? {
        val aligned = align(frame, face) ?: return null
        writeInput(aligned)
        aligned.recycle()
        interpreter.run(input, output)
        return output[0].copyOf().l2Normalized()
    }

    /**
     * Rotates the face so the eye line is level and scales it so the eyes sit at
     * a fixed spot in the crop.
     *
     * Removing in-plane rotation and normalising scale is the cheapest accuracy
     * win available - it is why the detector is asked for landmarks at all. When
     * the eyes are missing there is nothing to align to, so the box is used
     * directly with a generous margin.
     */
    private fun align(frame: Bitmap, face: DetectedFace): Bitmap? {
        val left = face.leftEye
        val right = face.rightEye
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        if (left != null && right != null) {
            val eyeDistance = hypot(right.first - left.first, right.second - left.second)
            if (eyeDistance < MIN_EYE_DISTANCE_PX) return null

            val centerX = (left.first + right.first) / 2f
            val centerY = (left.second + right.second) / 2f
            val angle = Math.toDegrees(
                atan2(right.second - left.second, right.first - left.first).toDouble()
            ).toFloat()
            val scale = SIZE * EYE_SPAN / eyeDistance

            val matrix = Matrix().apply {
                postTranslate(-centerX, -centerY)
                postRotate(-angle)
                postScale(scale, scale)
                postTranslate(SIZE / 2f, SIZE * EYE_HEIGHT)
            }
            canvas.drawBitmap(frame, matrix, paint)
        } else {
            // Straight from the source rect: an intermediate createBitmap crop
            // can alias the frame, which the caller still owns.
            val box = face.sample.box
            val margin = (box.width * FALLBACK_MARGIN).toInt()
            val srcLeft = (box.left - margin).coerceIn(0, frame.width - 1)
            val srcTop = (box.top - margin).coerceIn(0, frame.height - 1)
            val srcRight = (box.right + margin).coerceIn(srcLeft + 1, frame.width)
            val srcBottom = (box.bottom + margin).coerceIn(srcTop + 1, frame.height)
            canvas.drawBitmap(
                frame,
                Rect(srcLeft, srcTop, srcRight, srcBottom),
                Rect(0, 0, SIZE, SIZE),
                paint,
            )
        }
        return out
    }

    private fun writeInput(bitmap: Bitmap) {
        input.rewind()
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in pixels) {
            input.putFloat(((p shr 16 and 0xFF) - MEAN) / STD)
            input.putFloat(((p shr 8 and 0xFF) - MEAN) / STD)
            input.putFloat(((p and 0xFF) - MEAN) / STD)
        }
        input.rewind()
    }

    private fun loadModel(): MappedByteBuffer =
        context.assets.openFd(MODEL_ASSET).use { fd ->
            fd.createInputStream().channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength,
            )
        }

    override fun close() = interpreter.close()

    private companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"
        const val SIZE = 112
        const val CHANNELS = 3
        const val DIMENSIONS = 192
        const val MEAN = 127.5f
        const val STD = 128f

        /** Eye separation as a fraction of the crop width. */
        const val EYE_SPAN = 0.42f

        /** Where the eye line sits vertically, leaving room for chin and hair. */
        const val EYE_HEIGHT = 0.42f

        const val MIN_EYE_DISTANCE_PX = 2f
        const val FALLBACK_MARGIN = 0.25f
    }
}
