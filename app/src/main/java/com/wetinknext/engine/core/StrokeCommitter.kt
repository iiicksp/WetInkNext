package com.wetinknext.engine.core

import android.opengl.GLES30
import android.util.Log
import com.wetinknext.BuildConfig
import com.wetinknext.engine.brush.StrokeRenderMode
import com.wetinknext.engine.canvas.NonBuildupStrokeRenderer
import com.wetinknext.engine.canvas.PaintLayer
import com.wetinknext.engine.canvas.StrokeBlitter
import com.wetinknext.engine.canvas.TileStrokeMirror
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget
import com.wetinknext.engine.undo.PboTileReadbackQueue
import com.wetinknext.engine.undo.RawTileSnapshot
import com.wetinknext.engine.undo.TileSnapshot
import com.wetinknext.engine.undo.TileSnapshotCapture
import com.wetinknext.engine.undo.UndoManager
import com.wetinknext.engine.undo.UndoOperationType

/** GL-thread transaction boundary for a prepared stroke. */
class StrokeCommitter(
    private val strokeTarget: RenderTarget,
    private val canvasToFbo: FloatArray,
    private val undoPipeline: UndoCompressionPipeline,
    private val undoManager: UndoManager,
    private val requestRender: () -> Unit,
    private val onTilesCommitted: (PaintLayer, List<RawTileSnapshot>) -> Unit = { _, _ -> },
    private val onLayerCleared: (PaintLayer) -> Unit = {},
) {
    sealed interface CommitResult { data object Queued : CommitResult; data object Rejected : CommitResult }
    private data class PendingTransaction(val layer:PaintLayer,val before:PboTileReadbackQueue.Capture,val after:PboTileReadbackQueue.Capture?,val operation:UndoOperationType,val tag:String)
    private val readbackQueue=PboTileReadbackQueue(requestRender); private val pendingTransactions=ArrayDeque<PendingTransaction>(); private val tileRenderer=TileStrokeMirror()
    val pendingReadbackCount:Int get()=pendingTransactions.size

    fun commit(sourceTarget:RenderTarget=strokeTarget,layer:PaintLayer,geometry:CanvasGeometry,blitter:StrokeBlitter,dirtyBounds:IntArray,canvasWidth:Int,canvasHeight:Int,opacity:Float,erase:Boolean=false,strokeMode:StrokeRenderMode=StrokeRenderMode.NORMAL_BUILDUP,operation:UndoOperationType=UndoOperationType.TILE_EDIT,tag:String="stroke"):CommitResult{
        GlCheck.checkOnGlThread(); if(!layer.created||layer.isLocked||sourceTarget.textureId==0||dirtyBounds.size<4)return CommitResult.Rejected
        expandBoundsToTiles(dirtyBounds,canvasWidth,canvasHeight);if(dirtyBounds[2]<=dirtyBounds[0]||dirtyBounds[3]<=dirtyBounds[1])return CommitResult.Rejected;logLargeUndoReadback(layer,dirtyBounds)
        val before=readbackQueue.issue(layer.target,dirtyBounds)
        val report=if(layer.isTiled){tileRenderer.mirrorStroke(layer,geometry,blitter,sourceTarget.textureId,dirtyBounds,canvasWidth,canvasHeight,opacity.coerceIn(0f,1f),erase,strokeMode).also{layer.syncTiledTilesToFullTarget(dirtyBounds)}}else{blitter.blit(layer.target,geometry,sourceTarget.textureId,canvasToFbo,canvasWidth,canvasHeight,opacity.coerceIn(0f,1f),erase,strokeMode);if(PaintLayer.useTiledStrokeMirror)tileRenderer.mirrorStroke(layer,geometry,blitter,sourceTarget.textureId,dirtyBounds,canvasWidth,canvasHeight,opacity.coerceIn(0f,1f),erase,strokeMode)else null}
        logTileReport(layer,report);layer.version++;val after=readbackQueue.issue(layer.target,dirtyBounds);pendingTransactions+=PendingTransaction(layer,before,after,operation,tag);return CommitResult.Queued
    }

    fun commitNonBuildup(layer:PaintLayer,geometry:CanvasGeometry,blitter:NonBuildupStrokeRenderer,coverageTarget:RenderTarget,colorLinear:FloatArray,dirtyBounds:IntArray,canvasWidth:Int,canvasHeight:Int,opacity:Float,erase:Boolean=false,strokeMode:StrokeRenderMode=StrokeRenderMode.NORMAL_BUILDUP,edgeDarkening:Float=0f):CommitResult{
        GlCheck.checkOnGlThread();if(!layer.created||layer.isLocked||coverageTarget.textureId==0||colorLinear.size<3||dirtyBounds.size<4)return CommitResult.Rejected
        expandBoundsToTiles(dirtyBounds,canvasWidth,canvasHeight);if(dirtyBounds[2]<=dirtyBounds[0]||dirtyBounds[3]<=dirtyBounds[1])return CommitResult.Rejected;logLargeUndoReadback(layer,dirtyBounds)
        val before=readbackQueue.issue(layer.target,dirtyBounds)
        val report=if(layer.isTiled){tileRenderer.mirrorNonBuildup(layer,geometry,blitter,coverageTarget.textureId,colorLinear,dirtyBounds,canvasWidth,canvasHeight,opacity,erase,strokeMode,edgeDarkening).also{layer.syncTiledTilesToFullTarget(dirtyBounds)}}else{blitter.blit(layer.target,geometry,coverageTarget.textureId,colorLinear,canvasToFbo,canvasWidth,canvasHeight,opacity,erase,strokeMode,edgeDarkening);if(PaintLayer.useTiledStrokeMirror)tileRenderer.mirrorNonBuildup(layer,geometry,blitter,coverageTarget.textureId,colorLinear,dirtyBounds,canvasWidth,canvasHeight,opacity,erase,strokeMode,edgeDarkening)else null}
        logTileReport(layer,report);layer.version++;val after=readbackQueue.issue(layer.target,dirtyBounds);pendingTransactions+=PendingTransaction(layer,before,after,UndoOperationType.TILE_EDIT,"stroke");return CommitResult.Queued
    }

    fun clearLayer(layer:PaintLayer,canvasWidth:Int,canvasHeight:Int):CommitResult{
        GlCheck.checkOnGlThread();if(!layer.created||layer.isLocked||canvasWidth<=0||canvasHeight<=0)return CommitResult.Rejected
        val bounds=intArrayOf(0,0,canvasWidth,canvasHeight);logLargeUndoReadback(layer,bounds);val before=readbackQueue.issue(layer.target,bounds)
        layer.target.bind();GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glViewport(0,0,canvasWidth,canvasHeight);GLES30.glClearColor(0f,0f,0f,0f);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);layer.tileResources?.releaseAll()
        layer.version++;val after=readbackQueue.issue(layer.target,bounds);pendingTransactions+=PendingTransaction(layer,before,after,UndoOperationType.CLEAR_LAYER,"clear-layer");return CommitResult.Queued
    }

    fun processPendingReadbacks():Boolean{GlCheck.checkOnGlThread();readbackQueue.poll();var changed=false;while(pendingTransactions.isNotEmpty()){val t=pendingTransactions.first();if(!t.before.isComplete||t.after?.isComplete==false)break;pendingTransactions.removeFirst();undoPipeline.enqueue(undoManager,t.layer.id,t.before.snapshots,t.after?.snapshots.orEmpty(),t.operation,t.tag);if(t.operation==UndoOperationType.CLEAR_LAYER)onLayerCleared(t.layer)else onTilesCommitted(t.layer,t.after?.snapshots.orEmpty());changed=true};return changed}
    fun releaseReadbacks(){GlCheck.checkOnGlThread();readbackQueue.release();pendingTransactions.clear()}

    private fun expandBoundsToTiles(bounds:IntArray,canvasWidth:Int,canvasHeight:Int){val s=TileSnapshot.TILE_SIZE;bounds[0]=(bounds[0]/s)*s;bounds[1]=(bounds[1]/s)*s;bounds[2]=((bounds[2]+s-1)/s)*s;bounds[3]=((bounds[3]+s-1)/s)*s;bounds[0]=bounds[0].coerceIn(0,canvasWidth);bounds[1]=bounds[1].coerceIn(0,canvasHeight);bounds[2]=bounds[2].coerceIn(0,canvasWidth);bounds[3]=bounds[3].coerceIn(0,canvasHeight)}
    private fun logLargeUndoReadback(layer:PaintLayer,bounds:IntArray){if(!BuildConfig.DEBUG)return;val n=TileSnapshotCapture.countTiles(layer.target,bounds);if(n>64)Log.w(TAG,"Large commit: tiles=$n layer=${layer.id} bounds=${bounds.contentToString()}")}
    private fun logTileReport(layer:PaintLayer,report:TileStrokeMirror.Report?){if(!BuildConfig.DEBUG||report==null)return;Log.d(TAG,"stroke tiles layer=${layer.id} touched=${report.touchedTiles} resident=${report.residentTiles} budgetRefused=${report.budgetRefused}")}
    private companion object{const val TAG="StrokeCommitter"}
}
