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

    /** Representative shot per person id, filled in once the results land. */
    private val _portraits = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val portraits: StateFlow<Map<Int, Bitmap>> = _portraits.asStateFlow()

    private var running: Job? = null
    private var source: Uri? = null

    fun process(uri: Uri) {
        running?.cancel()
        source = uri
        _collage.value = null
        releasePortraits()
        running = viewModelScope.launch {
            repository.analyse(uri).collect { next ->
                _state.value = next
                if (next is ProcessingState.Done) loadPortraits(uri, next)
            }
        }
    }

    /**
     * Faces for the roster. Best effort: the counts and the timeline are the
     * result, and they stand on their own if a decode fails.
     */
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

    /**
     * Cancels the run and returns to Idle rather than showing a Cancelled screen -
     * the user asked to stop, so taking them back is the expected outcome.
     */
    fun cancel() {
        running?.cancel()
        running = null
        _state.value = ProcessingState.Idle
    }

    /**
     * Renders on its own coroutine, so the pipeline's error handling does not
     * cover it. Decoding a full-resolution frame per person can fail on a low
     * memory device, and an escape here takes the whole app down.
     */
    fun buildCollage() {
        val done = _state.value as? ProcessingState.Done ?: return
        val uri = source ?: return
        viewModelScope.launch {
            _collage.value = try {
                collages.render(uri, done.result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "collage render failed", e)
                CrashReporter.note("collage render failed: ${e.javaClass.simpleName}")
                null
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
        _collage.value = null
        releasePortraits()
        _state.value = ProcessingState.Idle
    }

    /**
     * Drops the roster thumbnails without recycling them. A composition may
     * still be drawing one on the way out, and recycling underneath it crashes
     * on a released bitmap; these are small enough to leave to the collector.
     */
    private fun releasePortraits() {
        _portraits.value = emptyMap()
    }

    override fun onCleared() {
        super.onCleared()
        releasePortraits()
    }

    private companion object {
        const val TAG = "RollCall"
    }
}
