package com.wetinknext.engine.canvas

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.undo.TileCoord

/** Copies an existing full layer target into resident tile targets. */
class LayerTileSeeder {
    fun seed(source: RenderTarget, grid: TileGrid, resources: LayerTileResources): Boolean {
        GlCheck.checkOnGlThread()
        if (source.framebufferId == 0 || source.width != grid.canvasWidth || source.height != grid.canvasHeight) return false
        val createdThisCall = ArrayList<TileCoord>()
        val previousDraw = IntArray(1)
        val previousRead = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, previousDraw, 0)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, previousRead, 0)
        var succeeded = false
        try {
            for (coord in grid.allTiles()) {
                val wasResident = resources.peek(coord) != null
                val tile = resources.obtain(coord) ?: run {
                    if (BuildConfig.DEBUG) Log.w(TAG, "GPU budget refused seed tile (${coord.tx},${coord.ty}); created=${createdThisCall.size}")
                    return false
                }
                if (!wasResident) createdThisCall += coord
                val left = grid.tileLeft(coord.tx)
                val top = grid.tileTop(coord.ty)
                val width = grid.tileWidth(coord.tx)
                val height = grid.tileHeight(coord.ty)
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebufferId)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, tile.framebufferId)
                GLES30.glBlitFramebuffer(left, top, left + width, top + height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
                GlCheck.noError("seed tile")
            }
            succeeded = true
            return true
        } catch (error: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "tile seeding failed", error)
            return false
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, previousRead[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, previousDraw[0])
            if (!succeeded) createdThisCall.forEach(resources::release)
        }
    }
    private companion object { const val TAG = "TileSeeder" }
}
