package com.wetinknext.engine.core

import android.opengl.GLES30
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

/**
 * GL-thread transaction boundary for a prepared stroke.
 *
 * The caller renders the active stroke into [strokeTarget]. This class then
 * snapshots the affected layer tiles, performs the single premultiplied blit,
 * captures the after-state, and sends the reversible transaction to history.
 */
class StrokeCommitter(
    private val strokeTarget: RenderTarget,
    private val canvasToFbo: FloatArray,
    private val undoPipeline: UndoCompressionPipeline,
    private val undoManager: UndoManager,
    private val onTilesCommitted: (PaintLayer, List<RawTileSnapshot>) -> Unit = { _, _ -> },
    private val onLayerCleared: (PaintLayer) -> Unit = {},
) {
    /**
     * Commits the already-rendered stroke target into [layer]. [dirtyBounds]
     * is in canvas coordinates and is expanded to a deterministic tile set.
     */
    fun commit(
        layer: PaintLayer,
        geometry: CanvasGeometry,
        blitter: StrokeBlitter,
        dirtyBounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
        operation: UndoOperationType = UndoOperationType.TILE_EDIT,
        tag: String = "stroke",
    ): Boolean {
        GlCheck.checkOnGlThread()
        if (!layer.created || layer.isLocked || strokeTarget.textureId == 0) return false
        if (dirtyBounds.size < 4) return false

        expandBoundsToTiles(dirtyBounds, canvasWidth, canvasHeight)
        if (dirtyBounds[2] <= dirtyBounds[0] || dirtyBounds[3] <= dirtyBounds[1]) return false

        val beforeRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        blitter.blit(
            layer = layer.target,
            geometry = geometry,
            strokeTextureId = strokeTarget.textureId,
            canvasToFbo = canvasToFbo,
            width = canvasWidth,
            height = canvasHeight,
            opacity = opacity.coerceIn(0f, 1f),
            erase = erase,
        )
        GLES30.glFlush()
        layer.version++
        val afterRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        undoPipeline.enqueue(
            undoManager = undoManager,
            layerId = layer.id,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            operation = operation,
            tag = tag,
        )
        onTilesCommitted(layer, afterRaw)
        return true
    }

    /** Commits a STAMP NON_BUILDUP stroke using its colour and union-coverage targets. */
    fun commitNonBuildup(
        layer: PaintLayer,
        geometry: CanvasGeometry,
        blitter: NonBuildupStrokeRenderer,
        coverageTarget: RenderTarget,
        dirtyBounds: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        opacity: Float,
        erase: Boolean = false,
    ): Boolean {
        GlCheck.checkOnGlThread()
        if (!layer.created || layer.isLocked || strokeTarget.textureId == 0 || coverageTarget.textureId == 0) return false
        if (dirtyBounds.size < 4) return false

        expandBoundsToTiles(dirtyBounds, canvasWidth, canvasHeight)
        if (dirtyBounds[2] <= dirtyBounds[0] || dirtyBounds[3] <= dirtyBounds[1]) return false

        val beforeRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        blitter.blit(
            layer = layer.target,
            geometry = geometry,
            colorTextureId = strokeTarget.textureId,
            coverageTextureId = coverageTarget.textureId,
            canvasToFbo = canvasToFbo,
            width = canvasWidth,
            height = canvasHeight,
            opacity = opacity,
            erase = erase,
        )
        GLES30.glFlush()
        layer.version++
        val afterRaw = TileSnapshotCapture.capture(layer.target, dirtyBounds)
        undoPipeline.enqueue(
            undoManager = undoManager,
            layerId = layer.id,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
        )
        onTilesCommitted(layer, afterRaw)
        return true
    }

    /** Clears one editable layer and records the full canvas as one undo transaction. */
    fun clearLayer(
        layer: PaintLayer,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Boolean {
        GlCheck.checkOnGlThread()
        if (!layer.created || layer.isLocked) return false

        val fullBounds = intArrayOf(0, 0, canvasWidth, canvasHeight)
        val beforeRaw = TileSnapshotCapture.capture(layer.target, fullBounds)
        layer.clear()
        GLES30.glFlush()
        layer.version++
        undoPipeline.enqueue(
            undoManager = undoManager,
            layerId = layer.id,
            beforeRaw = beforeRaw,
            // Redo clears the target directly. Keeping a full transparent copy would
            // double memory for a large document and can exhaust the Java heap.
            afterRaw = emptyList(),
            operation = UndoOperationType.CLEAR_LAYER,
            tag = "clear_layer",
        )
        onLayerCleared(layer)
        return true
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
}
