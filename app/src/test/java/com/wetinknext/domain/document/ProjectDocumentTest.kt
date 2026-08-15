package com.wetinknext.domain.document

import com.wetinknext.engine.canvas.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectDocumentTest {
    @Test
    fun `new documents use compact layer storage`() {
        assertEquals(LayerStorageFormat.RGBA8, ProjectDocument.newUntitled().layerStorage)
    }

    @Test
    fun `version one documents retain legacy storage by default`() {
        val legacy = ProjectDocument(
            id = "legacy",
            name = "Legacy",
            width = 512,
            height = 512,
            dpi = 300,
            colorProfile = ProjectDocument.SRGB_PROFILE,
            createdAt = 1L,
            updatedAt = 1L,
            layers = listOf(
                LayerDocument(1L, "Layer", true, false, 1f, BlendMode.NORMAL, "layers/1.tiles"),
            ),
            version = 1,
        )

        assertEquals(LayerStorageFormat.RGBA16F, legacy.layerStorage)
    }
}
