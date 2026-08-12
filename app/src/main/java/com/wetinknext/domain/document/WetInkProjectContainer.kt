package com.wetinknext.domain.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class ProjectManifest(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val documentId: String,
    val name: String,
    val width: Int,
    val height: Int,
    val documentPath: String = DOCUMENT_ENTRY,
    val thumbnailPath: String? = null,
    val thumbnailVersion: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
    val layers: List<StoredLayer>,
) {
    companion object {
        const val FORMAT = "wetink"
        const val FORMAT_VERSION = 1
        const val DOCUMENT_ENTRY = "document.json"
    }
}

@Serializable
data class StoredLayer(
    val id: Long,
    val path: String,
    val byteSize: Long,
)

/** Binary payloads deliberately sit next to the JSON document, not inside it. */
data class ProjectAssets(
    val layerTiles: Map<Long, ByteArray>,
    val thumbnailWebp: ByteArray? = null,
    val layerPreviewsWebp: Map<Long, ByteArray> = emptyMap(),
)

data class LoadedWetInkProject(
    val document: ProjectDocument,
    val manifest: ProjectManifest,
    val assets: ProjectAssets,
)

/**
 * Project ZIP reader/writer. A project is published only by an atomic rename
 * from `name.wetink.tmp` to `name.wetink`, so a failed save cannot corrupt the
 * previously published project.
 */
class WetInkProjectContainer(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    fun save(
        projectFile: File,
        document: ProjectDocument,
        assets: ProjectAssets,
    ): LoadedWetInkProject {
        require(projectFile.extension.equals(FILE_EXTENSION, ignoreCase = true)) {
            "Project file must use .$FILE_EXTENSION extension"
        }
        validateAssets(document, assets)

        val savedTimestamp = clockMillis()
        val savedDocument = document.copy(
            updatedAt = savedTimestamp,
            thumbnailVersion = if (assets.thumbnailWebp != null) savedTimestamp else document.thumbnailVersion,
        )
        val manifest = ProjectManifest(
            documentId = savedDocument.id,
            name = savedDocument.name,
            width = savedDocument.width,
            height = savedDocument.height,
            thumbnailPath = assets.thumbnailWebp?.let { THUMBNAIL_ENTRY },
            thumbnailVersion = savedDocument.thumbnailVersion,
            createdAt = savedDocument.createdAt,
            updatedAt = savedDocument.updatedAt,
            layers = savedDocument.layers.map { layer ->
                StoredLayer(
                    id = layer.id,
                    path = layer.pixelFile,
                    byteSize = checkNotNull(assets.layerTiles[layer.id]).size.toLong(),
                )
            },
        )

        val parent = projectFile.parentFile ?: throw IOException("Project file requires a parent folder")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create ${parent.path}")
        val temporaryFile = File(parent, "${projectFile.name}.tmp")
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Cannot replace stale temporary project ${temporaryFile.path}")
        }

        var published = false
        try {
            BufferedOutputStream(temporaryFile.outputStream()).use { output ->
                ZipOutputStream(output).use { zip ->
                    // Write large layer data first; metadata becomes visible only
                    // after every payload has been written and the ZIP is closed.
                    savedDocument.layers.forEach { layer ->
                        writeEntry(zip, layer.pixelFile, checkNotNull(assets.layerTiles[layer.id]))
                    }
                    writeEntry(zip, ProjectManifest.DOCUMENT_ENTRY, json.encodeToString(savedDocument).encodeToByteArray())
                    assets.thumbnailWebp?.let { writeEntry(zip, THUMBNAIL_ENTRY, it) }
                    assets.layerPreviewsWebp.forEach { (layerId, preview) ->
                        writeEntry(zip, previewPathFor(layerId), preview)
                    }
                    writeEntry(zip, MANIFEST_ENTRY, json.encodeToString(manifest).encodeToByteArray())
                }
            }
            if (temporaryFile.length() <= 0L) throw IOException("Temporary project is empty")
            atomicPublish(temporaryFile, projectFile)
            published = true
        } finally {
            if (!published && temporaryFile.exists()) temporaryFile.delete()
        }

        return LoadedWetInkProject(savedDocument, manifest, assets)
    }

    fun load(projectFile: File): LoadedWetInkProject {
        require(projectFile.isFile) { "Project does not exist: ${projectFile.path}" }
        ZipFile(projectFile).use { zip ->
            val manifest = readJson<ProjectManifest>(zip, MANIFEST_ENTRY)
            require(manifest.format == ProjectManifest.FORMAT) { "Unsupported project format" }
            require(manifest.formatVersion == ProjectManifest.FORMAT_VERSION) { "Unsupported project version" }

            val document = readJson<ProjectDocument>(zip, manifest.documentPath)
            require(document.id == manifest.documentId) { "Manifest/document id mismatch" }
            require(document.updatedAt == manifest.updatedAt) { "Manifest/document timestamp mismatch" }
            require(manifest.layers.map { it.id } == document.layers.map { it.id }) {
                "Manifest/document layer order mismatch"
            }

            val tiles = document.layers.associate { layer ->
                val stored = manifest.layers.first { it.id == layer.id }
                require(stored.path == layer.pixelFile) { "Layer path mismatch for ${layer.id}" }
                val bytes = readEntry(zip, layer.pixelFile)
                require(bytes.size.toLong() == stored.byteSize) { "Layer size mismatch for ${layer.id}" }
                layer.id to bytes
            }
            val previews = document.layers.mapNotNull { layer ->
                val entry = zip.getEntry(previewPathFor(layer.id)) ?: return@mapNotNull null
                layer.id to readEntry(zip, entry)
            }.toMap()
            val thumbnail = manifest.thumbnailPath?.let { readEntry(zip, it) }

            return LoadedWetInkProject(
                document = document,
                manifest = manifest,
                assets = ProjectAssets(tiles, thumbnail, previews),
            )
        }
    }

    /** Reads just the compact manifest; no document JSON or tile payload is loaded. */
    fun readManifest(projectFile: File): ProjectManifest {
        require(projectFile.isFile) { "Project does not exist: ${projectFile.path}" }
        ZipFile(projectFile).use { zip ->
            val manifest = readJson<ProjectManifest>(zip, MANIFEST_ENTRY)
            require(manifest.format == ProjectManifest.FORMAT) { "Unsupported project format" }
            require(manifest.formatVersion == ProjectManifest.FORMAT_VERSION) { "Unsupported project version" }
            return manifest
        }
    }

    fun readThumbnail(projectFile: File): ByteArray? {
        ZipFile(projectFile).use { zip ->
            val manifest = readJson<ProjectManifest>(zip, MANIFEST_ENTRY)
            return manifest.thumbnailPath?.let { readEntry(zip, it) }
        }
    }

    /** Opens only project metadata. Tile bytes remain in the ZIP until requested. */
    fun readDocument(projectFile: File): ProjectDocument {
        require(projectFile.isFile) { "Project does not exist: ${projectFile.path}" }
        ZipFile(projectFile).use { zip ->
            val manifest = readJson<ProjectManifest>(zip, MANIFEST_ENTRY)
            require(manifest.format == ProjectManifest.FORMAT) { "Unsupported project format" }
            require(manifest.formatVersion == ProjectManifest.FORMAT_VERSION) { "Unsupported project version" }
            val document = readJson<ProjectDocument>(zip, manifest.documentPath)
            require(document.id == manifest.documentId) { "Manifest/document id mismatch" }
            require(document.updatedAt == manifest.updatedAt) { "Manifest/document timestamp mismatch" }
            return document
        }
    }

    private fun validateAssets(document: ProjectDocument, assets: ProjectAssets) {
        val expectedIds = document.layers.map { it.id }.toSet()
        require(assets.layerTiles.keys == expectedIds) { "Every document layer requires one tile payload" }
        require(assets.layerPreviewsWebp.keys.all(expectedIds::contains)) {
            "Preview references an unknown layer"
        }
        document.layers.forEach { layer ->
            require(isSafeLayerPath(layer.pixelFile)) { "Unsafe layer path: ${layer.pixelFile}" }
        }
    }

    private fun atomicPublish(temporaryFile: File, projectFile: File) {
        try {
            Files.move(
                temporaryFile.toPath(),
                projectFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic project publish is not supported for ${projectFile.path}", error)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private inline fun <reified T> readJson(zip: ZipFile, path: String): T =
        json.decodeFromString(readEntry(zip, path).decodeToString())

    private fun readEntry(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: throw IOException("Missing project entry: $path")
        return readEntry(zip, entry)
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray {
        if (entry.size > MAX_ENTRY_BYTES) throw IOException("Project entry is too large: ${entry.name}")
        BufferedInputStream(zip.getInputStream(entry)).use { input ->
            val output = ByteArrayOutputStream(entry.size.coerceAtLeast(0L).coerceAtMost(MAX_ENTRY_BYTES).toInt())
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ENTRY_BYTES) {
                    throw IOException("Project entry is too large: ${entry.name}")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun isSafeLayerPath(path: String): Boolean =
        path.startsWith("layers/") && !path.contains("..") && !path.startsWith('/')

    private companion object {
        const val FILE_EXTENSION = "wetink"
        const val MANIFEST_ENTRY = "manifest.json"
        const val THUMBNAIL_ENTRY = "thumbnail.webp"
        const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
        const val READ_BUFFER_BYTES = 16 * 1024

        fun previewPathFor(layerId: Long): String = "previews/$layerId.webp"
    }
}
