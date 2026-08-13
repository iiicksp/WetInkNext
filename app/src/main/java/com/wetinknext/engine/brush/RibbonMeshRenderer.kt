package com.wetinknext.engine.brush

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** GPU half of [RibbonGeometryBuilder]. One draw call renders a welded mesh. */
class RibbonMeshRenderer {
    private var program: GlProgram? = null
    private var vaoId = 0
    private var vertexBufferId = 0
    private var indexBufferId = 0

    private var uCanvasToClip = -1
    private var uColorLinear = -1
    private var uCoverageOnly = -1
    private var uFlow = -1

    private var uGrainActive = -1
    private var uGrainTex = -1
    private var uGrainScale = -1
    private var uGrainScreenSpace = -1
    private var uScreenSize = -1
    private var uTextureContrast = -1
    private var uTextureDepth = -1

    private var vertexUpload: FloatBuffer? = null
    private var indexUpload: IntBuffer? = null
    private var vertexCapacityBytes = 0
    private var indexCapacityBytes = 0

    private var grainTextureId = 0
    private var grainScale = 1f
    private var grainScreenSpace = false
    private var textureDepth = 1f
    private var textureContrast = 1f
    private var screenWidth = 1080f
    private var screenHeight = 1920f

    fun create() {
        GlCheck.checkOnGlThread()
        release()
        // This mesh shader carries interpolated coverage for the AA fringe.
        program = GlProgram(ShaderLib.ribbonMeshVertex, ShaderLib.ribbonMeshFragment)
        val p = checkNotNull(program)
        p.use()

        uCanvasToClip = GLES30.glGetUniformLocation(p.id, "uCanvasToClip")
        uColorLinear = GLES30.glGetUniformLocation(p.id, "uColorLinear")
        uFlow = GLES30.glGetUniformLocation(p.id, "uFlow")
        uCoverageOnly = GLES30.glGetUniformLocation(p.id, "uCoverageOnly")

        uGrainActive = GLES30.glGetUniformLocation(p.id, "uGrainActive")
        uGrainTex = GLES30.glGetUniformLocation(p.id, "uGrainTex")
        uGrainScale = GLES30.glGetUniformLocation(p.id, "uGrainScale")
        uGrainScreenSpace = GLES30.glGetUniformLocation(p.id, "uGrainScreenSpace")
        uScreenSize = GLES30.glGetUniformLocation(p.id, "uScreenSize")
        uTextureContrast = GLES30.glGetUniformLocation(p.id, "uTextureContrast")
        uTextureDepth = GLES30.glGetUniformLocation(p.id, "uTextureDepth")

        check(uCanvasToClip >= 0) { "Ribbon uniform uCanvasToClip is missing" }
        check(uColorLinear >= 0) { "Ribbon uniform uColorLinear is missing" }
        check(uFlow >= 0) { "Ribbon uniform uFlow is missing" }
        check(uCoverageOnly >= 0) { "Ribbon uniform uCoverageOnly is missing" }

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vaoId = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        vertexBufferId = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        indexBufferId = ids[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(
            0,
            2,
            GLES30.GL_FLOAT,
            false,
            FLOATS_PER_VERTEX * Float.SIZE_BYTES,
            0,
        )
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            1,
            GLES30.GL_FLOAT,
            false,
            FLOATS_PER_VERTEX * Float.SIZE_BYTES,
            2 * Float.SIZE_BYTES,
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        GLES30.glBindVertexArray(0)

        GlCheck.noError("RibbonMeshRenderer create")
    }

    fun setGrainTexture(
        textureId: Int,
        scale: Float,
        depth: Float,
        contrast: Float,
    ) {
        grainTextureId = textureId
        grainScale = scale.coerceAtLeast(0.0001f)
        textureDepth = depth.coerceIn(0f, 1f)
        textureContrast = contrast.coerceIn(0f, 2f)
    }

    fun clearGrainTexture() {
        grainTextureId = 0
        grainScale = 1f
        textureDepth = 1f
        textureContrast = 1f
    }

    fun setScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    fun setGrainScreenSpace(screenSpace: Boolean) {
        grainScreenSpace = screenSpace
    }
    fun draw(
        target: RenderTarget,
        width: Int,
        height: Int,
        matrix: FloatArray,
        mesh: RibbonGeometryBuilder.Mesh,
        color: FloatArray,
        flow: Float,
        coverageOnly: Boolean,
    ): Boolean {
        val vertexFloats = mesh.vertices.size
        val indexCount = mesh.indices.size
        if (indexCount == 0) return false
        if (
            vertexFloats > MAX_VERTICES * FLOATS_PER_VERTEX ||
            indexCount > MAX_INDICES
        ) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "mesh too large: verts=${vertexFloats / FLOATS_PER_VERTEX} idx=$indexCount")
            }
            return false
        }

        val p = program ?: return false
        target.bind()
        GLES30.glViewport(0, 0, width, height)
        p.use()
        GLES30.glUniformMatrix4fv(uCanvasToClip, 1, false, matrix, 0)
        GLES30.glUniform3fv(uColorLinear, 1, color, 0)
        GLES30.glUniform1i(uCoverageOnly, if (coverageOnly) 1 else 0)
        GLES30.glUniform1f(uFlow, flow.coerceIn(0f, 1f))

        GLES30.glUniform1i(uGrainActive, if (grainTextureId != 0) 1 else 0)
        GLES30.glUniform1f(uGrainScale, grainScale)
        GLES30.glUniform1i(uGrainScreenSpace, if (grainScreenSpace) 1 else 0)
        GLES30.glUniform2f(uScreenSize, screenWidth, screenHeight)
        GLES30.glUniform1f(uTextureDepth, textureDepth)
        GLES30.glUniform1f(uTextureContrast, textureContrast)

        if (grainTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTextureId)
            GLES30.glUniform1i(uGrainTex, 2)
        }

        val vertexBuffer = obtainVertexBuffer(vertexFloats)
        vertexBuffer.clear()
        vertexBuffer.put(mesh.vertices)
        vertexBuffer.flip()
        val indexBuffer = obtainIndexBuffer(indexCount)
        indexBuffer.clear()
        indexBuffer.put(mesh.indices)
        indexBuffer.flip()

        GLES30.glBindVertexArray(vaoId)
        uploadVertices(vertexBuffer, vertexFloats)
        uploadIndices(indexBuffer, indexCount)
        if (coverageOnly) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_MAX)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_BLEND)
        return true
    }

    private fun uploadVertices(source: FloatBuffer, floatCount: Int) {
        val bytes = floatCount * Float.SIZE_BYTES
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
        if (bytes > vertexCapacityBytes) {
            vertexCapacityBytes = maxOf(bytes, vertexCapacityBytes * 2)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                vertexCapacityBytes,
                null,
                GLES30.GL_DYNAMIC_DRAW,
            )
        }
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, source)
    }

    private fun uploadIndices(source: IntBuffer, indexCount: Int) {
        val bytes = indexCount * Int.SIZE_BYTES
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBufferId)
        if (bytes > indexCapacityBytes) {
            indexCapacityBytes = maxOf(bytes, indexCapacityBytes * 2)
            GLES30.glBufferData(
                GLES30.GL_ELEMENT_ARRAY_BUFFER,
                indexCapacityBytes,
                null,
                GLES30.GL_DYNAMIC_DRAW,
            )
        }
        GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0, bytes, source)
    }
    private fun obtainVertexBuffer(size: Int): FloatBuffer {
        val current = vertexUpload
        if (current != null && current.capacity() >= size) return current
        val capacity = maxOf(size, (current?.capacity() ?: 256) * 2)
        return ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().also { vertexUpload = it }
    }
    private fun obtainIndexBuffer(size: Int): IntBuffer {
        val current = indexUpload
        if (current != null && current.capacity() >= size) return current
        val capacity = maxOf(size, (current?.capacity() ?: 256) * 2)
        return ByteBuffer.allocateDirect(capacity * Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).asIntBuffer().also { indexUpload = it }
    }
    fun release() {
        GlCheck.checkOnGlThread()

        if (vaoId != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        }
        if (vertexBufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
        }
        if (indexBufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(indexBufferId), 0)
        }

        vaoId = 0
        vertexBufferId = 0
        indexBufferId = 0
        vertexCapacityBytes = 0
        indexCapacityBytes = 0
        vertexUpload = null
        indexUpload = null
        grainTextureId = 0
        program?.release()
        program = null
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 3
        const val MAX_VERTICES = 65_536
        const val MAX_INDICES = 196_608
        const val TAG = "RibbonMesh"
    }
}
