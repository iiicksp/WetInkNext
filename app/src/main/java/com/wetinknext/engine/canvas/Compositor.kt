package com.wetinknext.engine.canvas

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.brush.StrokeRenderMode
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.GlProgram
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.gl.ShaderLib
import com.wetinknext.engine.gl.TileQuadGeometry

/** Presents visible layers, using resident tile textures for TILED layers. */
class Compositor {
    private var program: GlProgram? = null
    private var uCanvasToClip=-1; private var uCanvasSize=-1; private var uUvRect=-1
    private var uLayerTex=-1; private var uStrokeTex=-1; private var uScreenStrokeTex=-1; private var uStrokeCoverageTex=-1
    private var uStrokeActive=-1; private var uStrokeIsScreenSpace=-1; private var uStrokeMode=-1; private var uStrokeErase=-1
    private var uOpacity=-1; private var uStrokeOpacity=-1; private var uStrokeColorLinear=-1
    private val tileGeometry = TileQuadGeometry()

    fun create() {
        GlCheck.checkOnGlThread(); release()
        program=GlProgram(ShaderLib.compositorVertex,ShaderLib.compositorFragment)
        val p=checkNotNull(program); p.use()
        uCanvasToClip=GLES30.glGetUniformLocation(p.id,"uCanvasToClip"); uCanvasSize=GLES30.glGetUniformLocation(p.id,"uCanvasSize"); uUvRect=GLES30.glGetUniformLocation(p.id,"uUvRect")
        uLayerTex=GLES30.glGetUniformLocation(p.id,"uLayerTex"); uStrokeTex=GLES30.glGetUniformLocation(p.id,"uStrokeTex"); uScreenStrokeTex=GLES30.glGetUniformLocation(p.id,"uScreenStrokeTex"); uStrokeCoverageTex=GLES30.glGetUniformLocation(p.id,"uStrokeCoverageTex")
        uStrokeActive=GLES30.glGetUniformLocation(p.id,"uStrokeActive"); uStrokeIsScreenSpace=GLES30.glGetUniformLocation(p.id,"uStrokeIsScreenSpace"); uStrokeMode=GLES30.glGetUniformLocation(p.id,"uStrokeMode"); uStrokeErase=GLES30.glGetUniformLocation(p.id,"uStrokeErase")
        uOpacity=GLES30.glGetUniformLocation(p.id,"uOpacity"); uStrokeOpacity=GLES30.glGetUniformLocation(p.id,"uStrokeOpacity"); uStrokeColorLinear=GLES30.glGetUniformLocation(p.id,"uStrokeColorLinear")
        check(listOf(uCanvasToClip,uCanvasSize,uUvRect,uLayerTex,uStrokeTex,uScreenStrokeTex,uStrokeCoverageTex,uStrokeActive,uStrokeIsScreenSpace,uStrokeMode,uStrokeErase,uOpacity,uStrokeOpacity,uStrokeColorLinear).all{it>=0})
        tileGeometry.create(); GlCheck.noError("Compositor create")
    }

    fun render(destination: RenderTarget?=null, geometry: CanvasGeometry, layers: LayerStack, activeLayerId: Long, strokeTextureId: Int, strokeCoverageTextureId: Int=0, strokeIsScreenSpace:Boolean=false, strokeMode:StrokeRenderMode=StrokeRenderMode.NORMAL_BUILDUP, strokeErase:Boolean, strokeColorLinear:FloatArray=DEFAULT_STROKE_COLOR, canvasToClip:FloatArray, strokeOpacity:Float, firstLayerIndex:Int=0, lastLayerExclusive:Int=Int.MAX_VALUE, activeLayerTextureId:Int=0, onionTextureId:Int=0) {
        val p=program?:return; destination?.bind(); p.use(); GLES30.glUniformMatrix4fv(uCanvasToClip,1,false,canvasToClip,0); GLES30.glUniform2f(uCanvasSize,layers.canvasWidth.toFloat(),layers.canvasHeight.toFloat()); GLES30.glUniform4f(uUvRect,0f,0f,1f,1f)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_ALPHA)
        if(onionTextureId!=0){bindLayer(onionTextureId); setNoStroke(1f); geometry.draw()}
        var drawnTiles=0
        for((index,layer) in layers.allLayers().withIndex()){
            if(index<firstLayerIndex||index>=lastLayerExclusive||!layer.created||!layer.isVisible)continue
            val hasStroke=layer.id==activeLayerId&&(strokeTextureId!=0||strokeCoverageTextureId!=0)
            if(layer.isTiled){
                val grid=layer.tileGrid
                val resources=layer.tileResources
                if(grid!=null&&resources!=null){
                    for(coord in resources.loadedCoords){
                        val tile=resources.peek(coord)?:continue
                        bindLayer(tile.textureId); GLES30.glUniform4f(uUvRect,0f,0f,1f,1f)
                        setStroke(hasStroke,strokeTextureId,strokeCoverageTextureId,strokeIsScreenSpace,strokeMode,strokeErase,layer.opacity,strokeOpacity,strokeColorLinear)
                        val b=grid.tileBounds(coord); tileGeometry.draw(b[0],b[1],b[2],b[3]); drawnTiles++
                    }
                }
            } else {
                bindLayer(if(activeLayerTextureId!=0&&layer.id==activeLayerId)activeLayerTextureId else layer.target.textureId)
                setStroke(hasStroke,strokeTextureId,strokeCoverageTextureId,strokeIsScreenSpace,strokeMode,strokeErase,layer.opacity,strokeOpacity,strokeColorLinear); geometry.draw()
            }
        }
        if(BuildConfig.DEBUG&&drawnTiles>0)Log.d(TAG,"tiled compositor tiles=$drawnTiles")
        GLES30.glDisable(GLES30.GL_BLEND); clearTextures()
    }

    private fun bindLayer(id:Int){GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id);GLES30.glUniform1i(uLayerTex,0)}
    private fun setNoStroke(opacity:Float){GLES30.glUniform1i(uStrokeActive,0);GLES30.glUniform1f(uOpacity,opacity);GLES30.glUniform1f(uStrokeOpacity,1f);GLES30.glUniform1i(uStrokeMode,0);GLES30.glUniform1i(uStrokeErase,0);GLES30.glUniform1i(uStrokeIsScreenSpace,0)}
    private fun setStroke(active:Boolean,stroke:Int,coverage:Int,screen:Boolean,mode:StrokeRenderMode,erase:Boolean,opacity:Float,strokeOpacity:Float,color:FloatArray){
        setNoStroke(opacity); GLES30.glUniform1i(uStrokeActive,if(active)1 else 0); GLES30.glUniform1i(uStrokeIsScreenSpace,if(active&&screen)1 else 0); GLES30.glUniform1i(uStrokeErase,if(active&&erase)1 else 0); GLES30.glUniform1f(uStrokeOpacity,strokeOpacity.coerceIn(0f,1f)); GLES30.glUniform3f(uStrokeColorLinear,color[0],color[1],color[2])
        if(active){val unit=if(screen)GLES30.GL_TEXTURE2 else GLES30.GL_TEXTURE1;GLES30.glActiveTexture(unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,stroke);GLES30.glUniform1i(if(screen)uScreenStrokeTex else uStrokeTex,if(screen)2 else 1);if(mode==StrokeRenderMode.NON_BUILDUP){GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,coverage);GLES30.glUniform1i(uStrokeCoverageTex,3)}}
        GLES30.glUniform1i(uStrokeMode,if(active)when(mode){StrokeRenderMode.NORMAL_BUILDUP->0;StrokeRenderMode.NON_BUILDUP->1;StrokeRenderMode.MULTIPLY->2}else 0)
    }
    private fun clearTextures(){for(unit in intArrayOf(GLES30.GL_TEXTURE1,GLES30.GL_TEXTURE2,GLES30.GL_TEXTURE3)){GLES30.glActiveTexture(unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)};GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)}

    fun renderLayer(geometry:CanvasGeometry,layer:PaintLayer,canvasWidth:Int=layer.target.width,canvasHeight:Int=layer.target.height,canvasToClip:FloatArray){val p=program?:return;p.use();GLES30.glUniformMatrix4fv(uCanvasToClip,1,false,canvasToClip,0);GLES30.glUniform2f(uCanvasSize,canvasWidth.toFloat(),canvasHeight.toFloat());GLES30.glUniform4f(uUvRect,0f,0f,1f,1f);bindLayer(layer.target.textureId);setNoStroke(layer.opacity);GLES30.glEnable(GLES30.GL_BLEND);GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_ALPHA);geometry.draw();GLES30.glDisable(GLES30.GL_BLEND);clearTextures()}
    fun release(){GlCheck.checkOnGlThread();program?.release();program=null;tileGeometry.release()}
    private companion object{val DEFAULT_STROKE_COLOR=floatArrayOf(0f,0f,0f);const val TAG="Compositor"}
}
