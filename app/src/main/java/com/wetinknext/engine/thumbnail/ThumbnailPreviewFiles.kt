package com.wetinknext.engine.thumbnail

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Cache-file contract shared by preview restoration and the GL build scheduler. */
object ThumbnailPreviewFiles {
    fun layerPreviewFile(outputDirectory: File, layerId: Long): File =
        File(File(outputDirectory, LAYER_PREVIEW_DIRECTORY), "$layerId.webp")

    /**
     * Restores previews unpacked from a .wetink container to cache atomically.
     * It deliberately performs no bitmap decoding, so this work remains cheap
     * and safe to execute before the GL session is ready.
     */
    fun restoreLayerPreviews(
        outputDirectory: File,
        previews: Map<Long, ByteArray>,
    ): Map<Long, File> = previews.mapNotNull { (layerId, bytes) ->
        if (bytes.isEmpty()) return@mapNotNull null
        val target = layerPreviewFile(outputDirectory, layerId)
        val parent = target.parentFile ?: return@mapNotNull null
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create preview directory: ${parent.path}")
        }
        val temporary = File(parent, ".${target.name}.restore.tmp")
        try {
            temporary.outputStream().use { it.write(bytes) }
            publish(temporary, target)
            layerId to target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }.toMap()

    private fun publish(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private const val LAYER_PREVIEW_DIRECTORY = "previews"
}
