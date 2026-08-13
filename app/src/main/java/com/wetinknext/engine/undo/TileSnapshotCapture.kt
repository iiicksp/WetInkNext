package com.wetinknext.engine.undo
import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
object TileSnapshotCapture {
    private var reusable: ByteBuffer? = null

    /**
     * Returns the number of whole undo tiles intersecting [bounds].
     *
     * This is intentionally CPU-only: callers can use it to flag expensive
     * readback before calling [capture], without changing undo semantics.
     */
    fun countTiles(target: RenderTarget, bounds: IntArray): Int {
        require(bounds.size >= 4)

        val left = bounds[0].coerceIn(0, target.width)
        val top = bounds[1].coerceIn(0, target.height)
        val right = bounds[2].coerceIn(0, target.width)
        val bottom = bounds[3].coerceIn(0, target.height)
        if (right <= left || bottom <= top) return 0

        val firstTileX = left / TileSnapshot.TILE_SIZE
        val lastTileX = (right - 1) / TileSnapshot.TILE_SIZE
        val firstTileY = top / TileSnapshot.TILE_SIZE
        val lastTileY = (bottom - 1) / TileSnapshot.TILE_SIZE
        return (lastTileX - firstTileX + 1) *
            (lastTileY - firstTileY + 1)
    }

    /** Captures every 256 px tile intersecting [bounds] = left, top, right, bottom. */
    fun capture(target: RenderTarget, bounds: IntArray): List<RawTileSnapshot> {
        GlCheck.checkOnGlThread()
        require(target.framebufferId != 0)
        require(target.textureId != 0)
        require(bounds.size >= 4)
        val left = bounds[0].coerceIn(0, target.width)
        val top = bounds[1].coerceIn(0, target.height)
        val right = bounds[2].coerceIn(0, target.width)
        val bottom = bounds[3].coerceIn(0, target.height)
        if (right <= left || bottom <= top) return emptyList()

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "capture target=${target.textureId} fbo=${target.framebufferId} " +
                    "canvasBounds=[$left,$top,$right,$bottom] " +
                    "target=${target.width}x${target.height}",
            )
        }

        val bytesPerPixel = target.bytesPerPixel
        val glType = if (target.usesHalfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        val result = ArrayList<RawTileSnapshot>()
        target.bind()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        try {
            for (tileY in top / TileSnapshot.TILE_SIZE..(bottom - 1) / TileSnapshot.TILE_SIZE) {
                for (tileX in left / TileSnapshot.TILE_SIZE..(right - 1) / TileSnapshot.TILE_SIZE) {
                    val pixelLeft = tileX * TileSnapshot.TILE_SIZE
                    // Tile coordinates are document/canvas coordinates (Y grows down).
                    val canvasTop = tileY * TileSnapshot.TILE_SIZE
                    val pixelWidth = minOf(TileSnapshot.TILE_SIZE, target.width - pixelLeft)
                    val pixelHeight = minOf(TileSnapshot.TILE_SIZE, target.height - canvasTop)
                    // buildCanvasToFbo maps canvas y=0 to the FBO's bottom row.
                    // Canvas tile coordinates therefore already equal GL's bottom Y.
                    val glBottom = canvasTop
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "capture tile=($tileX,$tileY) " +
                                "canvas=[$pixelLeft,$canvasTop,$pixelWidth,$pixelHeight] " +
                                "gl=[$pixelLeft,$glBottom,$pixelWidth,$pixelHeight]",
                        )
                    }
                    val byteCount = pixelWidth * pixelHeight * bytesPerPixel
                    val buffer = obtainBuffer(byteCount)
                    buffer.clear()
                    GLES30.glReadPixels(
                        pixelLeft, glBottom, pixelWidth, pixelHeight,
                        GLES30.GL_RGBA, glType, buffer,
                    )
                    if (BuildConfig.DEBUG) {
                        val error = GLES30.glGetError()
                        check(error == GLES30.GL_NO_ERROR) {
                            "TileSnapshotCapture.glReadPixels failed: 0x${error.toString(16)}"
                        }
                    }
                    buffer.rewind()
                    val rawBytes = ByteArray(byteCount)
                    buffer.get(rawBytes)
                    result += RawTileSnapshot(
                        coord = TileCoord(tileX, tileY),
                        pixelLeft = pixelLeft,
                        pixelTop = canvasTop,
                        pixelWidth = pixelWidth,
                        pixelHeight = pixelHeight,
                        bytesPerPixel = bytesPerPixel,
                        rawBytes = rawBytes,
                    )
                }
            }
            return result
        } finally {
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun obtainBuffer(size: Int): ByteBuffer {
        val current = reusable
        if (current != null && current.capacity() >= size) return current
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { reusable = it }
    }

    private const val TAG = "TileUndo"
}
