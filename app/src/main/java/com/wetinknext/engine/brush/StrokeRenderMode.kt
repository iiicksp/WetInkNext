package com.wetinknext.engine.brush

/**
 * Declares what a temporary stroke texture represents on this pass.
 *
 * NORMAL_BUILDUP stores premultiplied colour accumulated with source-over.
 * NON_BUILDUP stores union coverage and receives colour only in the combine
 * pass. Keeping this explicit prevents a renderer from guessing by policy.
 */
enum class StrokeRenderMode {
    NORMAL_BUILDUP,
    NON_BUILDUP,
    MULTIPLY,
}
