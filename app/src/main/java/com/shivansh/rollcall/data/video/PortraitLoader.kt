package com.shivansh.rollcall.data.video

import android.graphics.Bitmap
import android.net.Uri
import com.shivansh.rollcall.domain.model.Person
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
    private val picker: PortraitPicker,
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

    private suspend fun portrait(uri: Uri, person: Person): Bitmap? {
        val cropped = picker.pick(uri, person, DECODE_PX, DECODE_PX, 1f) ?: return null
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
