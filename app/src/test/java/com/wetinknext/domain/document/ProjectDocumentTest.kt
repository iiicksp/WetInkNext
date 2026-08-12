package com.wetinknext.domain.document

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectDocumentTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun untitledProjectRoundTripsWithoutEmbeddingLayerPixels() {
        val document = ProjectDocument.newUntitled(
            id = "project-1",
            name = "Sketch",
            width = 2048,
            height = 1536,
            dpi = 300,
            nowMillis = 1234L,
        )

        val encoded = json.encodeToString(document)
        val decoded = json.decodeFromString<ProjectDocument>(encoded)

        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"pixelFile\":\"layers/1.tiles\""))
        assertFalse(encoded.contains("rawBytes"))
        assertFalse(encoded.contains("textureId"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun projectRejectsAnUnknownActiveLayer() {
        val document = ProjectDocument.newUntitled(id = "project-1", nowMillis = 1234L)
        document.copy(activeLayerId = 999L)
    }

    @Test
    fun newCanvasRejectsAnUnsafeInitialGpuAllocation() {
        try {
            ProjectDocument.newUntitled(width = 8192, height = 8192)
            fail("Expected memory-budget validation to reject an 8192px canvas")
        } catch (_: IllegalArgumentException) {
            // Expected: two initial layers and the stroke target would exceed the budget.
        }
    }
}
