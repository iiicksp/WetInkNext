package com.wetinknext.ui.state

import androidx.compose.runtime.*
import com.wetinknext.engine.core.EditorUiState

class LayerItem(
    val id: Long,
    name: String,
    visible: Boolean,
    opacity: Float,
    locked: Boolean,
) {
    var name by mutableStateOf(name)
    var visible by mutableStateOf(visible)
    var opacity by mutableFloatStateOf(opacity)
    var locked by mutableStateOf(locked)
}

class LayerState {
    val layers = mutableStateListOf<LayerItem>()
    var activeLayerId by mutableStateOf(-1L)

    fun syncFromEditor(state: EditorUiState) {
        val ids = state.layers.map { it.id }.toSet()
        layers.removeAll { it.id !in ids }

        state.layers.forEachIndexed { index, model ->
            val item = layers.firstOrNull { it.id == model.id }

            if (item == null) {
                layers.add(
                    index.coerceAtMost(layers.size),
                    LayerItem(
                        id = model.id,
                        name = model.name,
                        visible = model.isVisible,
                        opacity = model.opacity,
                        locked = model.isLocked,
                    ),
                )
            } else {
                item.name = model.name
                item.visible = model.isVisible
                item.opacity = model.opacity
                item.locked = model.isLocked
                
                // If the item needs to be moved to match the index in state
                val currentIndex = layers.indexOf(item)
                if (currentIndex != index && index < layers.size) {
                    layers.removeAt(currentIndex)
                    layers.add(index, item)
                }
            }
        }

        activeLayerId = state.activeLayerId
    }
}
