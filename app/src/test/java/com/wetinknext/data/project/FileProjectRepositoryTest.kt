package com.wetinknext.data.project

import com.wetinknext.domain.document.ProjectAssets
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.domain.document.WetInkProjectContainer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FileProjectRepositoryTest {
    @Test
    fun projectLifecycleKeepsGalleryMetadataCompact() {
        val root = Files.createTempDirectory("wetink-repository").toFile()
        val time = AtomicLong(100L)
        val repository = FileProjectRepository(
            projectsDirectory = root,
            container = WetInkProjectContainer(clockMillis = { time.incrementAndGet() }),
        )

        val first = runSuspend {
            repository.create(CreateProjectRequest(name = "First", width = 800, height = 600))
        }
        val second = runSuspend {
            repository.create(CreateProjectRequest(name = "Second", width = 1200, height = 900))
        }

        val listed = runSuspend { repository.list() }
        assertEquals(listOf(second.id, first.id), listed.map(ProjectSummary::id))
        assertEquals("Second", listed.first().name)
        assertEquals(1200, listed.first().width)
        assertEquals(900, listed.first().height)

        val opened = runSuspend { repository.open(first.id) }
        assertEquals("First", opened.name)
        assertEquals(800, opened.width)

        runSuspend { repository.rename(first.id, "Renamed") }
        assertEquals("Renamed", runSuspend { repository.open(first.id) }.name)

        runSuspend { repository.moveToTrash(first.id) }
        assertFalse(runSuspend { repository.list() }.any { it.id == first.id })
        assertEquals(first.id, runSuspend { repository.listTrash() }.single().project.id)
        runSuspend { repository.restore(first.id) }
        assertTrue(runSuspend { repository.list() }.any { it.id == first.id })
        runSuspend { repository.moveToTrash(first.id) }
        runSuspend { repository.deleteForever(first.id) }
        assertFalse(File(root, ".trash/${first.id}.wetink").exists())
    }

    @Test
    fun metadataSavePreservesExistingTilePayloads() {
        val root = Files.createTempDirectory("wetink-repository-save").toFile()
        val container = WetInkProjectContainer(clockMillis = { 500L })
        val repository = FileProjectRepository(root, container)
        val document = ProjectDocument.newUntitled(id = "project-1", nowMillis = 100L)
        val file = File(root, "project-1.wetink")
        container.save(
            file,
            document,
            ProjectAssets(mapOf(1L to byteArrayOf(1, 2), 2L to byteArrayOf(3, 4))),
        )

        runSuspend { repository.save(document.copy(name = "Saved name")) }

        // Saving a legacy ZIP is the migration point: the same project is now
        // represented by projects/project-1/{manifest,document,layers}.
        assertTrue(File(root, "project-1").isDirectory)
        val loaded = runSuspend { repository.openForEditor("project-1") }
        assertEquals("Saved name", loaded.document.name)
        assertArrayEquals(byteArrayOf(1, 2), loaded.assets.layerTiles.getValue(1L))
        assertArrayEquals(byteArrayOf(3, 4), loaded.assets.layerTiles.getValue(2L))
    }

    @Test
    fun recoverySnapshotPreservesNewestTilePayloadUntilPrimarySave() {
        val root = Files.createTempDirectory("wetink-recovery").toFile()
        val repository = FileProjectRepository(root, WetInkProjectContainer(clockMillis = { 700L }))
        val summary = runSuspend { repository.create(CreateProjectRequest(name = "Recovery")) }
        val document = runSuspend { repository.open(summary.id) }

        runSuspend { repository.saveRecovery(document, mapOf(2L to byteArrayOf(9, 8, 7))) }
        val recovered = runSuspend { repository.openRecovery(summary.id) }
        assertArrayEquals(byteArrayOf(9, 8, 7), checkNotNull(recovered).assets.layerTiles.getValue(2L))

        runSuspend { repository.save(recovered.document, recovered.assets.layerTiles) }
        runSuspend { repository.clearRecovery(summary.id) }
        assertTrue(runSuspend { repository.openRecovery(summary.id) } == null)
        assertArrayEquals(byteArrayOf(9, 8, 7), runSuspend { repository.openForEditor(summary.id) }.assets.layerTiles.getValue(2L))
    }

    @Test
    fun thumbnailSaveIsAtomicAndGalleryReadsThePublishedPreview() {
        val root = Files.createTempDirectory("wetink-thumbnail").toFile()
        val repository = FileProjectRepository(root, WetInkProjectContainer(clockMillis = { 800L }))
        val summary = runSuspend { repository.create(CreateProjectRequest(name = "Preview")) }
        val document = runSuspend { repository.open(summary.id) }
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x57, 0x45, 0x42, 0x50)

        runSuspend { repository.save(document, emptyMap(), webp) }

        val galleryItem = runSuspend { repository.list() }.single()
        assertArrayEquals(webp, galleryItem.thumbnailBytes)
        assertArrayEquals(webp, runSuspend { repository.openForEditor(summary.id) }.assets.thumbnailWebp)
    }

    @Test
    fun duplicateCreatesIndependentDirectoryProject() {
        val root = Files.createTempDirectory("wetink-duplicate").toFile()
        val repository = FileProjectRepository(root, WetInkProjectContainer(clockMillis = { 900L }))
        val original = runSuspend { repository.create(CreateProjectRequest(name = "Original")) }

        val duplicate = runSuspend { repository.duplicate(original.id) }

        assertFalse(original.id == duplicate.id)
        assertEquals("Original copy", duplicate.name)
        assertTrue(File(root, duplicate.id).isDirectory)
        val originalTiles = runSuspend { repository.openForEditor(original.id) }.assets.layerTiles
        val duplicateTiles = runSuspend { repository.openForEditor(duplicate.id) }.assets.layerTiles
        assertEquals(originalTiles.keys, duplicateTiles.keys)
        originalTiles.forEach { (layerId, bytes) ->
            assertArrayEquals(bytes, duplicateTiles.getValue(layerId))
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(value: Result<T>) {
                result = value
            }
        })
        return checkNotNull(result).getOrThrow()
    }
}
