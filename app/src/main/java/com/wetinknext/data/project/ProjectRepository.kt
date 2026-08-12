package com.wetinknext.data.project

import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.domain.document.LoadedWetInkProject
import com.wetinknext.domain.document.ProjectAssets

interface ProjectRepository {
    suspend fun create(request: CreateProjectRequest): ProjectSummary
    suspend fun list(): List<ProjectSummary>
    suspend fun listTrash(): List<TrashedProjectSummary>
    suspend fun open(id: String): ProjectDocument
    /** Reads document metadata and all layer payloads needed to build a GPU session. */
    suspend fun openForEditor(id: String): LoadedWetInkProject
    suspend fun save(document: ProjectDocument)
    /** Atomically replaces only the supplied layer payloads; other layer data is retained. */
    suspend fun save(document: ProjectDocument, changedLayerTiles: Map<Long, ByteArray>)
    suspend fun save(
        document: ProjectDocument,
        changedLayerTiles: Map<Long, ByteArray>,
        thumbnailWebp: ByteArray?,
        layerPreviewsWebp: Map<Long, ByteArray> = emptyMap(),
    )
    suspend fun saveRecovery(document: ProjectDocument, changedLayerTiles: Map<Long, ByteArray>)
    suspend fun clearRecovery(id: String)
    suspend fun openRecovery(id: String): LoadedWetInkProject?
    suspend fun rename(id: String, name: String)
    suspend fun duplicate(id: String): ProjectSummary
    suspend fun moveToTrash(id: String)
    suspend fun restore(id: String)
    suspend fun deleteForever(id: String)
}

data class CreateProjectRequest(
    val name: String,
    val width: Int = ProjectDocument.DEFAULT_WIDTH,
    val height: Int = ProjectDocument.DEFAULT_HEIGHT,
    val dpi: Int = ProjectDocument.DEFAULT_DPI,
    val colorProfile: String = ProjectDocument.SRGB_PROFILE,
)

/** Metadata for the gallery. No layer tile data is loaded to create this item. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val updatedAt: Long,
    val thumbnailPath: String?,
    val thumbnailBytes: ByteArray? = null,
    /** Cache version for the gallery WebP; metadata loading remains tile-free. */
    val thumbnailVersion: Long = 0L,
)

data class TrashedProjectSummary(
    val project: ProjectSummary,
    val deletedAt: Long,
)
