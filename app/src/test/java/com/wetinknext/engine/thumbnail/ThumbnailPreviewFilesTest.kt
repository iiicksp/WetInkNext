package com.wetinknext.engine.thumbnail

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailPreviewFilesTest {
    @Test
    fun restoredLayerPreviewKeepsItsBytesAndStablePath() {
        val directory = Files.createTempDirectory("wetink-preview-test").toFile()
        try {
            val bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01)

            val restored = ThumbnailPreviewFiles.restoreLayerPreviews(
                outputDirectory = directory,
                previews = mapOf(42L to bytes),
            )

            val preview = requireNotNull(restored[42L])
            assertEquals("42.webp", preview.name)
            assertArrayEquals(bytes, preview.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
