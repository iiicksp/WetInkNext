package com.wetinknext.engine.canvas

import com.wetinknext.engine.core.Camera
import com.wetinknext.engine.gl.BudgetedTargets
import com.wetinknext.engine.gl.GlCaps
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.domain.document.LayerStorageFormat

class LayerStack {
    val camera = Camera()
    private val layers = mutableListOf<PaintLayer>()
    private var nextId = 1L
    private var resources: LayerResourceAllocator? = null
    var activeLayerId: Long = NO_LAYER; private set
    var canvasWidth: Int = 0; private set
    var canvasHeight: Int = 0; private set
    val count: Int get() = layers.size
    fun allLayers(): List<PaintLayer> = layers
    fun activeLayer(): PaintLayer? = findLayerById(activeLayerId)
    fun findLayerById(id: Long): PaintLayer? = layers.firstOrNull { it.id == id }
    fun indexOfLayer(id: Long): Int = layers.indexOfFirst { it.id == id }
    fun create(caps: GlCaps, document: ProjectDocument, targets: BudgetedTargets) {
        release(); resources = LayerResourceAllocator(targets, document.layerStorage == LayerStorageFormat.RGBA16F); canvasWidth = document.width; canvasHeight = document.height
        try { document.layers.forEachIndexed { index, source -> val layer = PaintLayer(source.id, source.name).also { it.isVisible=source.visible; it.isLocked=source.locked; it.opacity=source.opacity; it.blendMode=source.blendMode; it.version=source.thumbnailVersion }; check(layer.create(checkNotNull(resources),canvasWidth,canvasHeight)); layer.enableTiledStorage(checkNotNull(resources),canvasWidth,canvasHeight); layers += layer; if(index==0&&source.locked) layer.target.clear(1f,1f,1f,1f) } } catch(error:Throwable){ release(); throw error }
        activeLayerId=document.activeLayerId?:layers.last().id; nextId=(layers.maxOf{it.id}+1L).coerceAtLeast(1L)
    }
    fun create(caps: GlCaps,width:Int,height:Int,targets:BudgetedTargets){ require(width>0&&height>0); release(); resources=LayerResourceAllocator(targets,false); canvasWidth=width; canvasHeight=height; val background=checkNotNull(newLayer("Фон")).also{it.isLocked=true;it.target.clear(1f,1f,1f,1f)}; layers+=background; layers+=checkNotNull(newLayer("Слой 1")); activeLayerId=layers.last().id }
    fun addLayer(name:String,insertAt:Int=layers.size):PaintLayer?{check(canvasWidth>0&&canvasHeight>0);val layer=newLayer(name)?:return null;layers.add(insertAt.coerceIn(0,layers.size),layer);activeLayerId=layer.id;return layer}
    fun restoreLayer(id:Long,name:String,insertAt:Int,visible:Boolean,locked:Boolean,opacity:Float,blendMode:BlendMode,version:Long):PaintLayer?{if(findLayerById(id)!=null)return null;val layer=PaintLayer(id,name);if(!layer.create(checkNotNull(resources),canvasWidth,canvasHeight))return null;layer.enableTiledStorage(checkNotNull(resources),canvasWidth,canvasHeight);layer.isVisible=visible;layer.isLocked=locked;layer.opacity=opacity.coerceIn(0f,1f);layer.blendMode=blendMode;layer.version=version;layers.add(insertAt.coerceIn(0,layers.size),layer);nextId=maxOf(nextId,id+1L);activeLayerId=id;return layer}
    fun removeLayer(id:Long):PaintLayer?{val index=layers.indexOfFirst{it.id==id};if(index<0||layers.size<=1)return null;val layer=layers[index];if(layer.isLocked)return null;layers.removeAt(index);layer.release(checkNotNull(resources));if(activeLayerId==id)activeLayerId=layers[index.coerceAtMost(layers.lastIndex)].id;return layer}
    fun setActive(id:Long):Boolean{if(findLayerById(id)==null)return false;activeLayerId=id;return true}
    fun moveLayer(id:Long,delta:Int):Boolean{val index=layers.indexOfFirst{it.id==id};if(index<0)return false;val newIndex=(index+delta).coerceIn(0,layers.lastIndex);if(newIndex==index)return false;val layer=layers.removeAt(index);layers.add(newIndex,layer);return true}
    fun resetGlHandles(){allLayers().forEach{it.resetGlHandles()}}
    fun release(){resources?.let{allocator->layers.forEach{it.release(allocator)}};layers.clear();activeLayerId=NO_LAYER;canvasWidth=0;canvasHeight=0;resources=null}
    private fun newLayer(name:String):PaintLayer?{val layer=PaintLayer(nextId,name);if(!layer.create(checkNotNull(resources),canvasWidth,canvasHeight))return null;layer.enableTiledStorage(checkNotNull(resources),canvasWidth,canvasHeight);nextId++;return layer}
    private companion object{const val NO_LAYER=-1L}
}
