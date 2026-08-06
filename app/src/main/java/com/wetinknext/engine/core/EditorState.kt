package com.wetinknext.engine.core

/** Immutable state published by the GL thread for Compose to render. */
data class LayerUiModel(
    val id: Long,
    val name: String,
    val isVisible: Boolean,
    val isLocked: Boolean,
    val opacity: Float,
    val active: Boolean,
    val canDelete: Boolean,
)

data class EditorUiState(
    val layers: List<LayerUiModel>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val brushSizePx: Float,
    val brushOpacity: Float,
    val activeLayerId: Long,
    val ready: Boolean,
) {
    companion object {
        val empty = EditorUiState(
            layers = emptyList(),
            canUndo = false,
            canRedo = false,
            brushSizePx = 16f,
            brushOpacity = 1f,
            activeLayerId = -1L,
            ready = false,
        )
    }
}
