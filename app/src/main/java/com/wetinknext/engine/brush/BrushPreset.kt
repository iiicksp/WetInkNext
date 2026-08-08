package com.wetinknext.engine.brush

import kotlinx.serialization.Serializable

@Serializable
data class BrushPreset(
    val id: String,
    val settings: BrushSettings,
)
