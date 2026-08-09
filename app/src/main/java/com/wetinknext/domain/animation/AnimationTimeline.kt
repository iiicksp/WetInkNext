package com.wetinknext.domain.animation

/**
 * Creates a default set of animation frames from a list of layer IDs.
 * Each layer becomes its own frame. System "Background" (usually ID 1) should be filtered out by the caller.
 */
fun createFramesFromLayers(layerIds: List<Long>): List<AnimationFrame> {
    return layerIds.map { layerId ->
        AnimationFrame(
            id = layerId, // Use layerId as initial frame ID for simplicity in 1:1 mapping
            layerIds = listOf(layerId)
        )
    }
}

/**
 * Ensures the animation document is valid given the currently existing layers.
 * Removes dead layer references, empty frames, and enforces range limits.
 */
fun normalizedAnimationDocument(
    document: AnimationDocument,
    existingLayerIds: Set<Long>,
): AnimationDocument {
    return normalizedAnimationDocument(
        document = document,
        layersInDocumentOrder = existingLayerIds.map { AnimationLayerInfo(id = it) },
    )
}

/** Minimal layer data needed by the timeline. Kept independent from OpenGL resources. */
data class AnimationLayerInfo(
    val id: Long,
    val isCanvasBackgroundLayer: Boolean = false,
    val isClipping: Boolean = false,
)

/**
 * Normalizes frames against the real document order. Canvas background never becomes a frame.
 * Layer IDs inside every frame are also kept in document order, which is required for clipping.
 */
fun normalizedAnimationDocument(
    document: AnimationDocument,
    layersInDocumentOrder: List<AnimationLayerInfo>,
): AnimationDocument {
    val order = layersInDocumentOrder.mapIndexed { index, layer -> layer.id to index }.toMap()
    val animatableIds = layersInDocumentOrder
        .filterNot { it.isCanvasBackgroundLayer }
        .map { it.id }
    val usedLayerIds = mutableSetOf<Long>()
    
    val normalizedFrames = document.frames.mapNotNull { frame ->
        val validLayerIds = frame.layerIds
            .filter { id -> id in order && id !in usedLayerIds }
            .sortedBy { order.getOrDefault(it, 0) }
        
        if (validLayerIds.isEmpty()) {
            null
        } else {
            usedLayerIds.addAll(validLayerIds)
            frame.copy(
                layerIds = validLayerIds,
                holdFrames = frame.holdFrames.coerceIn(1, 120)
            )
        }
    }
    // A layer can belong to only one frame. Any newly created document layer gets its own frame.
    val missingLayerIds = animatableIds.filter { it !in usedLayerIds }
    val newFrames = missingLayerIds.map { layerId ->
        AnimationFrame(id = layerId, layerIds = listOf(layerId))
    }
    return document.copy(
        framesPerSecond = document.framesPerSecond.coerceIn(1, 60),
        frames = normalizedFrames + newFrames
    )
}

/**
 * Combines multiple layers into a single animation frame.
 */
fun groupLayersIntoFrame(
    document: AnimationDocument,
    layerIdsToGroup: List<Long>,
    newFrameId: Long = System.nanoTime()
): AnimationDocument {
    val orderedIds = layerIdsToGroup.distinct()
    val idsSet = orderedIds.toSet()
    if (idsSet.isEmpty()) return document

    val insertionIndex = document.frames.indexOfFirst { frame -> frame.layerIds.any { it in idsSet } }
    val cleanedFrames = document.frames.map { frame ->
        frame.copy(layerIds = frame.layerIds.filter { it !in idsSet })
    }.filter { it.layerIds.isNotEmpty() }.toMutableList()
    val newFrame = AnimationFrame(id = newFrameId, layerIds = orderedIds)
    val targetIndex = if (insertionIndex < 0) cleanedFrames.size else insertionIndex.coerceAtMost(cleanedFrames.size)
    cleanedFrames.add(targetIndex, newFrame)
    return document.copy(frames = cleanedFrames)
}

sealed interface AnimationFrameGroupingValidation {
    data class Valid(val layerIdsInDocumentOrder: List<Long>) : AnimationFrameGroupingValidation
    data object EmptySelection : AnimationFrameGroupingValidation
    data class MissingClippingBase(val clippingLayerId: Long) : AnimationFrameGroupingValidation
    data class MissingClippingLayer(val baseLayerId: Long, val clippingLayerId: Long) : AnimationFrameGroupingValidation
}

/**
 * A clipping layer and its base must live in one animation frame: otherwise a frame would render
 * with a different compositing result than the document. The selection is returned in document order.
 */
fun validateAnimationFrameGrouping(
    selectedLayerIds: Set<Long>,
    layersInDocumentOrder: List<AnimationLayerInfo>,
): AnimationFrameGroupingValidation {
    val layers = layersInDocumentOrder.filterNot { it.isCanvasBackgroundLayer }
    val selected = selectedLayerIds.intersect(layers.map { it.id }.toSet())
    if (selected.isEmpty()) return AnimationFrameGroupingValidation.EmptySelection

    val baseByClippingId = mutableMapOf<Long, Long>()
    layers.forEachIndexed { index, layer ->
        if (layer.isClipping) {
            val base = layers.subList(0, index).lastOrNull { !it.isClipping }
                ?: return AnimationFrameGroupingValidation.MissingClippingBase(layer.id)
            baseByClippingId[layer.id] = base.id
            if (layer.id in selected && base.id !in selected) {
                return AnimationFrameGroupingValidation.MissingClippingBase(layer.id)
            }
        }
    }
    baseByClippingId.forEach { (clippingId, baseId) ->
        if (baseId in selected && clippingId !in selected) {
            return AnimationFrameGroupingValidation.MissingClippingLayer(baseId, clippingId)
        }
    }
    return AnimationFrameGroupingValidation.Valid(layers.filter { it.id in selected }.map { it.id })
}

/**
 * Expands frames into a flat list of frame IDs to be played back, accounting for holdFrames.
 */
fun expandedPlaybackFrameIds(
    frames: List<AnimationFrame>,
): List<Long> {
    return frames.flatMap { frame ->
        List(frame.holdFrames) { frame.id }
    }
}

data class PlaybackStep(
    val nextIndex: Int,
    val nextDirection: Int, // 1 for forward, -1 for backward
    val shouldStop: Boolean = false
)

/**
 * Calculates the next index in the playback sequence.
 */
fun nextPlaybackIndex(
    currentIndex: Int,
    direction: Int,
    frameCount: Int,
    mode: AnimationPlaybackMode,
): PlaybackStep {
    if (frameCount <= 0) return PlaybackStep(0, 1, true)
    if (frameCount == 1) return PlaybackStep(0, 1, mode == AnimationPlaybackMode.ONE_SHOT)

    var nextDir = direction
    var nextIdx = currentIndex + direction
    var stop = false

    when (mode) {
        AnimationPlaybackMode.LOOP -> {
            if (nextIdx >= frameCount) nextIdx = 0
            if (nextIdx < 0) nextIdx = frameCount - 1
        }
        AnimationPlaybackMode.PING_PONG -> {
            if (nextIdx >= frameCount) {
                nextIdx = (frameCount - 2).coerceAtLeast(0)
                nextDir = -1
            } else if (nextIdx < 0) {
                nextIdx = (1).coerceAtMost(frameCount - 1)
                nextDir = 1
            }
        }
        AnimationPlaybackMode.ONE_SHOT -> {
            if (nextIdx >= frameCount) {
                nextIdx = frameCount - 1
                stop = true
            }
        }
    }

    return PlaybackStep(nextIdx, nextDir, stop)
}
