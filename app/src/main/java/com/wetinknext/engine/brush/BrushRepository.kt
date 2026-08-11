package com.wetinknext.engine.brush

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BrushRepository(
    private val json: Json = defaultJson,
) {
    fun encode(preset: BrushPreset): String =
        json.encodeToString(preset)

    fun decode(value: String): BrushPreset =
        json.decodeFromString<BrushPreset>(value).let { preset ->
            preset.copy(settings = preset.settings.resolved())
        }

    companion object {
        val defaultJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
