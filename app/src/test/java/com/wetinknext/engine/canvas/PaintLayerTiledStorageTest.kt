package com.wetinknext.engine.canvas

import com.wetinknext.engine.gl.BudgetedTargets
import com.wetinknext.engine.gl.RenderTargetBudget
import com.wetinknext.engine.undo.TileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintLayerTiledStorageTest {
    private fun allocator(budget: RenderTargetBudget, halfFloat: Boolean = false) =
        LayerResourceAllocator(BudgetedTargets(budget), useHalfFloat = halfFloat)

    @Test fun largeCanvasGetsSixteenByNineGridWithoutAllocation() {
        val budget = RenderTargetBudget()
        val layer = PaintLayer(1L, "Layer 1")
        layer.enableTiledStorage(allocator(budget), 4096, 2160)

        val grid = checkNotNull(layer.tileGrid)
        assertEquals(TileSnapshot.TILE_SIZE, grid.tileSize)
        assertEquals(16, grid.tilesX)
        assertEquals(9, grid.tilesY)
        assertEquals(0, layer.loadedTileCount)
        assertEquals(0L, budget.allocatedBytes)
    }

    @Test fun repeatedEnableKeepsSameStoreAndResizeReplacesIt() {
        val budget = RenderTargetBudget()
        val layer = PaintLayer(1L, "Layer 1")
        val allocator = allocator(budget)
        layer.enableTiledStorage(allocator, 4096, 2160)
        val first = checkNotNull(layer.tileResources)

        layer.enableTiledStorage(allocator, 4096, 2160)
        assertSame(first, layer.tileResources)

        layer.enableTiledStorage(allocator, 2048, 2048)
        assertNotSame(first, layer.tileResources)
        assertEquals(8, checkNotNull(layer.tileGrid).tilesX)
        assertEquals(0L, budget.allocatedBytes)
    }

    @Test fun disableAndReleaseClearTiledStore() {
        val budget = RenderTargetBudget()
        val layer = PaintLayer(1L, "Layer 1")
        val allocator = allocator(budget)
        layer.enableTiledStorage(allocator, 4096, 2160)
        layer.disableTiledStorage()
        assertNull(layer.tileResources)

        layer.enableTiledStorage(allocator, 4096, 2160)
        layer.release(allocator)
        assertNull(layer.tileResources)
        assertFalse(layer.created)
        assertEquals(0L, budget.allocatedBytes)
    }

    @Test fun resetKeepsGridAndDropsResidentState() {
        val layer = PaintLayer(1L, "Layer 1")
        layer.enableTiledStorage(allocator(RenderTargetBudget()), 4096, 2160)
        layer.resetGlHandles()

        assertEquals(0, layer.gpuTarget.textureId)
        assertFalse(layer.created)
        assertEquals(16, checkNotNull(layer.tileGrid).tilesX)
        assertEquals(0, layer.loadedTileCount)
    }

    @Test fun tileStorePreservesDocumentFormatChoice() {
        val legacy = PaintLayer(1L, "Legacy")
        legacy.enableTiledStorage(allocator(RenderTargetBudget(), halfFloat = true), 64, 64)
        assertTrue(checkNotNull(legacy.tileResources).usesHalfFloat)

        val fresh = PaintLayer(2L, "Fresh")
        fresh.enableTiledStorage(allocator(RenderTargetBudget()), 64, 64)
        assertFalse(checkNotNull(fresh.tileResources).usesHalfFloat)
    }
}
