package com.wetinknext.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces completed document edits into one disk publication.
 *
 * Callers only schedule after transactional changes (UP, clear, layer or
 * property operations), never for high-frequency MOVE events.
 */
class AutosaveCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private var pendingJob: Job? = null

    fun schedule(save: suspend () -> Unit) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMillis)
            save()
        }
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 700L
    }
}
