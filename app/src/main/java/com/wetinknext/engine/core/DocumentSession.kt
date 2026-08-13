package com.wetinknext.engine.core

import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.BudgetedTargets
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.engine.undo.UndoManager

/** Render-thread lifetime of an opened document and its GPU layer resources. */
class DocumentSession(
    val document: ProjectDocument,
    val layerStack: LayerStack,
    val undoManager: UndoManager,
    private val layerTiles: Map<Long, ByteArray>,
) {
    /** Layers whose pixels must be persisted by the next autosave. GL-thread owned. */
    val dirtyLayerIds = mutableSetOf<Long>()

    /** Metadata or pixels changed since the last successful autosave. GL-thread owned. */
    var projectDirty: Boolean = false
        private set

    fun markLayerDirty(layerId: Long) {
        dirtyLayerIds += layerId
        projectDirty = true
    }

    fun markProjectDirty() {
        projectDirty = true
    }

    fun markSaved(savedLayerIds: Set<Long>) {
        dirtyLayerIds.removeAll(savedLayerIds)
        projectDirty = dirtyLayerIds.isNotEmpty()
    }

    fun loadIntoGpu(caps: GlCaps, targets: BudgetedTargets) {
        layerStack.create(caps, document, targets)
        document.layers.forEach { layer ->
            LayerTileCodec.uploadPersistent(
                target = checkNotNull(layerStack.findLayerById(layer.id)).target,
                payload = layerTiles[layer.id] ?: ByteArray(0),
            )
        }
        check(layerStack.activeLayerId == document.activeLayerId) { "Active layer was not restored" }
    }
}
