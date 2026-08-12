package com.wetinknext.data.project

import com.wetinknext.domain.document.ProjectAssets
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.domain.document.ProjectManifest
import com.wetinknext.domain.document.WetInkProjectContainer
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** File-backed project catalog. Each project remains one independent .wetink file. */
class FileProjectRepository(
    private val projectsDirectory: File,
    private val container: WetInkProjectContainer = WetInkProjectContainer(),
    private val directoryStore: DirectoryProjectStore = DirectoryProjectStore(),
) : ProjectRepository {
    override suspend fun create(request: CreateProjectRequest): ProjectSummary {
        val document = ProjectDocument.newUntitled(
            name = request.name,
            width = request.width,
            height = request.height,
            dpi = request.dpi,
            colorProfile = request.colorProfile,
        )
        val assets = ProjectAssets(
            layerTiles = document.layers.associate { it.id to ByteArray(0) },
            thumbnailWebp = DEFAULT_THUMBNAIL_WEBP,
        )
        val saved = directoryStore.save(projectDirectory(document.id), document, assets)
        return saved.manifest.toSummary()
    }

    override suspend fun list(): List<ProjectSummary> {
        ensureDirectories()
        return listProjects(projectsDirectory)
    }

    override suspend fun listTrash(): List<TrashedProjectSummary> {
        ensureDirectories()
        return trashDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isDirectory || (file.isFile && file.extension.equals(FILE_EXTENSION, ignoreCase = true) && !file.name.endsWith(".recovery.$FILE_EXTENSION")) }
            .mapNotNull { file ->
                runCatching {
                    TrashedProjectSummary(readSummary(file), file.lastModified())
                }.getOrNull()
            }
            .sortedByDescending(TrashedProjectSummary::deletedAt)
            .toList()
    }

    override suspend fun open(id: String): ProjectDocument = loadProject(id).document

    override suspend fun openForEditor(id: String) = loadProject(id)

    override suspend fun save(document: ProjectDocument) {
        val previous = loadProject(document.id)
        saveProject(document, previous.assets.retainDocumentLayers(document))
    }

    override suspend fun save(document: ProjectDocument, changedLayerTiles: Map<Long, ByteArray>) {
        if (changedLayerTiles.isEmpty()) return save(document)
        val previous = loadProject(document.id)
        val allowed = document.layers.map { it.id }.toSet()
        require(changedLayerTiles.keys.all(allowed::contains)) { "Unknown layer tile payload" }
        saveProject(
            document,
            previous.assets.retainDocumentLayers(document).copy(
                layerTiles = previous.assets.layerTiles
                    .filterKeys(allowed::contains)
                    .plus(changedLayerTiles),
            ),
        )
    }

    override suspend fun save(
        document: ProjectDocument,
        changedLayerTiles: Map<Long, ByteArray>,
        thumbnailWebp: ByteArray?,
        layerPreviewsWebp: Map<Long, ByteArray>,
    ) {
        val previous = loadProject(document.id)
        val layerIds = document.layers.map { it.id }.toSet()
        saveProject(
            document,
            previous.assets.copy(
                layerTiles = (previous.assets.layerTiles + changedLayerTiles)
                    .filterKeys(layerIds::contains),
                thumbnailWebp = thumbnailWebp ?: previous.assets.thumbnailWebp,
                layerPreviewsWebp = (previous.assets.layerPreviewsWebp + layerPreviewsWebp)
                    .filterKeys(layerIds::contains),
            ),
        )
    }

    override suspend fun saveRecovery(document: ProjectDocument, changedLayerTiles: Map<Long, ByteArray>) {
        val current = loadProject(document.id)
        container.save(recoveryFile(document.id), document, current.assets.copy(layerTiles = current.assets.layerTiles + changedLayerTiles))
    }

    override suspend fun clearRecovery(id: String) {
        val file = recoveryFile(id)
        if (file.exists() && !file.delete()) throw IOException("Cannot clear recovery snapshot")
    }

    override suspend fun openRecovery(id: String) = recoveryFile(id).takeIf(File::isFile)?.let(container::load)

    override suspend fun rename(id: String, name: String) {
        val document = open(id)
        save(document.copy(name = name))
    }

    /** Creates a separate editable copy; undo history intentionally is not persisted. */
    override suspend fun duplicate(id: String): ProjectSummary {
        val source = loadProject(id)
        val copyId = UUID.randomUUID().toString()
        val copiedDocument = source.document.copy(
            id = copyId,
            name = duplicateName(source.document.name),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val saved = directoryStore.save(projectDirectory(copyId), copiedDocument, source.assets)
        return saved.manifest.toSummary(saved.assets.thumbnailWebp)
    }

    override suspend fun moveToTrash(id: String) {
        val source = projectLocation(id)
        require(source.exists()) { "Project does not exist: $id" }
        val target = trashedProjectLocation(id, source.isDirectory)
        atomicMove(source, target)
        target.setLastModified(System.currentTimeMillis())
    }

    override suspend fun restore(id: String) {
        val source = trashedProjectLocation(id, true).takeIf(File::exists) ?: trashedProjectLocation(id, false)
        require(source.exists()) { "Trashed project does not exist: $id" }
        atomicMove(source, if (source.isDirectory) projectDirectory(id) else legacyProjectFile(id))
    }

    override suspend fun deleteForever(id: String) {
        val file = trashedProjectLocation(id, true).takeIf(File::exists) ?: trashedProjectLocation(id, false)
        require(file.exists()) { "Trashed project does not exist: $id" }
        if (file.isDirectory) directoryStore.delete(file) else if (!file.delete()) throw IOException("Cannot delete project permanently: ${file.path}")
    }

    private fun projectDirectory(id: String): File {
        ensureDirectories()
        return File(projectsDirectory, safeId(id))
    }

    private fun legacyProjectFile(id: String): File = File(projectsDirectory, "${safeId(id)}.$FILE_EXTENSION")

    private fun trashedProjectLocation(id: String, directory: Boolean): File {
        ensureDirectories()
        return File(trashDirectory, if (directory) safeId(id) else "${safeId(id)}.$FILE_EXTENSION")
    }

    private fun projectLocation(id: String): File = projectDirectory(id).takeIf(File::exists) ?: legacyProjectFile(id)
    private fun recoveryFile(id: String): File = File(projectsDirectory, "${safeId(id)}.recovery.$FILE_EXTENSION")

    private fun ensureDirectories() {
        if (!projectsDirectory.exists() && !projectsDirectory.mkdirs()) {
            throw IOException("Cannot create projects directory: ${projectsDirectory.path}")
        }
        if (!trashDirectory.exists() && !trashDirectory.mkdirs()) {
            throw IOException("Cannot create project trash: ${trashDirectory.path}")
        }
    }

    private fun listProjects(directory: File): List<ProjectSummary> =
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isDirectory || (file.isFile && file.extension.equals(FILE_EXTENSION, ignoreCase = true) && !file.name.endsWith(".recovery.$FILE_EXTENSION")) }
            .mapNotNull { file -> runCatching { readSummary(file) }.getOrNull() }
            .sortedByDescending(ProjectSummary::updatedAt)
            .toList()

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic project move is not supported", error)
        }
    }

    private fun loadProject(id: String) = projectDirectory(id).takeIf(File::isDirectory)
        ?.let(directoryStore::load)
        ?: container.load(legacyProjectFile(id))

    /** First regular save migrates an old ZIP project to the directory format. */
    private fun saveProject(document: ProjectDocument, assets: ProjectAssets) =
        directoryStore.save(projectDirectory(document.id), document, assets).also {
            legacyProjectFile(document.id).takeIf(File::isFile)?.delete()
        }

    private fun readSummary(location: File): ProjectSummary = if (location.isDirectory) {
        directoryStore.readManifest(location).toSummary(directoryStore.readThumbnail(location))
    } else {
        container.readManifest(location).toSummary(container.readThumbnail(location))
    }

    private fun safeId(id: String): String {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Unsafe project id"
        }
        return id
    }

    private fun duplicateName(name: String): String = "$name copy".take(80)

    /** Drops binary assets for layers removed from the persisted document. */
    private fun ProjectAssets.retainDocumentLayers(document: ProjectDocument): ProjectAssets {
        val allowed = document.layers.map { it.id }.toSet()
        return copy(
            layerTiles = layerTiles.filterKeys(allowed::contains),
            layerPreviewsWebp = layerPreviewsWebp.filterKeys(allowed::contains),
        )
    }

    private fun ProjectManifest.toSummary(thumbnail: ByteArray? = null): ProjectSummary = ProjectSummary(
        id = documentId,
        name = name,
        width = width,
        height = height,
        updatedAt = updatedAt,
        thumbnailPath = thumbnailPath,
        thumbnailBytes = thumbnail,
        thumbnailVersion = thumbnailVersion,
    )

    private val trashDirectory: File
        get() = File(projectsDirectory, TRASH_DIRECTORY)

    private companion object {
        const val FILE_EXTENSION = "wetink"
        const val TRASH_DIRECTORY = ".trash"
        // Valid transparent 1×1 WebP until a GL-generated project preview is available.
        val DEFAULT_THUMBNAIL_WEBP: ByteArray = Base64.getDecoder().decode(
            "UklGRiIAAABXRUJQVlA4IBYAAADQAwCdASoBAAEAAUAmJaQAA3AA/vuUAAA=",
        )
    }
}
