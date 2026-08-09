package com.wetinknext.domain.animation

import kotlinx.serialization.Serializable

@Serializable
enum class AnimationPlaybackMode {
    LOOP,
    PING_PONG,
    ONE_SHOT,
}

@Serializable
enum class AnimationFrameRole {
    NORMAL,
    BACKGROUND,
    FOREGROUND,
}

@Serializable
data class OnionSkinSettings(
    val enabled: Boolean = false, // Disabled by default until renderer supports it
    val previousFrames: Int = 1,
    val nextFrames: Int = 1,
    val opacity: Float = 0.35f,
    val previousColorArgb: Int = 0xFFFF4FA3.toInt(),
    val nextColorArgb: Int = 0xFF4A8DFF.toInt(),
    val blendPrimaryFrame: Boolean = false,
)

@Serializable
data class AnimationFrame(
    val id: Long,
    val layerIds: List<Long>,
    val holdFrames: Int = 1,
    val role: AnimationFrameRole = AnimationFrameRole.NORMAL,
)

@Serializable
data class AnimationDocument(
    val enabled: Boolean = false,
    val framesPerSecond: Int = 12,
    val playbackMode: AnimationPlaybackMode = AnimationPlaybackMode.LOOP,
    val onionSkin: OnionSkinSettings = OnionSkinSettings(),
    val frames: List<AnimationFrame> = emptyList(),
)
