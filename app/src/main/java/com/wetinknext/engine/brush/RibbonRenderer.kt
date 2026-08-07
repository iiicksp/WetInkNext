package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RibbonRenderer(private val maxVertices: Int = DEFAULT_MAX_VERTICES) {
    private var program: GlProgram? = null; private var vao = 0; private var vbo = 0; private var ebo = 0
    private var uMatrix = -1; private var uColor = -1; private var uFlow = -1; private var uAa = -1; private var uNoAa = -1
    private val vertexData = ByteBuffer.allocateDirect(maxVertices * RibbonShader.VERTEX_STRIDE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val indexData = ByteBuffer.allocateDirect(maxVertices * 3 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).asIntBuffer()
    fun create() {
        release(); program = GlProgram(ShaderLib.ribbonVertex, ShaderLib.ribbonFragment); val p = checkNotNull(program); p.use()
        uMatrix = GLES30.glGetUniformLocation(p.id, "uCanvasToClip"); uColor = GLES30.glGetUniformLocation(p.id, "uColorLinear"); uFlow = GLES30.glGetUniformLocation(p.id, "uFlow"); uAa = GLES30.glGetUniformLocation(p.id, "uAntiAliasLevel"); uNoAa = GLES30.glGetUniformLocation(p.id, "uNoAntialias")
        val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]; GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]; GLES30.glGenBuffers(1, ids, 0); ebo = ids[0]
        GLES30.glBindVertexArray(vao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxVertices * RibbonShader.VERTEX_STRIDE_BYTES, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, maxVertices * 3 * Int.SIZE_BYTES, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, RibbonShader.VERTEX_STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, RibbonShader.VERTEX_STRIDE_BYTES, 8)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, RibbonShader.VERTEX_STRIDE_BYTES, 12); GLES30.glBindVertexArray(0)
    }
    fun draw(target: RenderTarget, width: Int, height: Int, canvasToFbo: FloatArray, mesh: RibbonMesh, color: FloatArray, flow: Float, antiAliasLevel: Int, noAntialias: Boolean) {
        val p = program ?: return; if (mesh.isEmpty || mesh.vertexCount > maxVertices || mesh.indices.size > maxVertices * 3) return
        vertexData.clear(); for (i in 0 until mesh.vertexCount) { vertexData.put(mesh.vertices[i*2]); vertexData.put(mesh.vertices[i*2+1]); vertexData.put(mesh.coverage[i]); vertexData.put(mesh.alpha[i]) }; vertexData.flip()
        indexData.clear(); mesh.indices.forEach { indexData.put(it) }; indexData.flip()
        target.bind(); GLES30.glViewport(0,0,width,height); GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_ALPHA); p.use()
        GLES30.glUniformMatrix4fv(uMatrix,1,false,canvasToFbo,0); GLES30.glUniform3f(uColor,color[0],color[1],color[2]); GLES30.glUniform1f(uFlow,flow.coerceIn(0f,1f)); GLES30.glUniform1i(uAa,antiAliasLevel.coerceIn(0,3)); GLES30.glUniform1i(uNoAa,if(noAntialias)1 else 0)
        GLES30.glBindVertexArray(vao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo); GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,mesh.vertexCount*RibbonShader.VERTEX_STRIDE_BYTES,vertexData); GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,ebo); GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER,0,mesh.indices.size*Int.SIZE_BYTES,indexData); GLES30.glDrawElements(GLES30.GL_TRIANGLES,mesh.indices.size,GLES30.GL_UNSIGNED_INT,0); GLES30.glBindVertexArray(0); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
    }
    fun release(){ program?.release();program=null;if(vbo!=0)GLES30.glDeleteBuffers(1,intArrayOf(vbo),0);if(ebo!=0)GLES30.glDeleteBuffers(1,intArrayOf(ebo),0);if(vao!=0)GLES30.glDeleteVertexArrays(1,intArrayOf(vao),0);vao=0;vbo=0;ebo=0 }
    companion object { const val DEFAULT_MAX_VERTICES = 65_536 }
}
