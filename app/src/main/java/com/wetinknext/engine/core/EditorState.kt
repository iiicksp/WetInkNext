package com.wetinknext.engine.core

import com.wetinknext.domain.animation.AnimationDocument

/** Immutable state published by the GL thread for Compose to render. */
data class LayerUiModel(
    val id: Long,
    val name: String,
    val isVisible: Boolean,
    val isLocked: Boolean,
    val opacity: Float,
    val active: Boolean,
    val canDelete: Boolean,
    val thumbnailPath: String? = null,
    val thumbnailVersion: Long = 0L,
)

data class UndoDiagnostics(
    val pendingJobs: Int = 0,
    val staleResults: Int = 0,
    val compressionFailures: Int = 0,
    val restoreFailures: Int = 0,
    val memoryBytes: Long = 0L,
)

data class EditorUiState(
    val layers: List<LayerUiModel>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val brushSizePx: Float,
    val brushOpacity: Float,
    val activeLayerId: Long,
    val ready: Boolean,
    val undoDiagnostics: UndoDiagnostics = UndoDiagnostics(),
    val animationDocument: AnimationDocument? = null,
    val animationFrameId: Long = 0L,
    val animationPlaying: Boolean = false,
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
