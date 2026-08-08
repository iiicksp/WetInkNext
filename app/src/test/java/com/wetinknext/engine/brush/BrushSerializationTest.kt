package com.wetinknext.engine.brush

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushSerializationTest {

    @Test
    fun settingsRoundTripKeepsRenderFields() {
        val json = BrushRepository.defaultJson
        val original = BrushSettings(
            name = "Pencil 6B",
            grainAssetPath = "asset:brush/pencil_6b.png",
            grainScale = 5.5f,
            textureDepth = .65f,
            tiltToSize = .55f,
            blendPolicy = BlendPolicy.NORMAL_BUILDUP,
        )

        val encoded = json.encodeToString(original)
        val restored = json.decodeFromString<BrushSettings>(encoded)

        assertEquals(original, restored)
    }

    @Test
    fun presetRoundTrip() {
        val repo = BrushRepository()
        val original = BrushPreset(
            id = "test-preset",
            settings = BrushSettings(name = "Test Brush")
        )

        val encoded = repo.encode(original)
        val restored = repo.decode(encoded)

        assertEquals(original, restored)
    }
}
