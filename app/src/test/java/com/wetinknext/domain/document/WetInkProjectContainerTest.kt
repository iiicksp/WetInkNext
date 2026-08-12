package com.wetinknext.domain.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class WetInkProjectContainerTest {
    @Test
    fun savePublishesOneContainerWithSeparateLayerPayloads() {
        val root = Files.createTempDirectory("wetink-project-test").toFile()
        val projectFile = File(root, "sketch.wetink")
        val document = ProjectDocument.newUntitled(
            id = "project-1",
            nowMillis = 100L,
        )
        val assets = ProjectAssets(
            layerTiles = mapOf(
                1L to byteArrayOf(1, 2, 3),
                2L to byteArrayOf(4, 5, 6),
            ),
            thumbnailWebp = byteArrayOf(7, 8),
            layerPreviewsWebp = mapOf(2L to byteArrayOf(9)),
        )
        val container = WetInkProjectContainer(clockMillis = { 200L })

        val saved = container.save(projectFile, document, assets)
        val loaded = container.load(projectFile)

        assertEquals(100L, document.updatedAt)
        assertEquals(200L, saved.document.updatedAt)
        assertEquals(saved.document, loaded.document)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.assets.layerTiles.getValue(1L))
        assertArrayEquals(byteArrayOf(4, 5, 6), loaded.assets.layerTiles.getValue(2L))
        assertArrayEquals(byteArrayOf(7, 8), loaded.assets.thumbnailWebp)
        assertArrayEquals(byteArrayOf(9), loaded.assets.layerPreviewsWebp.getValue(2L))
        assertFalse(File(root, "sketch.wetink.tmp").exists())

        ZipFile(projectFile).use { zip ->
            assertEquals(true, zip.getEntry("manifest.json") != null)
            assertEquals(true, zip.getEntry("document.json") != null)
            assertEquals(true, zip.getEntry("layers/1.tiles") != null)
            assertEquals(true, zip.getEntry("layers/2.tiles") != null)
            assertEquals(true, zip.getEntry("thumbnail.webp") != null)
            assertEquals(true, zip.getEntry("previews/2.webp") != null)
        }
    }

    @Test
    fun invalidSecondSaveLeavesPublishedProjectUntouched() {
        val root = Files.createTempDirectory("wetink-project-atomic").toFile()
        val projectFile = File(root, "sketch.wetink")
        val document = ProjectDocument.newUntitled(
            id = "project-1",
            nowMillis = 100L,
        )
        val container = WetInkProjectContainer(clockMillis = { 200L })
        container.save(
            projectFile,
            document,
            ProjectAssets(mapOf(1L to byteArrayOf(1), 2L to byteArrayOf(2))),
        )

        try {
            container.save(
                projectFile,
                document,
                ProjectAssets(mapOf(1L to byteArrayOf(99))),
            )
            fail("Missing layer payload must reject the save")
        } catch (_: IllegalArgumentException) {
            // Expected: validation happens before replacing the published file.
        }

        val loaded = container.load(projectFile)
        assertArrayEquals(byteArrayOf(1), loaded.assets.layerTiles.getValue(1L))
        assertArrayEquals(byteArrayOf(2), loaded.assets.layerTiles.getValue(2L))
    }
}
