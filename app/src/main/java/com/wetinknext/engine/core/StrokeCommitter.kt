package com.wetinknext.engine.core

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.engine.brush.StrokeRenderMode
import com.wetinknext.BuildConfig
import com.wetinknext.engine.canvas.PaintLayer
import com.wetinknext.engine.canvas.NonBuildupStrokeRenderer
import com.wetinknext.engine.canvas.StrokeBlitter
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.undo.TileSnapshot
import com.wetinknext.engine.undo.TileSnapshotCapture
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.PboTileReadbackQueue

/**
 * GL-thread transaction boundary for a prepared stroke.
 *
 * The caller renders a normal stroke into [strokeTarget]. This class then
 * snapshots the affected layer tiles, performs the single premultiplied blit,
 * captures the after-state, and sends the reversible transaction to history.
 */
class StrokeCommitter(
    private val strokeTarget: RenderTarget,
    private val canvasToFbo: FloatArray,
    private val undoPipeline: UndoCompressionPipeline,
    private val undoManager: UndoManager,
    private val requestRender: () -> Unit,
    private val onTilesCommitted: (PaintLayer, List<RawTileSnapshot>) -> Unit = { _, _ -> },
    private val onLayerCleared: (PaintLayer) -> Unit = {},
) {
    sealed interface CommitResult {
        data object Queued : CommitResult
        data object Rejected : CommitResult
    }

    private data class PendingTransaction(
        val layer: PaintLayer,
        val before: PboTileReadbackQueue.Capture,
        val after: PboTileReadbackQueue.Capture?,
        val operation: UndoOperationType,
        val tag: String,
    )

    private val readbackQueue = PboTileReadbackQueue(requestRender)
    private val pendingTransactions = ArrayDeque<PendingTransaction>()

    val pendingReadbackCount: Int
        get() = pendingTransactions.size

    /**
     * Commits the already-rendered stroke target into [layer]. [dirtyBounds]
     * is in canvas coordinates and is expanded to a deterministic tile set.
     */
    fun commit(
        sourceTarget: com.wetinknext.engine.gl.RenderTarget = strokeTarget,
        layer: PaintLayer,
        geometry: CanvasGeometry,
        blitter: StrokeBlitter,
        dirtyBounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
        strokeMode: StrokeRenderMode = StrokeRenderMode.NORMAL_BUILDUP,
        operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        tag: String = "stroke",
    ): CommitResult {
        GlCheck.checkOnGlThread()
        if (!layer.created || layer.isLocked || sourceTarget.textureId == 0) return CommitResult.Rejected
        if (dirtyBounds.size < 4) return CommitResult.Rejected

        expandBoundsToTiles(dirtyBounds, canvasWidth, canvasHeight)
        if (dirtyBounds[2] <= dirtyBounds[0] || dirtyBounds[3] <= dirtyBounds[1]) return CommitResult.Rejected
        logLargeUndoReadback(layer, dirtyBounds)

        val before = readbackQueue.issue(layer.target, dirtyBounds)
        blitter.blit(
            layer = layer.target,
            geometry = geometry,
            strokeTextureId = sourceTarget.textureId,
            canvasToFbo = canvasToFbo,
            width = canvasWidth,
            height = canvasHeight,
            opacity = opacity.coerceIn(0f, 1f),
            erase = erase,
            strokeMode = strokeMode,
        )
        layer.version++
        val after = readbackQueue.issue(layer.target, dirtyBounds)
        pendingTransactions += PendingTransaction(
            layer = layer,
            before = before,
            after = after,
            operation = operation,
            tag = tag,
        )
        return CommitResult.Queued
    }

    /** Commits a STAMP NON_BUILDUP stroke from its union coverage target. */
    fun commitNonBuildup(
        layer: PaintLayer,
        geometry: CanvasGeometry,
        blitter: NonBuildupStrokeRenderer,
        coverageTarget: RenderTarget,
        colorLinear: FloatArray,
        dirtyBounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
        strokeMode: StrokeRenderMode = StrokeRenderMode.NORMAL_BUILDUP,
        edgeDarkening: Float = 0f,
    ): CommitResult {
        GlCheck.checkOnGlThread()
        if (!layer.created || layer.isLocked || coverageTarget.textureId == 0 || colorLinear.size < 3) return CommitResult.Rejected
        if (dirtyBounds.size < 4) return CommitResult.Rejected

        expandBoundsToTiles(dirtyBounds, canvasWidth, canvasHeight)
        if (dirtyBounds[2] <= dirtyBounds[0] || dirtyBounds[3] <= dirtyBounds[1]) return CommitResult.Rejected
        logLargeUndoReadback(layer, dirtyBounds)

        val before = readbackQueue.issue(layer.target, dirtyBounds)
        blitter.blit(
            layer = layer.target,
            geometry = geometry,
            coverageTextureId = coverageTarget.textureId,
            colorLinear = colorLinear,
            canvasToFbo = canvasToFbo,
            width = canvasWidth,
            height = canvasHeight,
            opacity = opacity,
            erase = erase,
            strokeMode = strokeMode,
            edgeDarkening = edgeDarkening,
        )
        layer.version++
        val after = readbackQueue.issue(layer.target, dirtyBounds)
        pendingTransactions += PendingTransaction(
            layer = layer,
            before = before,
            after = after,
            operation = UndoOperationType.TILE_EDIT,
            tag = "stroke",
        )
        return CommitResult.Queued
    }

    /** Clears one editable layer and records the full canvas as one undo transaction. */
        fun processPendingReadbacks(): Boolean {
        GlCheck.checkOnGlThread()
        readbackQueue.poll()
        var changed = false
        while (pendingTransactions.isNotEmpty()) {
            val transaction = pendingTransactions.first()
            if (!transaction.before.isComplete || transaction.after?.isComplete == false) break
            pendingTransactions.removeFirst()
            undoPipeline.enqueue(
                undoManager = undoManager,
                layerId = transaction.layer.id,
                beforeRaw = transaction.before.snapshots,
                afterRaw = transaction.after?.snapshots.orEmpty(),
                operation = transaction.operation,
                tag = transaction.tag,
            )
            if (transaction.operation == UndoOperationType.CLEAR_LAYER) {
                onLayerCleared(transaction.layer)
            } else {
                onTilesCommitted(transaction.layer, transaction.after?.snapshots.orEmpty())
            }
            changed = true
        }
        return changed
    }

    fun releaseReadbacks() {
        GlCheck.checkOnGlThread()
        readbackQueue.release()
        pendingTransactions.clear()
    }

    private fun expandBoundsToTiles(
        bounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
    ) {
        val tileSize = TileSnapshot.TILE_SIZE
        bounds[0] = (bounds[0] / tileSize) * tileSize
        bounds[1] = (bounds[1] / tileSize) * tileSize
        bounds[2] = ((bounds[2] + tileSize - 1) / tileSize) * tileSize
        bounds[3] = ((bounds[3] + tileSize - 1) / tileSize) * tileSize
        bounds[0] = bounds[0].coerceIn(0, canvasWidth)
        bounds[1] = bounds[1].coerceIn(0, canvasHeight)
        bounds[2] = bounds[2].coerceIn(0, canvasWidth)
        bounds[3] = bounds[3].coerceIn(0, canvasHeight)
    }

    private fun logLargeUndoReadback(
        layer: PaintLayer,
        bounds: IntArray,
    ) {
        if (!BuildConfig.DEBUG) return

        val tileCount = TileSnapshotCapture.countTiles(layer.target, bounds)
        if (tileCount > MAX_UNDO_TILES_PER_OPERATION) {
            Log.w(
                TAG,
                "Large commit: tiles=$tileCount layer=${layer.id} " +
                    "bounds=${bounds.contentToString()}",
            )
        }
    }

    private companion object {
        const val TAG = "StrokeCommitter"
        const val MAX_UNDO_TILES_PER_OPERATION = 64
    }
}
