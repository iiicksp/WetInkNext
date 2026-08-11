package com.wetinknext.engine.input

/** Current ownership of touch input. Only DRAWING reaches the brush pipeline. */
enum class InputMode {
    IDLE,
    DRAWING,
    NAVIGATING,
    CANCELLED,
}
