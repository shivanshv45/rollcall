package com.shivansh.rollcall.data.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.shivansh.rollcall.domain.model.PipelineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class VideoFrame(val timestampMs: Long, val bitmap: Bitmap)

class FrameExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: PipelineConfig,
) {

    fun durationMs(uri: Uri): Long = retriever(uri).use {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }

    /**
     * Frames at the configured cadence, scaled down.
     *
     * OPTION_CLOSEST rather than OPTION_CLOSEST_SYNC: sync-only seeking snaps
     * several requests onto the same keyframe, which would quietly collapse the
     * timeline the appearance counts are built from.
     *
     * Decoding at half resolution is the single biggest memory saving here, and
     * neither detection nor embedding gains anything from the extra pixels. The
     * one full-resolution decode happens later, for the chosen shot only.
     */
    fun frames(uri: Uri): Flow<VideoFrame> = flow {
        retriever(uri).use { mmr ->
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@flow
            val step = config.frameIntervalMs
            var t = 0L
            while (t < duration) {
                coroutineContext.ensureActive()
                val bitmap = mmr.getScaledFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    config.workWidth,
                    config.workHeight,
                )
                // Some codecs return null near boundaries; skipping that sample is
                // better than aborting a run that is otherwise fine.
                if (bitmap != null) emit(VideoFrame(t, bitmap))
                t += step
            }
        }
    }

    /** Full-resolution frame for the shot that ends up in the collage. */
    fun frameAt(uri: Uri, timestampMs: Long): Bitmap? = retriever(uri).use {
        it.getFrameAtTime(timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    private fun retriever(uri: Uri) = MediaMetadataRetriever().apply {
        setDataSource(context, uri)
    }
}
