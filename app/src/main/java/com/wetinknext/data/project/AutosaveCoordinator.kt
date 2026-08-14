package com.wetinknext.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces completed document edits into one disk publication.
 *
 * Guarantees:
 * - a save that already started always runs to completion (NonCancellable);
 * - two saves never overlap (writeMutex);
 * - a continuously edited document is still flushed every maxWaitMillis.
 */
class AutosaveCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var pendingJob: Job? = null
    private val writeMutex = Mutex()
    private var firstPendingAtMillis: Long? = null

    fun schedule(save: suspend () -> Unit) {
        val now = nowMillis()
        val firstPendingAt = firstPendingAtMillis ?: now.also { firstPendingAtMillis = it }
        val waitedMillis = now - firstPendingAt
        val remainingBudget = (maxWaitMillis - waitedMillis).coerceAtLeast(0L)
        val effectiveDelay = minOf(debounceMillis, remainingBudget)

        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(effectiveDelay)
            writeMutex.withLock {
                // The disk write must survive a cancellation that arrives
                // while bytes are already being published.
                withContext(NonCancellable) { save() }
            }
            firstPendingAtMillis = null
        }
    }

    /** Cancels only a not-yet-started save. An in-flight write still completes. */
    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
        firstPendingAtMillis = null
    }

    /** Runs the pending save immediately and waits for it. Use on editor close. */
    suspend fun flushNow(save: suspend () -> Unit) {
        pendingJob?.cancel()
        pendingJob = null
        writeMutex.withLock {
            withContext(NonCancellable) { save() }
        }
        firstPendingAtMillis = null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 700L
        const val DEFAULT_MAX_WAIT_MILLIS = 5_000L
    }
}
