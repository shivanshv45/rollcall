package com.shivansh.rollcall.data.video

import android.graphics.Bitmap
import android.net.Uri
import com.shivansh.rollcall.domain.model.Person
import com.shivansh.rollcall.domain.model.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Decodes each person's representative shot at roster size.
 *
 * Done once when the results land rather than per recomposition. The roster is
 * where the grouping gets judged, so it needs faces, not just letters.
 */
class PortraitLoader @Inject constructor(
    private val frames: FrameExtractor,
    private val config: PipelineConfig,
) {

    suspend fun load(uri: Uri, people: List<Person>): Map<Int, Bitmap> =
        withContext(Dispatchers.Default) {
            val out = LinkedHashMap<Int, Bitmap>(people.size)
            for (person in people) {
                coroutineContext.ensureActive()
                // One bad decode shouldn't cost the whole roster its pictures.
                // That row falls back to the plain chip.
                runCatching { portrait(uri, person) }
                    .getOrNull()
                    ?.let { out[person.id] = it }
            }
            out
        }

    private fun portrait(uri: Uri, person: Person): Bitmap? {
        val sample = person.representative
        val frame = frames.frameAt(uri, sample.timestampMs, DECODE_PX, DECODE_PX) ?: return null

        val box = sample.box
        val cropped = PortraitCropper.crop(
            frame = frame,
            boxCenterX = box.centerX,
            boxCenterY = box.centerY,
            boxWidth = box.width.toFloat(),
            boxHeight = box.height.toFloat(),
            faceScale = frame.width.toFloat() / config.workWidth,
            cropScale = config.portraitCropScale,
            aspect = 1f,
            neighbours = sample.neighbourBoxes(),
        )
        frame.recycle()

        if (cropped.width <= THUMBNAIL_PX) return cropped
        // Down to display size: five of these cost well under a megabyte.
        return Bitmap.createScaledBitmap(cropped, THUMBNAIL_PX, THUMBNAIL_PX, true)
            .also { if (it !== cropped) cropped.recycle() }
    }

    private companion object {
        /** Enough detail to crop from without decoding the source at full size. */
        const val DECODE_PX = 720

        /** Roughly the row height at 3x density. */
        const val THUMBNAIL_PX = 192
    }
}
