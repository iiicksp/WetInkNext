package com.wetinknext.engine.animation

import android.opengl.GLES30
import com.wetinknext.domain.animation.AnimationDocument
import com.wetinknext.domain.animation.createFramesFromLayers
import com.wetinknext.domain.animation.groupLayersIntoFrame
import com.wetinknext.domain.animation.nextPlaybackIndex
import com.wetinknext.domain.animation.normalizedAnimationDocument
import com.wetinknext.engine.canvas.Compositor
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.CanvasGeometry
import com.wetinknext.engine.gl.GlCheck
import com.wetinknext.engine.gl.RenderTarget

/**
 * Frame-by-frame animation subsystem: the frame document, layer visibility
 * masks per frame, onion skin rendering and playback timing.
 *
 * Lives entirely on the GL thread, exactly like the renderer it was extracted
 * from. Rendering context objects (compositor / geometry / canvas matrix) are
 * injected as providers so this class stays testable and the renderer stays
 * the coordinator.
 */
class AnimationController(
    private val layerStack: LayerStack,
    private val requestRender: () -> Unit,
    private val publishUiState: () -> Unit = {},
    private val compositorProvider: () -> Compositor? = { null },
    private val geometryProvider: () -> CanvasGeometry? = { null },
    private val canvasToFboProvider: () -> FloatArray = { FloatArray(16) },
) {
    private var animationDocument: AnimationDocument? = null
    private var animationActive = false
    private var animationFrameId = 0L
    private var animationPlaying = false
    private var animationLastTickNanos = 0L
    private val savedLayerVisibility = mutableMapOf<Long, Boolean>()
    private val onionTarget = RenderTarget()

    private val onionOpacity: Float get() = animationDocument?.onionSkin?.opacity ?: 0.35f

    val document: AnimationDocument? get() = animationDocument
    val frameId: Long get() = animationFrameId
    val isPlaying: Boolean get() = animationPlaying

    fun onionTextureId(): Int = onionTarget.textureId

    private fun ensureAnimationDocument(): AnimationDocument {
        val existing = animationDocument
        if (existing != null && existing.frames.isNotEmpty()) return existing
        val doc = AnimationDocument(
            enabled = true,
            framesPerSecond = 12,
            frames = createFramesFromLayers(layerStack.allLayers().map { it.id }),
        )
        animationDocument = normalizedAnimationDocument(doc, layerStack.allLayers().map { it.id }.toSet())
        return animationDocument!!
    }

    /** Advances playback by one frame when the current hold period elapsed. */
    fun trackPlayback() {
        if (!animationActive || !animationPlaying) return
        val doc = animationDocument ?: return
        if (doc.frames.size <= 1) return
        val now = System.nanoTime()
        val hold = doc.frames.firstOrNull { it.id == animationFrameId }?.holdFrames ?: 1
        val periodNanos = (1_000_000_000L / doc.framesPerSecond.coerceIn(1, 60)) * hold
        if (now - animationLastTickNanos < periodNanos) return
        val index = doc.frames.indexOfFirst { it.id == animationFrameId }.coerceAtLeast(0)
        val step = nextPlaybackIndex(index, 1, doc.frames.size, doc.playbackMode)
        animationLastTickNanos = now
        if (step.shouldStop) {
            setAnimationPlaying(false)
            return
        }
        val next = doc.frames.getOrNull(step.nextIndex) ?: return
        animationFrameId = next.id
        applyFrameToVisibility(animationFrameId)
        rebuildOnion()
        publishUiState()
        requestRender()
    }

    /** Enters/exits animation mode; restores layer visibility on exit. */
    fun toggleAnimationActive() {
        GlCheck.checkOnGlThread()
        if (!animationActive) {
            savedLayerVisibility.clear()
            layerStack.allLayers().forEach { savedLayerVisibility[it.id] = it.isVisible }
            val doc = ensureAnimationDocument()
            animationActive = true
            animationFrameId = doc.frames.firstOrNull()?.id ?: 0L
            applyFrameToVisibility(animationFrameId)
            rebuildOnion()
        } else {
            animationActive = false
            animationPlaying = false
            restoreLayerVisibility()
            onionTarget.release()
        }
        publishUiState()
        requestRender()
    }

    fun setAnimationDocument(document: AnimationDocument) {
        GlCheck.checkOnGlThread()
        val normalized = normalizedAnimationDocument(
            document.copy(enabled = true),
            layerStack.allLayers().map { it.id }.toSet(),
        )
        animationDocument = normalized
        val stillValid = normalized.frames.any { it.id == animationFrameId }
        if (!stillValid) {
            animationFrameId = normalized.frames.firstOrNull()?.id ?: 0L
        }
        if (animationActive) applyFrameToVisibility(animationFrameId)
        rebuildOnion()
        publishUiState()
        requestRender()
    }

    fun setAnimationFrame(frameId: Long) {
        val doc = animationDocument ?: return
        if (doc.frames.none { it.id == frameId }) return
        animationFrameId = frameId
        if (animationActive) applyFrameToVisibility(frameId)
        rebuildOnion()
        publishUiState()
        requestRender()
    }

    fun animationAddFrame() {
        val doc = ensureAnimationDocument()
        val activeIds = doc.frames.firstOrNull { it.id == animationFrameId }?.layerIds.orEmpty()
        val updated = groupLayersIntoFrame(doc, activeIds)
        animationDocument = normalizedAnimationDocument(updated, layerStack.allLayers().map { it.id }.toSet())
        val newDoc = animationDocument!!
        animationFrameId = newDoc.frames.firstOrNull()?.id ?: 0L
        applyFrameToVisibility(animationFrameId)
        rebuildOnion()
        publishUiState()
        requestRender()
    }

    fun animationDuplicateFrame(frameId: Long) {
        val doc = animationDocument ?: return
        val ids = doc.frames.firstOrNull { it.id == frameId }?.layerIds.orEmpty()
        animationDocument = normalizedAnimationDocument(
            groupLayersIntoFrame(doc, ids),
            layerStack.allLayers().map { it.id }.toSet(),
        )
        publishUiState()
        requestRender()
    }

    fun animationDeleteFrame(frameId: Long) {
        val doc = animationDocument ?: return
        val remaining = doc.frames.filterNot { it.id == frameId }
        val updated = if (remaining.isEmpty()) {
            AnimationDocument(
                enabled = true,
                framesPerSecond = doc.framesPerSecond,
                playbackMode = doc.playbackMode,
                onionSkin = doc.onionSkin,
                frames = createFramesFromLayers(layerStack.allLayers().map { it.id }),
            )
        } else doc.copy(frames = remaining)
        animationDocument = normalizedAnimationDocument(updated, layerStack.allLayers().map { it.id }.toSet())
        if (!animationDocument!!.frames.any { it.id == animationFrameId }) {
            animationFrameId = animationDocument!!.frames.firstOrNull()?.id ?: 0L
        }
        if (animationActive) applyFrameToVisibility(animationFrameId)
        rebuildOnion()
        publishUiState()
        requestRender()
    }

    fun animationMoveFrame(frameId: Long, direction: Int) {
        val doc = animationDocument ?: return
        val frames = doc.frames.toMutableList()
        val index = frames.indexOfFirst { it.id == frameId }
        val target = index + direction
        if (index < 0 || target < 0 || target >= frames.size) return
        val moved = frames.removeAt(index)
        frames.add(target, moved)
        animationDocument = normalizedAnimationDocument(doc.copy(frames = frames), layerStack.allLayers().map { it.id }.toSet())
        publishUiState()
        requestRender()
    }

    fun animationSetHold(frameId: Long, hold: Int) {
        val doc = animationDocument ?: return
        animationDocument = normalizedAnimationDocument(
            doc.copy(frames = doc.frames.map { if (it.id == frameId) it.copy(holdFrames = hold.coerceIn(1, 120)) else it }),
            layerStack.allLayers().map { it.id }.toSet(),
        )
        publishUiState()
        requestRender()
    }

    fun animationTogglePlay() {
        setAnimationPlaying(!animationPlaying)
    }

    fun setAnimationPlaying(playing: Boolean) {
        if (animationPlaying == playing) return
        animationPlaying = playing && animationActive
        if (animationPlaying) animationLastTickNanos = System.nanoTime()
        publishUiState()
        requestRender()
    }

    /** Applies the frame's layer membership to visibility; onion rebuild on demand. */
    private fun applyFrameToVisibility(frameId: Long) {
        val doc = animationDocument ?: return
        val frame = doc.frames.firstOrNull { it.id == frameId } ?: return
        val visibleIds = frame.layerIds.toSet()
        for (layer in layerStack.allLayers()) {
            val saved = savedLayerVisibility[layer.id] ?: true
            layer.isVisible = saved && (layer.id in visibleIds)
        }
    }

    private fun restoreLayerVisibility() {
        for (layer in layerStack.allLayers()) {
            savedLayerVisibility[layer.id]?.let { layer.isVisible = it }
        }
        savedLayerVisibility.clear()
    }

    private fun rebuildOnion() {
        val doc = animationDocument ?: return
        if (!doc.onionSkin.enabled || doc.frames.size < 2) {
            onionTarget.release()
            return
        }
        val index = doc.frames.indexOfFirst { it.id == animationFrameId }
        if (index <= 0) {
            onionTarget.release()
            return
        }
        val prevFrame = doc.frames[index - 1]
        val activeIds = doc.frames[index].layerIds.toSet()
        val context = compositorProvider() ?: return
        val geom = geometryProvider() ?: return
        val canvasToFbo = canvasToFboProvider()
        onionTarget.create(layerStack.canvasWidth, layerStack.canvasHeight, preferHalfFloat = false)
        onionTarget.clear(0f, 0f, 0f, 0f)
        onionTarget.bind()
        GLES30.glViewport(0, 0, layerStack.canvasWidth, layerStack.canvasHeight)
        for (layer in layerStack.allLayers()) {
            if (layer.id !in prevFrame.layerIds || layer.id in activeIds) continue
            val saved = layer.opacity
            layer.opacity = (saved * onionOpacity).coerceIn(0f, 1f)
            context.renderLayer(geom, layer, canvasToClip = canvasToFbo)
            layer.opacity = saved
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Full teardown on the GL thread (renderer detach / surface release). */
    fun release() {
        onionTarget.release()
        savedLayerVisibility.clear()
        animationActive = false
        animationPlaying = false
        animationDocument = null
    }

    /** For a dead EGL context: drop names without glDelete*. */
    fun resetGlHandles() {
        onionTarget.resetHandles()
    }
}
