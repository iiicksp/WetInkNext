package com.wetinknext.ui.state

/**
 * Interface defining all possible user actions from the UI that affect the paint engine state.
 */
interface EditorActions {
    fun undo()
    fun redo()
    fun setBrushSize(px: Float)
    fun setBrushOpacity(opacity: Float)

    fun addLayer()
    fun removeLayer(id: Long)
    fun setActiveLayer(id: Long)
    fun setLayerVisible(id: Long, visible: Boolean)
    fun setLayerOpacity(id: Long, opacity: Float)
}
