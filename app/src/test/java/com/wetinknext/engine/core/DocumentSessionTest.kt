package com.wetinknext.engine.core

import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.undo.UndoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSessionTest {
    @Test
    fun committedLayersRemainDirtyUntilTheirSaveIsAcknowledged() {
        val session = DocumentSession(
            ProjectDocument.newUntitled(id = "session-test"),
            LayerStack(),
            UndoManager(),
            emptyMap(),
        )

        session.markLayerDirty(2L)
        session.markLayerDirty(2L)

        assertEquals(setOf(2L), session.dirtyLayerIds)
        assertTrue(session.projectDirty)

        session.markSaved(setOf(2L))

        assertTrue(session.dirtyLayerIds.isEmpty())
        assertFalse(session.projectDirty)
    }
}
