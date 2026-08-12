package com.wetinknext.domain.document

import com.wetinknext.domain.animation.AnimationDocument
import com.wetinknext.engine.brush.BrushPreset
import com.wetinknext.engine.canvas.BlendMode
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Persisted project structure. Pixel data deliberately lives outside this JSON
 * document: [LayerDocument.pixelFile] points at its future tile/container data.
 */
@Serializable
data class ProjectDocument(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val colorProfile: String,
    val createdAt: Long,
    val updatedAt: Long,
    val layers: List<LayerDocument>,
    val brushes: List<BrushPreset> = emptyList(),
    val animation: AnimationDocument = AnimationDocument(),
    val activeLayerId: Long? = null,
    /** Changes only when the project-wide preview is rebuilt. */
    val thumbnailVersion: Long = 0L,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(id.isNotBlank()) { "Project id must not be blank" }
        require(name.isNotBlank()) { "Project name must not be blank" }
        require(width > 0 && height > 0) { "Project dimensions must be positive" }
        require(dpi in 1..2400) { "Project DPI must be within 1..2400" }
        require(colorProfile.isNotBlank()) { "Project color profile must not be blank" }
        require(layers.isNotEmpty()) { "A project must contain at least one layer" }
        require(layers.map(LayerDocument::id).distinct().size == layers.size) {
            "Layer ids must be unique"
        }
        require(activeLayerId == null || layers.any { it.id == activeLayerId }) {
            "Active layer must belong to the project"
        }
        require(version >= 1) { "Project version must be positive" }
        require(thumbnailVersion >= 0L) { "Project thumbnail version must not be negative" }
    }

    fun withUpdatedTimestamp(nowMillis: Long): ProjectDocument = copy(updatedAt = nowMillis)

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_WIDTH = 1500
        const val DEFAULT_HEIGHT = 2000
        const val DEFAULT_DPI = 300
        const val SRGB_PROFILE = "sRGB IEC61966-2.1"
        const val DISPLAY_P3_PROFILE = "Display P3"
        const val MIN_DIMENSION = 256
        const val MAX_DIMENSION = 8192
        const val MIN_DPI = 72
        const val MAX_DPI = 1200
        const val MAX_INITIAL_GPU_BYTES = 256L * 1024L * 1024L
        private const val INITIAL_RENDER_TARGET_COUNT = 3L
        private const val RGBA16F_BYTES_PER_PIXEL = 8L
        val SUPPORTED_COLOR_PROFILES = setOf(SRGB_PROFILE, DISPLAY_P3_PROFILE)

        fun newUntitled(
            name: String = "Untitled",
            width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
        colorProfile: String = SRGB_PROFILE,
        nowMillis: Long = System.currentTimeMillis(),
            id: String = UUID.randomUUID().toString(),
        ): ProjectDocument {
            val normalizedProfile = normalizeColorProfile(colorProfile)
            validateNewCanvas(width, height, dpi, normalizedProfile)
            return ProjectDocument(
            id = id,
            name = name,
            width = width,
            height = height,
            dpi = dpi,
            colorProfile = normalizedProfile,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            layers = listOf(
                LayerDocument(
                    id = BACKGROUND_LAYER_ID,
                    name = "Фон",
                    visible = true,
                    locked = true,
                    opacity = 1f,
                    blendMode = BlendMode.NORMAL,
                    pixelFile = pixelFileFor(BACKGROUND_LAYER_ID),
                ),
                LayerDocument(
                    id = FIRST_PAINT_LAYER_ID,
                    name = "Слой 1",
                    visible = true,
                    locked = false,
                    opacity = 1f,
                    blendMode = BlendMode.NORMAL,
                    pixelFile = pixelFileFor(FIRST_PAINT_LAYER_ID),
                ),
            ),
            activeLayerId = FIRST_PAINT_LAYER_ID,
            )
        }

        /** Validates the initial two layer targets plus the transient stroke target. */
        fun validateNewCanvas(width: Int, height: Int, dpi: Int, colorProfile: String) {
            require(width in MIN_DIMENSION..MAX_DIMENSION) { "Canvas width must be within $MIN_DIMENSION..$MAX_DIMENSION px" }
            require(height in MIN_DIMENSION..MAX_DIMENSION) { "Canvas height must be within $MIN_DIMENSION..$MAX_DIMENSION px" }
            require(dpi in MIN_DPI..MAX_DPI) { "Canvas DPI must be within $MIN_DPI..$MAX_DPI" }
            require(normalizeColorProfile(colorProfile) in SUPPORTED_COLOR_PROFILES) { "Unsupported color profile: $colorProfile" }
            val estimatedBytes = width.toLong() * height * INITIAL_RENDER_TARGET_COUNT * RGBA16F_BYTES_PER_PIXEL
            require(estimatedBytes <= MAX_INITIAL_GPU_BYTES) {
                "Canvas requires ${estimatedBytes / (1024 * 1024)} MB GPU memory; limit is ${MAX_INITIAL_GPU_BYTES / (1024 * 1024)} MB"
            }
        }

        /** Keeps older UI values (`sRGB`) compatible with the persisted profile identifier. */
        fun normalizeColorProfile(value: String): String = when (value.trim()) {
            "sRGB", SRGB_PROFILE -> SRGB_PROFILE
            "Display P3", DISPLAY_P3_PROFILE -> DISPLAY_P3_PROFILE
            else -> value.trim()
        }

        fun pixelFileFor(layerId: Long): String = "layers/$layerId.tiles"

        private const val BACKGROUND_LAYER_ID = 1L
        private const val FIRST_PAINT_LAYER_ID = 2L
    }
}

@Serializable
data class LayerDocument(
    val id: Long,
    val name: String,
    val visible: Boolean,
    val locked: Boolean,
    val opacity: Float,
    val blendMode: BlendMode,
    val pixelFile: String,
    /** Increments whenever the runtime layer pixels change; useful for preview invalidation. */
    val thumbnailVersion: Long = 0L,
) {
    init {
        require(id >= 0L) { "Layer id must not be negative" }
        require(name.isNotBlank()) { "Layer name must not be blank" }
        require(opacity in 0f..1f) { "Layer opacity must be within 0..1" }
        require(pixelFile.isNotBlank()) { "Layer pixel file must not be blank" }
        require(thumbnailVersion >= 0L) { "Layer thumbnail version must not be negative" }
    }
}
