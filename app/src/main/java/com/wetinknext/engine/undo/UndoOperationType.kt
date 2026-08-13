package com.wetinknext.engine.undo

/**
 * A lightweight operation discriminator for the current tile-snapshot history.
 * Future history entries can replace this with full typed operations without
 * changing existing stored tile edits.
 */
enum class UndoOperationType {
    TILE_EDIT,
    CLEAR_LAYER,
    LAYER_PROPERTIES,
    REMOVE_LAYER,
}
