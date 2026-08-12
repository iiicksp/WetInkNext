package com.wetinknext.engine.core

import com.wetinknext.engine.undo.IdentityTileCompressor
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileCompressor
import com.wetinknext.engine.undo.TileCoord
import com.wetinknext.engine.undo.UndoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UndoCompressionPipelineTest {
    @Test
    fun synchronousFallbackUsesTheSameOrderedHistoryPath() {
        val manager = UndoManager()
        val pipeline = UndoCompressionPipeline(
            requestRender = {},
            compressor = IdentityTileCompressor,
            maxPendingJobs = 1,
        )

        pipeline.enqueue(
            undoManager = manager,
            layerId = 7L,
            beforeRaw = listOf(raw(byteArrayOf(1, 2, 3, 4))),
            afterRaw = listOf(raw(byteArrayOf(5, 6, 7, 8))),
        )

        assertEquals(0, pipeline.pendingCount)
        assertEquals(1, manager.undoCount)
        assertEquals(0, manager.redoCount)
        assertTrue(manager.canUndo)
        pipeline.shutdown()
    }

    @Test
    fun laterWorkerResultWaitsForTheEarlierSequence() {
        val firstCompressionStarted = CountDownLatch(1)
        val allowFirstCompression = CountDownLatch(1)
        val secondResultReady = CountDownLatch(1)
        val allResultsReady = CountDownLatch(2)
        val resultCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val compressor = object : TileCompressor {
            override fun compress(data: ByteArray): ByteArray {
                if (data.first() == 0.toByte()) {
                    firstCompressionStarted.countDown()
                    check(allowFirstCompression.await(2, TimeUnit.SECONDS))
                }
                return data
            }

            override fun decompress(data: ByteArray, expectedSize: Int): ByteArray = data
        }
        val manager = UndoManager()
        val pipeline = UndoCompressionPipeline(
            requestRender = {
                if (resultCount.incrementAndGet() == 1) secondResultReady.countDown()
                allResultsReady.countDown()
            },
            compressor = compressor,
            executorFactory = { executor },
        )

        pipeline.enqueue(
            undoManager = manager,
            layerId = 1L,
            beforeRaw = listOf(raw(byteArrayOf(0, 0, 0, 0))),
            afterRaw = listOf(raw(byteArrayOf(2, 0, 0, 0))),
        )
        assertTrue(firstCompressionStarted.await(1, TimeUnit.SECONDS))

        pipeline.enqueue(
            undoManager = manager,
            layerId = 2L,
            beforeRaw = listOf(raw(byteArrayOf(1, 0, 0, 0))),
            afterRaw = listOf(raw(byteArrayOf(3, 0, 0, 0))),
        )
        assertTrue(secondResultReady.await(1, TimeUnit.SECONDS))

        assertFalse(pipeline.process(manager))
        assertEquals(0, manager.undoCount)
        assertEquals(2, pipeline.pendingCount)

        allowFirstCompression.countDown()
        assertTrue(allResultsReady.await(1, TimeUnit.SECONDS))

        assertTrue(pipeline.process(manager))
        assertEquals(2, manager.undoCount)
        assertEquals(0, pipeline.pendingCount)
        pipeline.shutdown()
    }

    private fun raw(bytes: ByteArray) = RawTileSnapshot(
        coord = TileCoord(0, 0),
        pixelLeft = 0,
        pixelTop = 0,
        pixelWidth = 1,
        pixelHeight = 1,
        bytesPerPixel = 4,
        rawBytes = bytes,
    )
}
