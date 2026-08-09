package com.wetinknext.domain.animation

/**
 * Defines which frames and roles should be rendered in the current view.
 */
data class AnimationRenderPlan(
    val backgroundFrame: AnimationFrame?,
    val currentFrame: AnimationFrame?,
    val foregroundFrame: AnimationFrame?,
    val previousOnionFrames: List<AnimationFrame>,
    val nextOnionFrames: List<AnimationFrame>,
)

/**
 * Logic to decide which layers are visible based on the animation document.
 * This is a pure function for easy testing.
 */
fun buildAnimationRenderPlan(
    document: AnimationDocument,
    currentFrameId: Long,
): AnimationRenderPlan {
    if (!document.enabled) {
        return AnimationRenderPlan(null, null, null, emptyList(), emptyList())
    }

    val frames = document.frames
    val currentIndex = frames.indexOfFirst { it.id == currentFrameId }
    
    val bgFrame = frames.find { it.role == AnimationFrameRole.BACKGROUND }
    val fgFrame = frames.find { it.role == AnimationFrameRole.FOREGROUND }
    val current = if (currentIndex >= 0) frames[currentIndex] else null
    
    val prevOnions = mutableListOf<AnimationFrame>()
    val nextOnions = mutableListOf<AnimationFrame>()
    
    if (document.onionSkin.enabled && currentIndex >= 0) {
        // Previous frames
        for (i in 1..document.onionSkin.previousFrames) {
            val idx = currentIndex - i
            if (idx >= 0) {
                val f = frames[idx]
                if (f.role == AnimationFrameRole.NORMAL) prevOnions.add(f)
            }
        }
        // Next frames
        for (i in 1..document.onionSkin.nextFrames) {
            val idx = currentIndex + i
            if (idx < frames.size) {
                val f = frames[idx]
                if (f.role == AnimationFrameRole.NORMAL) nextOnions.add(f)
            }
        }
    }

    return AnimationRenderPlan(
        backgroundFrame = bgFrame,
        currentFrame = current,
        foregroundFrame = fgFrame,
        previousOnionFrames = prevOnions,
        nextOnionFrames = nextOnions
    )
}
