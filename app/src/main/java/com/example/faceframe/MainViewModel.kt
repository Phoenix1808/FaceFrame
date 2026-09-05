package com.example.faceframe

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.faceframe.model.ProcessingState
import com.example.faceframe.processing.VideoProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private var job: Job? = null

    fun analyze(uri: Uri) {
        // Cancel any run already going. Two pipelines at once would double the
        // memory and make the progress bar jump around.
        job?.cancel()

        job = viewModelScope.launch {
            VideoProcessor(getApplication())
                .process(uri)
                .catch { t ->
                    Log.e(TAG, "processing failed", t)
                    // First frame that is ours. This goes on screen, because
                    // digging an exception out of logcat on a busy device is
                    // its own small nightmare.
                    val where = t.stackTrace
                        .firstOrNull { it.className.startsWith("com.example.faceframe") }
                        ?.let { "${it.fileName}:${it.lineNumber}" }
                        ?: "unknown"
                    _state.value = ProcessingState.Failed(
                        message = "${t::class.java.simpleName}: ${t.message ?: "no message"}",
                        where = where
                    )
                }
                .collect { _state.value = it }
        }
    }

    private companion object {
        const val TAG = "FaceFrame"
    }
}
