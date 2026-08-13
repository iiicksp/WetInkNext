package com.wetinknext.engine.undo

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Asynchronous tile readback for undo.  Commands are issued on the GL thread,
 * but no CPU data is touched until the corresponding GPU fence is signalled.
 *
 * A capture is split into modest PBOs instead of creating one buffer per tile:
 * this keeps a 4K clear from causing hundreds of synchronous readbacks.
 */
class PboTileReadbackQueue(
    private val requestRender: () -> Unit,
) {
    class Capture internal constructor(
        internal val batches: MutableList<Batch>,
        internal val snapshots: ArrayList<RawTileSnapshot>,
    ) {
        internal var nextBatch = 0
        val isComplete: Boolean get() = nextBatch >= batches.size
    }

    internal class Batch(
        val bufferId: Int,
        val byteCount: Int,
        val tiles: List<TileRead>,
        var fence: Long,
    )

    internal class TileRead(
        val coord: TileCoord,
        val pixelLeft: Int,
        val pixelTop: Int,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val bytesPerPixel: Int,
        val offset: Int,
        val byteCount: Int,
    )

    private val captures = ArrayDeque<Capture>()

    val pendingCount: Int get() = captures.size

    /** Issues GL reads now, but returns before the GPU work is complete. */
    fun issue(target: RenderTarget, bounds: IntArray): Capture {
        GlCheck.checkOnGlThread()
        require(target.framebufferId != 0 && target.textureId != 0)
        require(bounds.size >= 4)

        val tiles = enumerateTiles(target, bounds)
        val capture = Capture(mutableListOf(), ArrayList(tiles.size))
        if (tiles.isEmpty()) return capture

        val glType = if (target.usesHalfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        target.bind()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        try {
            var cursor = 0
            while (cursor < tiles.size) {
                val group = ArrayList<TileRead>()
                var bytes = 0
                while (cursor < tiles.size) {
                    val tile = tiles[cursor]
                    if (group.isNotEmpty() && bytes + tile.byteCount > MAX_PBO_BYTES) break
                    group += tile.copyWithOffset(bytes)
                    bytes += tile.byteCount
                    cursor++
                }

                val ids = IntArray(1)
                GLES30.glGenBuffers(1, ids, 0)
                check(ids[0] != 0) { "Unable to allocate PBO for undo readback" }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, ids[0])
                GLES30.glBufferData(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    bytes,
                    null,
                    GLES30.GL_STREAM_READ,
                )
                group.forEach { tile ->
                    GLES30.glReadPixels(
                        tile.pixelLeft,
                        tile.pixelTop,
                        tile.pixelWidth,
                        tile.pixelHeight,
                        GLES30.GL_RGBA,
                        glType,
                        tile.offset,
                    )
                }
                val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                check(fence != 0L) { "Unable to create undo readback fence" }
                capture.batches += Batch(ids[0], bytes, group, fence)
            }
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
        captures += capture
        requestRender()
        return capture
    }

    /**
     * Maps at most [MAX_MAP_BYTES_PER_FRAME] bytes. Returns every capture that
     * completed in order; unfinished captures remain GPU-owned.
     */
    fun poll(): List<Capture> {
        GlCheck.checkOnGlThread()
        if (captures.isEmpty()) return emptyList()

        var remaining = MAX_MAP_BYTES_PER_FRAME
        val complete = ArrayList<Capture>()
        while (captures.isNotEmpty()) {
            val capture = captures.first()
            if (capture.isComplete) {
                captures.removeFirst()
                complete += capture
                continue
            }
            val batch = capture.batches[capture.nextBatch]
            if (batch.byteCount > remaining || !isSignalled(batch.fence)) break
            mapBatch(batch, capture.snapshots)
            capture.nextBatch++
            remaining -= batch.byteCount
            if (capture.isComplete) {
                captures.removeFirst()
                complete += capture
            }
            if (remaining <= 0) break
        }
        if (captures.isNotEmpty()) requestRender()
        return complete
    }

    fun release() {
        GlCheck.checkOnGlThread()
        captures.forEach { capture ->
            capture.batches.forEach(::releaseBatch)
        }
        captures.clear()
    }

    private fun isSignalled(fence: Long): Boolean {
        val status = IntArray(1)
        GLES30.glGetSynciv(
            fence,
            GLES30.GL_SYNC_STATUS,
            1,
            null,
            0,
            status,
            0,
        )
        return status[0] == GLES30.GL_SIGNALED
    }

    private fun mapBatch(batch: Batch, output: MutableList<RawTileSnapshot>) {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, batch.bufferId)
        try {
            val mapped = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER,
                0,
                batch.byteCount,
                GLES30.GL_MAP_READ_BIT,
            ) as? ByteBuffer ?: error("Unable to map PBO undo readback")
            mapped.order(ByteOrder.nativeOrder())
            batch.tiles.forEach { tile ->
                val bytes = ByteArray(tile.byteCount)
                mapped.position(tile.offset)
                mapped.get(bytes)
                output += RawTileSnapshot(
                    coord = tile.coord,
                    pixelLeft = tile.pixelLeft,
                    pixelTop = tile.pixelTop,
                    pixelWidth = tile.pixelWidth,
                    pixelHeight = tile.pixelHeight,
                    bytesPerPixel = tile.bytesPerPixel,
                    rawBytes = bytes,
                )
            }
            check(GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                "PBO undo readback was invalidated"
            }
        } finally {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            releaseBatch(batch)
        }
    }

    private fun releaseBatch(batch: Batch) {
        if (batch.fence != 0L) {
            GLES30.glDeleteSync(batch.fence)
            batch.fence = 0L
        }
        if (batch.bufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(batch.bufferId), 0)
    }

    private fun enumerateTiles(target: RenderTarget, bounds: IntArray): List<TileRead> {
        val left = bounds[0].coerceIn(0, target.width)
        val top = bounds[1].coerceIn(0, target.height)
        val right = bounds[2].coerceIn(0, target.width)
        val bottom = bounds[3].coerceIn(0, target.height)
        if (right <= left || bottom <= top) return emptyList()
        val bytesPerPixel = target.bytesPerPixel
        val result = ArrayList<TileRead>()
        for (tileY in top / TileSnapshot.TILE_SIZE..(bottom - 1) / TileSnapshot.TILE_SIZE) {
            for (tileX in left / TileSnapshot.TILE_SIZE..(right - 1) / TileSnapshot.TILE_SIZE) {
                val pixelLeft = tileX * TileSnapshot.TILE_SIZE
                val pixelTop = tileY * TileSnapshot.TILE_SIZE
                val pixelWidth = minOf(TileSnapshot.TILE_SIZE, target.width - pixelLeft)
                val pixelHeight = minOf(TileSnapshot.TILE_SIZE, target.height - pixelTop)
                result += TileRead(
                    coord = TileCoord(tileX, tileY),
                    pixelLeft = pixelLeft,
                    pixelTop = pixelTop,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    bytesPerPixel = bytesPerPixel,
                    offset = 0,
                    byteCount = pixelWidth * pixelHeight * bytesPerPixel,
                )
            }
        }
        return result
    }

    private fun TileRead.copyWithOffset(offset: Int) = TileRead(
        coord, pixelLeft, pixelTop, pixelWidth, pixelHeight, bytesPerPixel, offset, byteCount,
    )

    private companion object {
        const val MAX_PBO_BYTES = 4 * 1024 * 1024
        const val MAX_MAP_BYTES_PER_FRAME = 4 * 1024 * 1024
        const val TAG = "PboReadback"
    }
}
