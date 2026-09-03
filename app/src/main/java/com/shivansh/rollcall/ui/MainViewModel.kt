package com.shivansh.rollcall.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shivansh.rollcall.data.ProcessingRepository
import android.content.Context
import android.content.Intent
import com.shivansh.rollcall.data.collage.CollageRenderer
import com.shivansh.rollcall.data.collage.CollageStore
import com.shivansh.rollcall.domain.model.ProcessingState
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val _collage = MutableStateFlow<Bitmap?>(null)
    val collage: StateFlow<Bitmap?> = _collage.asStateFlow()

    private var running: Job? = null
    private var source: Uri? = null

    fun process(uri: Uri) {
        running?.cancel()
        source = uri
        _collage.value = null
        running = viewModelScope.launch {
            repository.analyse(uri).collect { _state.value = it }
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

    fun buildCollage() {
        val done = _state.value as? ProcessingState.Done ?: return
        val uri = source ?: return
        viewModelScope.launch {
            _collage.value = collages.render(uri, done.result)
        }
    }

    fun save(onResult: (Uri?) -> Unit) {
        val bitmap = _collage.value ?: return onResult(null)
        viewModelScope.launch { onResult(store.saveToGallery(bitmap)) }
    }

    fun share(context: Context) {
        val bitmap = _collage.value ?: return
        viewModelScope.launch {
            val intent = store.shareIntent(bitmap)
            context.startActivity(Intent.createChooser(intent, null))
        }
    }

    fun reset() {
        running?.cancel()
        running = null
        source = null
        _collage.value = null
        _state.value = ProcessingState.Idle
    }
}
