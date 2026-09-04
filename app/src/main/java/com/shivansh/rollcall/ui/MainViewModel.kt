package com.shivansh.rollcall.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shivansh.rollcall.CrashReporter
import com.shivansh.rollcall.data.ProcessingRepository
import android.content.Context
import android.content.Intent
import com.shivansh.rollcall.data.collage.CollageRenderer
import com.shivansh.rollcall.data.collage.CollageStore
import com.shivansh.rollcall.data.video.PortraitLoader
import com.shivansh.rollcall.domain.model.CollageBorder
import com.shivansh.rollcall.domain.model.ProcessingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ProcessingRepository,
    private val collages: CollageRenderer,
    private val store: CollageStore,
    private val portraitLoader: PortraitLoader,
) : ViewModel() {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val _collage = MutableStateFlow<Bitmap?>(null)
    val collage: StateFlow<Bitmap?> = _collage.asStateFlow()

    private val _showLabels = MutableStateFlow(true)
    val showLabels: StateFlow<Boolean> = _showLabels.asStateFlow()

    private val _border = MutableStateFlow(CollageBorder.DEFAULT)
    val border: StateFlow<CollageBorder> = _border.asStateFlow()

    /** One small preview per border, in [CollageBorder.ALL] order. */
    private val _borderThumbnails = MutableStateFlow<List<Bitmap>>(emptyList())
    val borderThumbnails: StateFlow<List<Bitmap>> = _borderThumbnails.asStateFlow()

    /** Representative shot per person id, filled in once the results land. */
    private val _portraits = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val portraits: StateFlow<Map<Int, Bitmap>> = _portraits.asStateFlow()

    private var running: Job? = null
    private var collageJob: Job? = null
    private var thumbnailJob: Job? = null

    /** The decoded portraits every border draws from. Released in [clearCollage]. */
    private var sheet: CollageRenderer.Sheet? = null
    private var source: Uri? = null

    fun process(uri: Uri) {
        running?.cancel()
        source = uri
        clearCollage()
        releasePortraits()
        running = viewModelScope.launch {
            repository.analyse(uri).collect { next ->
                _state.value = next
                if (next is ProcessingState.Done) loadPortraits(uri, next)
            }
        }
    }

    /** Best effort: the counts and timeline still stand if a decode fails. */
    private suspend fun loadPortraits(uri: Uri, done: ProcessingState.Done) {
        _portraits.value = try {
            portraitLoader.load(uri, done.result.people)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "loading portraits failed", e)
            emptyMap()
        }
    }

    /** Back to Idle rather than a Cancelled screen; the user asked to stop. */
    fun cancel() {
        running?.cancel()
        running = null
        _state.value = ProcessingState.Idle
    }

    /** The collage is a bitmap, so changing this means redrawing it. */
    fun setShowLabels(show: Boolean) {
        if (_showLabels.value == show) return
        _showLabels.value = show
        redraw()
        buildThumbnails()
    }

    /** Picking a border only repaints; the portraits it draws are already cut. */
    fun setBorder(border: CollageBorder) {
        if (_border.value == border) return
        _border.value = border
        redraw()
    }

    /**
     * Decodes the portraits, then draws the collage and the picker's thumbnails.
     *
     * Safe to call again for the same run - the second call reuses the portraits
     * rather than going back to the video.
     */
    fun buildCollage() {
        val done = _state.value as? ProcessingState.Done ?: return
        val uri = source ?: return
        if (sheet != null) {
            redraw()
            buildThumbnails()
            return
        }
        collageJob?.cancel()
        collageJob = viewModelScope.launch {
            try {
                sheet = collages.prepare(uri, done.result)
                drawInto(_collage)
                buildThumbnails()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "collage render failed", e)
                CrashReporter.note("collage render failed: ${e.javaClass.simpleName}")
                _collage.value = null
            }
        }
    }

    /**
     * Repaints the preview in the current style.
     *
     * Runs on its own coroutine, so the pipeline's error handling does not cover
     * it and an escaped throw would take the app down.
     */
    private fun redraw() {
        if (sheet == null) return
        collageJob?.cancel()
        collageJob = viewModelScope.launch {
            try {
                drawInto(_collage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "collage redraw failed", e)
            }
        }
    }

    private suspend fun drawInto(target: MutableStateFlow<Bitmap?>) {
        val ready = sheet ?: return
        val next = collages.render(ready, _border.value, _showLabels.value)
        // The outgoing bitmap may still be on screen for a frame, so it is left
        // to the GC rather than recycled out from under the composition.
        target.value = next
    }

    /**
     * Renders the strip's previews, one per style.
     *
     * They are small and drawn from portraits already in memory, so the whole
     * strip costs a fraction of one full-size render.
     */
    private fun buildThumbnails() {
        val ready = sheet ?: return
        thumbnailJob?.cancel()
        thumbnailJob = viewModelScope.launch {
            try {
                _borderThumbnails.value = CollageBorder.ALL.map { style ->
                    collages.render(
                        sheet = ready,
                        border = style,
                        showLabels = _showLabels.value,
                        width = CollageRenderer.THUMB_WIDTH,
                        height = CollageRenderer.THUMB_HEIGHT,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The picker falls back to plain swatches; the collage itself
                // is unaffected.
                Log.e(TAG, "border thumbnails failed", e)
                _borderThumbnails.value = emptyList()
            }
        }
    }

    fun save(onResult: (Uri?) -> Unit) {
        val bitmap = _collage.value ?: return onResult(null)
        viewModelScope.launch {
            val saved = try {
                store.saveToGallery(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "saving the collage failed", e)
                null
            }
            onResult(saved)
        }
    }

    fun share(context: Context) {
        val bitmap = _collage.value ?: return
        viewModelScope.launch {
            try {
                val intent = store.shareIntent(bitmap)
                context.startActivity(Intent.createChooser(intent, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // No share target, or the file could not be staged. Neither is
                // worth taking the app down for.
                Log.e(TAG, "sharing the collage failed", e)
            }
        }
    }

    fun reset() {
        running?.cancel()
        running = null
        source = null
        clearCollage()
        releasePortraits()
        _state.value = ProcessingState.Idle
    }

    /**
     * Drops everything the collage screen built, portraits included.
     *
     * The sheet holds a full-size bitmap per person, so it is recycled here
     * rather than left to the GC. The rendered collages are not: one may still
     * be drawing on the way out, and they are replaced whole on the next build.
     */
    private fun clearCollage() {
        collageJob?.cancel()
        collageJob = null
        thumbnailJob?.cancel()
        thumbnailJob = null
        sheet?.close()
        sheet = null
        _collage.value = null
        _borderThumbnails.value = emptyList()
        _border.value = CollageBorder.DEFAULT
    }

    /**
     * Drops the thumbnails without recycling them. A composition may still be
     * drawing one on the way out, and they are small enough to leave to the GC.
     */
    private fun releasePortraits() {
        _portraits.value = emptyMap()
    }

    override fun onCleared() {
        super.onCleared()
        sheet?.close()
        sheet = null
        releasePortraits()
    }

    private companion object {
        const val TAG = "RollCall"
    }
}
