package com.wetinknext.data.project

import com.wetinknext.domain.document.LoadedWetInkProject
import com.wetinknext.domain.document.ProjectAssets
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.domain.document.ProjectManifest
import com.wetinknext.domain.document.StoredLayer
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** New on-disk project format: directory metadata plus independently replaceable layer files. */
class DirectoryProjectStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true },
) {
    private var lastTimestamp = Long.MIN_VALUE

    fun save(directory: File, document: ProjectDocument, assets: ProjectAssets): LoadedWetInkProject {
        require(directory.name == document.id) { "Project directory/id mismatch" }
        val expected = document.layers.map { it.id }.toSet()
        require(assets.layerTiles.keys == expected) { "Every layer needs one payload" }
        ensureDirectory(directory)
        val timestamp = nextTimestamp()
        val saved = document.copy(
            updatedAt = timestamp,
            thumbnailVersion = if (assets.thumbnailWebp != null) timestamp else document.thumbnailVersion,
        )
        saved.layers.forEach { layer -> writeAtomic(File(directory, layer.pixelFile), checkNotNull(assets.layerTiles[layer.id])) }
        assets.thumbnailWebp?.let { writeAtomic(File(directory, THUMBNAIL), it) }
        assets.layerPreviewsWebp.forEach { (id, bytes) -> writeAtomic(File(directory, previewPath(id)), bytes) }
        deleteRemovedLayerFiles(directory, saved.layers.map { it.id }.toSet())
        writeAtomic(File(directory, DOCUMENT), json.encodeToString(saved).encodeToByteArray())
        val manifest = ProjectManifest(
            documentId = saved.id, name = saved.name, width = saved.width, height = saved.height,
            thumbnailPath = assets.thumbnailWebp?.let { THUMBNAIL }, thumbnailVersion = saved.thumbnailVersion,
            createdAt = saved.createdAt, updatedAt = saved.updatedAt,
            layers = saved.layers.map { StoredLayer(it.id, it.pixelFile, checkNotNull(assets.layerTiles[it.id]).size.toLong()) },
        )
        writeAtomic(File(directory, MANIFEST), json.encodeToString(manifest).encodeToByteArray())
        return LoadedWetInkProject(saved, manifest, assets)
    }

    fun load(directory: File): LoadedWetInkProject {
        val manifest = readManifest(directory)
        val document = readDocument(directory)
        require(manifest.documentId == document.id) { "Manifest/document id mismatch" }
        val tiles = document.layers.associate { layer ->
            val bytes = requireFile(File(directory, layer.pixelFile)).readBytes()
            val stored = manifest.layers.firstOrNull { it.id == layer.id } ?: error("Missing layer metadata")
            require(stored.byteSize == bytes.size.toLong()) { "Layer size mismatch" }
            layer.id to bytes
        }
        val previews = document.layers.mapNotNull { layer ->
            File(directory, previewPath(layer.id)).takeIf(File::isFile)?.readBytes()?.let { layer.id to it }
        }.toMap()
        val thumbnail = manifest.thumbnailPath?.let { File(directory, it).takeIf(File::isFile)?.readBytes() }
        return LoadedWetInkProject(document, manifest, ProjectAssets(tiles, thumbnail, previews))
    }

    /** Keeps gallery sorting deterministic when two saves share one millisecond. */
    @Synchronized
    private fun nextTimestamp(): Long {
        val now = clock()
        val next = maxOf(now, lastTimestamp + 1L)
        lastTimestamp = next
        return next
    }

    fun readManifest(directory: File): ProjectManifest =
        json.decodeFromString(requireFile(File(directory, MANIFEST)).readText())

    fun readDocument(directory: File): ProjectDocument =
        json.decodeFromString(requireFile(File(directory, DOCUMENT)).readText())

    fun readThumbnail(directory: File): ByteArray? = File(directory, THUMBNAIL).takeIf(File::isFile)?.readBytes()

    fun delete(directory: File) {
        require(directory.isDirectory) { "Project directory does not exist" }
        directory.walkBottomUp().forEach { if (!it.delete()) throw IOException("Cannot delete ${it.path}") }
    }

    private fun deleteRemovedLayerFiles(directory: File, active: Set<Long>) {
        listOf(LAYERS, PREVIEWS).forEach { folder ->
            File(directory, folder).listFiles().orEmpty().forEach { file ->
                val id = file.name.removePrefix("layer-").substringBefore('.').toLongOrNull()
                    ?: file.name.substringBefore('.').toLongOrNull()
                if (id != null && id !in active) file.delete()
            }
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        ensureDirectory(checkNotNull(file.parentFile))
        val tmp = File(checkNotNull(file.parentFile), ".${file.name}.tmp")
        try {
            tmp.outputStream().use { it.write(bytes) }
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    private fun ensureDirectory(file: File) {
        if (!file.exists() && !file.mkdirs()) throw IOException("Cannot create ${file.path}")
        require(file.isDirectory) { "Expected directory: ${file.path}" }
    }

    private fun requireFile(file: File): File = file.also { require(it.isFile) { "Missing ${it.path}" } }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val DOCUMENT = "document.json"
        const val THUMBNAIL = "thumbnail.webp"
        const val LAYERS = "layers"
        const val PREVIEWS = "thumbnails"
        fun previewPath(layerId: Long) = "$PREVIEWS/layer-$layerId.webp"
    }
}
