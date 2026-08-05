package com.wetinknext.engine.brush

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.ShaderLib
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DabRenderer(private val maxDabs:Int){private var program:GlProgram?=null;private var vao=0;private var quad=0;private var instances=0;private var matrix=-1;private var color=-1
 fun create(){release();program=GlProgram(ShaderLib.dabVertex,ShaderLib.dabFragment);program!!.use();matrix=GLES30.glGetUniformLocation(program!!.id,"uCanvasToClip");color=GLES30.glGetUniformLocation(program!!.id,"uColor");val v=floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f);val b=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(v).position(0);val ids=IntArray(1);GLES30.glGenVertexArrays(1,ids,0);vao=ids[0];GLES30.glGenBuffers(1,ids,0);quad=ids[0];GLES30.glGenBuffers(1,ids,0);instances=ids[0];GLES30.glBindVertexArray(vao);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,quad);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,v.size*4,b,GLES30.GL_STATIC_DRAW);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,0,0);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,instances);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,maxDabs*20,null,GLES30.GL_DYNAMIC_DRAW);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(1,4,GLES30.GL_FLOAT,false,20,0);GLES30.glVertexAttribDivisor(1,1);GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(2,1,GLES30.GL_FLOAT,false,20,16);GLES30.glVertexAttribDivisor(2,1);GLES30.glBindVertexArray(0)}
 fun draw(d:DabBuffer,m:FloatArray){if(d.count==0)return;program?.use()?:return;GLES30.glUniformMatrix4fv(matrix,1,false,m,0);GLES30.glUniform3f(color,.12f,.25f,.95f);d.prepareForUpload();GLES30.glBindVertexArray(vao);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,instances);GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,d.count*20,d.floats);GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP,0,4,d.count);GLES30.glBindVertexArray(0)}
 fun release(){program?.release();program=null;if(instances!=0)GLES30.glDeleteBuffers(1,intArrayOf(instances),0);if(quad!=0)GLES30.glDeleteBuffers(1,intArrayOf(quad),0);if(vao!=0)GLES30.glDeleteVertexArrays(1,intArrayOf(vao),0);vao=0;quad=0;instances=0}
}
