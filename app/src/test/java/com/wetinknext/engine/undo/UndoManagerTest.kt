package com.wetinknext.engine.undo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wetinknext.engine.canvas.BlendMode

class UndoManagerTest {
    @Test
    fun undoAndRedoMoveTheSameEntryBetweenStacks() {
        val manager = UndoManager()
        val entry = entry(1L)
        manager.push(entry)

        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
        assertSame(entry, manager.peekUndo())
        assertTrue(manager.commitUndo(entry))
        assertFalse(manager.canUndo)
        assertTrue(manager.canRedo)
        assertSame(entry, manager.peekRedo())
        assertTrue(manager.commitRedo(entry))
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun aNewEntryClearsRedoHistory() {
        val manager = UndoManager()
        val first = entry(1L)
        manager.push(first)
        val undo = manager.peekUndo()
        assertNotNull(undo)
        assertTrue(manager.commitUndo(checkNotNull(undo)))
        assertTrue(manager.canRedo)

        manager.push(entry(1L))

        assertFalse(manager.canRedo)
        assertEquals(1, manager.undoCount)
        assertEquals(0, manager.redoCount)
    }

    @Test
    fun stepLimitEvictsTheOldestHistoryEntry() {
        val manager = UndoManager(maxSteps = 2)
        manager.push(entry(1L))
        manager.push(entry(2L))
        manager.push(entry(3L))

        assertEquals(2, manager.undoCount)
    }

    @Test
    fun rejectedCommitLeavesUndoHistoryUntouched() {
        val manager = UndoManager()
        val stored = entry(1L)
        manager.push(stored)

        assertFalse(manager.commitUndo(entry(2L)))
        assertSame(stored, manager.peekUndo())
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun removingLayerPurgesOnlyThatLayersUndoAndRedoEntries() {
        val manager = UndoManager()
        val firstLayer = entry(1L)
        val secondLayer = entry(2L)
        manager.push(firstLayer)
        manager.push(secondLayer)
        assertTrue(manager.commitUndo(secondLayer))

        manager.removeEntriesForLayer(1L)

        assertFalse(manager.canUndo)
        assertTrue(manager.canRedo)
        assertSame(secondLayer, manager.peekRedo())

        manager.removeEntriesForLayer(2L)

        assertFalse(manager.canRedo)
    }

    @Test
    fun entryKeepsItsOperationMetadata() {
        val entry = UndoEntry(
            layerId = 1L,
            beforeTiles = emptyList(),
            afterTiles = emptyList(),
            operation = UndoOperationType.CLEAR_LAYER,
            tag = "clear_layer",
        )

        assertEquals(UndoOperationType.CLEAR_LAYER, entry.operation)
        assertEquals("clear_layer", entry.tag)
    }

    @Test
    fun removingLayerEntriesDoesNotBlockOtherHistory() {
        val manager = UndoManager()
        val removedLayerEntry = entry(layerId = 10L)
        val validEntry = entry(layerId = 20L)
        manager.push(removedLayerEntry)
        manager.push(validEntry)

        manager.removeEntriesForLayer(10L)

        assertTrue(manager.canUndo)
        assertSame(validEntry, manager.peekUndo())
    }

    @Test
    fun fiveEntriesCanBeCommittedInOrder() {
        val manager = UndoManager()
        repeat(5) { index -> manager.push(entry(layerId = index.toLong())) }

        assertEquals(5, manager.undoCount)
        repeat(5) {
            val entry = manager.peekUndo()
            assertNotNull(entry)
            assertTrue(manager.commitUndo(checkNotNull(entry)))
        }

        assertEquals(0, manager.undoCount)
        assertEquals(5, manager.redoCount)
    }

    @Test
    fun layerPropertiesAreFirstClassZeroPixelHistory() {
        val entry = UndoEntry(
            DocumentCommand.LayerProperties(
                layerId = 7L,
                before = LayerPropertiesState(true, false, 1f, BlendMode.NORMAL),
                after = LayerPropertiesState(false, false, .4f, BlendMode.NORMAL),
            ),
        )

        assertEquals(UndoOperationType.LAYER_PROPERTIES, entry.operation)
        assertEquals(0L, entry.memorySize)
        assertEquals(7L, entry.layerId)
    }

    @Test
    fun removeLayerCommandSurvivesNormalStackMoves() {
        val manager = UndoManager()
        val entry = UndoEntry(
            DocumentCommand.RemoveLayer(
                layerId = 9L,
                layer = RemovedLayerState(
                    index = 1,
                    activeLayerIdBefore = 9L,
                    activeLayerIdAfter = 2L,
                    name = "Paint",
                    visible = true,
                    locked = false,
                    opacity = 1f,
                    blendMode = BlendMode.NORMAL,
                    version = 3L,
                    tiles = listOf(tile(0)),
                ),
            ),
        )
        manager.push(entry)

        assertTrue(manager.commitUndo(entry))
        assertSame(entry, manager.peekRedo())
        assertTrue(manager.commitRedo(entry))
        assertSame(entry, manager.peekUndo())
    }

    private fun entry(layerId: Long): UndoEntry = UndoEntry(
        layerId = layerId,
        beforeTiles = listOf(tile(0)),
        afterTiles = listOf(tile(1)),
    )

    private fun tile(x: Int): TileSnapshot = TileSnapshot(
        coord = TileCoord(x, 0),
        pixelLeft = x,
        pixelTop = 0,
        pixelWidth = 1,
        pixelHeight = 1,
        bytesPerPixel = TileSnapshot.BYTES_PER_PIXEL_RGBA8,
        storedData = byteArrayOf(1, 2, 3, 4),
    )
}
