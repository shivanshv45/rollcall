package com.shivansh.rollcall.data.embedding

import android.content.Context
import android.graphics.Bitmap
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
        val aligned = FaceAligner.align(frame, face.sample.box, face.leftEye, face.rightEye)
        writeInput(aligned)
        aligned.recycle()
        interpreter.run(input, output)
        return output[0].copyOf().l2Normalized()
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
        const val SIZE = FaceAligner.SIZE
        const val CHANNELS = 3
        const val DIMENSIONS = 192
        const val MEAN = 127.5f
        const val STD = 128f
    }
}
