package com.wetinknext.engine.selection

import android.opengl.GLES30
import com.wetinknext.engine.gl.GlCheck
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Canvas-sized selection mask used by the lasso and transform preview. */
class SelectionMask(val width: Int, val height: Int) {
    private val pixels = ByteArray(width * height)
    private var textureId = 0
    private var textureDirty = false
    private var contourStarted = false
    private var contourStartX = 0f
    private var contourStartY = 0f
    private var contourEndX = 0f
    private var contourEndY = 0f

    val selectionBounds = IntArray(4)
    var isEmpty: Boolean = true
        private set

    fun clear() {
        pixels.fill(0); textureDirty = true; isEmpty = true; contourStarted = false
        selectionBounds[0] = 0; selectionBounds[1] = 0; selectionBounds[2] = 0; selectionBounds[3] = 0
    }

    fun strokeCapsule(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        if (!contourStarted) { contourStartX=x0; contourStartY=y0; contourStarted=true }
        contourEndX=x1; contourEndY=y1
        val steps = max(1, (kotlin.math.hypot(x1-x0,y1-y0)/1.5f).toInt())
        for (step in 0..steps) { val t=step.toFloat()/steps; stampCircle(x0+(x1-x0)*t,y0+(y1-y0)*t,radius) }
    }

    fun stampCircle(cx: Float, cy: Float, radius: Float) {
        val r=radius.toInt().coerceAtLeast(1)
        val minX=(cx-r).toInt().coerceIn(0,width-1); val maxX=(cx+r).toInt().coerceIn(0,width-1)
        val minY=(cy-r).toInt().coerceIn(0,height-1); val maxY=(cy+r).toInt().coerceIn(0,height-1)
        val r2=radius*radius
        for(y in minY..maxY){val dy=y-cy;for(x in minX..maxX){val dx=x-cx;if(dx*dx+dy*dy<=r2)mark(x,y)}}
    }

    fun fillRect(x0:Int,y0:Int,x1:Int,y1:Int){val left=min(x0,x1).coerceIn(0,width-1);val right=max(x0,x1).coerceIn(0,width-1);val top=min(y0,y1).coerceIn(0,height-1);val bottom=max(y0,y1).coerceIn(0,height-1);for(y in top..bottom)for(x in left..right)mark(x,y)}
    fun fillEllipse(x0:Int,y0:Int,x1:Int,y1:Int){val left=min(x0,x1);val right=max(x0,x1);val top=min(y0,y1);val bottom=max(y0,y1);val cx=(left+right)/2f;val cy=(top+bottom)/2f;val rx=max(1,(right-left)/2);val ry=max(1,(bottom-top)/2);val rx2=rx.toFloat()*rx;val ry2=ry.toFloat()*ry;for(y in top.coerceAtLeast(0)..bottom.coerceAtMost(height-1)){val dy=y-cy;for(x in left.coerceAtLeast(0)..right.coerceAtMost(width-1)){val dx=x-cx;if(dx*dx/rx2+dy*dy/ry2<=1f)mark(x,y)}}}

    /** Closes the freehand path, then fills its even-odd interior. */
    fun fillContour() {
        if (contourStarted) {
            val dx=contourEndX-contourStartX; val dy=contourEndY-contourStartY
            if (kotlin.math.hypot(dx,dy)>0.5f) strokeCapsule(contourEndX,contourEndY,contourStartX,contourStartY,1.5f)
        }
        val edges=IntArray(width)
        for(y in 0 until height){val row=y*width;var n=0;var x=0;while(x<width){if(pixels[row+x].toInt() and 0xFF!=0){edges[n++]=x;while(x<width&&pixels[row+x].toInt() and 0xFF!=0)x++}else x++};var i=0;while(i+1<n){for(fillX in edges[i]..edges[i+1])mark(fillX,y);i+=2}}
        contourStarted=false
    }

    private fun mark(x:Int,y:Int){if(x<0||y<0||x>=width||y>=height)return;val index=y*width+x;if(pixels[index]==0.toByte()){pixels[index]=255.toByte();textureDirty=true;isEmpty=false;include(x,y)}}
    private fun include(x:Int,y:Int){if(selectionBounds[2]==0){selectionBounds[0]=x;selectionBounds[1]=y;selectionBounds[2]=x+1;selectionBounds[3]=y+1;return};selectionBounds[0]=min(selectionBounds[0],x);selectionBounds[1]=min(selectionBounds[1],y);selectionBounds[2]=max(selectionBounds[2],x+1);selectionBounds[3]=max(selectionBounds[3],y+1)}
    fun contourBounds(points:List<FloatArray>):FloatArray?{if(points.isEmpty())return null;var left=Float.MAX_VALUE;var top=Float.MAX_VALUE;var right=-Float.MAX_VALUE;var bottom=-Float.MAX_VALUE;for(p in points){left=min(left,p[0]);top=min(top,p[1]);right=max(right,p[0]);bottom=max(bottom,p[1])};return floatArrayOf(left,top,right,bottom)}

    fun uploadIfDirty():Boolean{if(!textureDirty)return textureId!=0;ensureTexture();if(textureId==0)return false;GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textureId);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,width,height,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder()));GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);textureDirty=false;return true}
    fun texture():Int=textureId
    private fun ensureTexture(){if(textureId!=0)return;val ids=IntArray(1);GLES30.glGenTextures(1,ids,0);textureId=ids[0];GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textureId);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_R8,width,height,0,GLES30.GL_RED,GLES30.GL_UNSIGNED_BYTE,null);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)}
    fun release(){if(textureId!=0){GLES30.glDeleteTextures(1,intArrayOf(textureId),0);textureId=0};GlCheck.noError("SelectionMask.release")}
}
