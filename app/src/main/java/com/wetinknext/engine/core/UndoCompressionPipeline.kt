package com.wetinknext.engine.core

import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.undo.DeflateTileCompressor
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileCompressor
import com.wetinknext.engine.undo.UndoEntry
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType
import java.util.HashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Orders tile-compression work without ever touching GL from a worker thread.
 *
 * Call [enqueue], [process], [invalidate], and [shutdown] on the GL thread.
 * The worker only converts [RawTileSnapshot] objects into compressed [UndoEntry]
 * instances and requests another frame once a result is ready.
 */
class UndoCompressionPipeline(
    private val requestRender: () -> Unit,
    private val compressor: TileCompressor = DeflateTileCompressor(),
    private val maxPendingJobs: Int = DEFAULT_MAX_PENDING_JOBS,
    private val executorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() },
) {
    private var executor = executorFactory()
    private val pendingResults = ConcurrentLinkedQueue<UndoJobResult>()
    private val completedResults = HashMap<Long, UndoJobResult>()

    private var nextSequence = 0L
    private var nextSequenceToApply = 0L
    private var epoch = 0L

    var pendingCount: Int = 0
        private set
    var compressionFailureCount: Int = 0
        private set
    var staleResultCount: Int = 0
        private set

    /**
     * Schedules one ordered undo transaction. The synchronous fallback still
     * travels through the same result queue, so it cannot overtake older work.
     */
    fun enqueue(
        undoManager: UndoManager,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        tag: String = "stroke",
    ) {
        val jobEpoch = epoch
        val sequence = allocateSequence()
        pendingCount++

        if (pendingCount >= maxPendingJobs) {
            compressSynchronously(
                epoch = jobEpoch,
                sequence = sequence,
                layerId = layerId,
                beforeRaw = beforeRaw,
                afterRaw = afterRaw,
                operation = operation,
                tag = tag,
            )
            process(undoManager)
            return
        }

        submit(
            epoch = jobEpoch,
            sequence = sequence,
            layerId = layerId,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            operation = operation,
            tag = tag,
        )
    }

    /** Applies completed work in commit order. Must be called on the GL thread. */
    fun process(undoManager: UndoManager): Boolean {
        var changed = false

        while (true) {
            val result = pendingResults.poll() ?: break
            if (result.sequence < nextSequenceToApply) {
                discard(result)
                decrementPending()
                changed = true
            } else {
                completedResults.put(result.sequence, result)?.let(::disposeReady)
            }
        }

        while (true) {
            val result = completedResults.remove(nextSequenceToApply) ?: break
            apply(result, undoManager)
            decrementPending()
            nextSequenceToApply++
            changed = true
        }

        return changed
    }

    /** Rejects compression results that belong to a document state that no longer exists. */
    fun invalidate() {
        epoch++
        nextSequenceToApply = nextSequence
        val discardedCount = completedResults.size
        completedResults.values.forEach(::disposeReady)
        completedResults.clear()
        pendingCount = (pendingCount - discardedCount).coerceAtLeast(0)
        staleResultCount += discardedCount
    }

    /** Reopens the worker after an EGL/context recreation. */
    fun ensureRunning() {
        if (executor.isShutdown) executor = executorFactory()
    }

    /** Releases worker-owned results without changing the caller's [UndoManager]. */
    fun shutdown() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        while (true) {
            val result = pendingResults.poll() ?: break
            disposeReady(result)
        }
        completedResults.values.forEach(::disposeReady)
        completedResults.clear()
        pendingCount = 0
        nextSequenceToApply = nextSequence
    }

    private fun allocateSequence(): Long = nextSequence++

    private fun submit(
        epoch: Long,
        sequence: Long,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType,
        tag: String,
    ) {
        try {
            executor.execute {
                val result = runCatching {
                    readyResult(
                        epoch = epoch,
                        sequence = sequence,
                        layerId = layerId,
                        beforeRaw = beforeRaw,
                        afterRaw = afterRaw,
                        operation = operation,
                        tag = tag,
                    )
                }.getOrElse { error ->
                    UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error)
                }
                pendingResults.add(result)
                requestRender()
            }
        } catch (error: RejectedExecutionException) {
            pendingResults.add(
                UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error),
            )
            requestRender()
        }
    }

    private fun compressSynchronously(
        epoch: Long,
        sequence: Long,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType,
        tag: String,
    ) {
        val result = runCatching {
            readyResult(
                epoch = epoch,
                sequence = sequence,
                layerId = layerId,
                beforeRaw = beforeRaw,
                afterRaw = afterRaw,
                operation = operation,
                tag = tag,
            )
        }.getOrElse { error ->
            UndoJobResult.Failed(epoch = epoch, sequence = sequence, error = error)
        }
        pendingResults.add(result)
    }

    private fun readyResult(
        epoch: Long,
        sequence: Long,
        layerId: Long,
        beforeRaw: List<RawTileSnapshot>,
        afterRaw: List<RawTileSnapshot>,
        operation: UndoOperationType,
        tag: String,
    ): UndoJobResult.Ready = UndoJobResult.Ready(
        epoch = epoch,
        sequence = sequence,
        entry = UndoEntry(
            layerId = layerId,
            beforeTiles = beforeRaw.map { it.compress(compressor) },
            afterTiles = afterRaw.map { it.compress(compressor) },
            operation = operation,
            tag = tag,
        ),
    )

    private fun apply(result: UndoJobResult, undoManager: UndoManager) {
        when (result) {
            is UndoJobResult.Ready -> {
                if (isUndoResultCurrent(result.epoch, epoch)) {
                    undoManager.push(result.entry)
                } else {
                    result.entry.dispose()
                    staleResultCount++
                }
            }

            is UndoJobResult.Failed -> {
                compressionFailureCount++
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "Undo compression failed", result.error)
                }
            }
        }
    }

    private fun discard(result: UndoJobResult) {
        disposeReady(result)
        staleResultCount++
    }

    private fun disposeReady(result: UndoJobResult) {
        if (result is UndoJobResult.Ready) result.entry.dispose()
    }

    private fun decrementPending() {
        pendingCount = (pendingCount - 1).coerceAtLeast(0)
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_JOBS = 8
        const val SHUTDOWN_TIMEOUT_MS = 100L
        const val TAG = "TileUndo"
    }
}
