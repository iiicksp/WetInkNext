package com.wetinknext.engine.thumbnail

import android.os.Handler
import android.os.Looper
import com.wetinknext.engine.canvas.LayerStack
import com.wetinknext.engine.gl.GlCheck
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Result published to the UI only after the current generation reached disk. */
data class ThumbnailBuildResult(
    val generation: Long,
    val projectPreview: File?,
    val layerPreviews: Map<Long, File>,
)

/**
 * Coordinates thumbnail builds across the GL, worker and UI threads.
 *
 * Call [markDirty], [buildIfNeeded], [processCompleted] and [shutdown] on the
 * GL thread. [buildIfNeeded] is the sole point that reads GPU textures. WebP
 * encoding and file publication happen on [executor]; callbacks are dispatched
 * to the Android main thread.
 */
class ThumbnailBuildScheduler(
    private val outputDirectory: File,
    private val renderer: ThumbnailRenderer,
    private val requestGlFrame: () -> Unit,
    private val onSaved: (ThumbnailBuildResult) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val executorFactory: () -> ExecutorService = { Executors.newSingleThreadExecutor() },
    private val uiHandler: Handler = Handler(Looper.getMainLooper()),
    private val encoder: ThumbnailEncoder = ThumbnailEncoder(),
    private val previewSizePx: Int = DEFAULT_PREVIEW_SIZE_PX,
) {
    private var executor = executorFactory()
    private val pendingResults = ConcurrentLinkedQueue<WorkerResult>()
    private val generation = AtomicLong(0L)
    private val dirtyLayerIds = linkedSetOf<Long>()

    private var projectDirty = false
    private var buildRunning = false

    /**
     * Records the newest desired preview state. A newer mark makes an ongoing
     * worker result stale before it is allowed to notify the UI.
     */
    fun markDirty(
        projectDirty: Boolean,
        dirtyLayerIds: Collection<Long> = emptyList(),
    ) {
        GlCheck.checkOnGlThread()
        if (!projectDirty && dirtyLayerIds.isEmpty()) return
        this.projectDirty = this.projectDirty || projectDirty
        this.dirtyLayerIds += dirtyLayerIds
        generation.incrementAndGet()
    }

    fun markProjectDirty() = markDirty(projectDirty = true)

    fun markLayerDirty(layerId: Long) = markDirty(
        projectDirty = false,
        dirtyLayerIds = listOf(layerId),
    )

    /** Stops future work for a deleted layer without touching the GL context. */
    fun removeLayer(layerId: Long) {
        GlCheck.checkOnGlThread()
        if (dirtyLayerIds.remove(layerId)) generation.incrementAndGet()
    }

    /** Recreates the worker after a GL context was deliberately released. */
    fun ensureRunning() {
        GlCheck.checkOnGlThread()
        if (executor.isShutdown) executor = executorFactory()
    }

    /**
     * Captures all currently dirty pixels on the GL thread and starts one
     * worker job. Calling this while another job runs is intentionally a no-op.
     */
    fun buildIfNeeded(layers: LayerStack): Boolean {
        GlCheck.checkOnGlThread()
        if (buildRunning || (!projectDirty && dirtyLayerIds.isEmpty())) return false

        val jobGeneration = generation.get()
        val dimensions = previewDimensions(layers.canvasWidth, layers.canvasHeight)
        val projectPixels = if (projectDirty) {
            renderer.renderProject(layers, dimensions.width, dimensions.height)
        } else {
            null
        }
        val layerPixels = dirtyLayerIds
            .mapNotNull { layerId ->
                layers.findLayerById(layerId)?.let { layer ->
                    layerId to renderer.renderLayer(layer, dimensions.width, dimensions.height)
                }
            }
            .toMap()

        val request = BuildRequest(
            generation = jobGeneration,
            width = dimensions.width,
            height = dimensions.height,
            projectPixels = projectPixels,
            layerPixels = layerPixels,
        )
        buildRunning = true
        try {
            executor.execute {
                pendingResults.add(encodeAndPublish(request))
                requestGlFrame()
            }
        } catch (error: RejectedExecutionException) {
            pendingResults.add(WorkerResult.Failed(jobGeneration, error))
            requestGlFrame()
        }
        return true
    }

    /**
     * Finishes worker jobs on the GL thread and starts no UI callback for stale
     * generations. The next frame can immediately call [buildIfNeeded] again.
     */
    fun processCompleted(): Boolean {
        GlCheck.checkOnGlThread()
        var changed = false
        while (true) {
            val result = pendingResults.poll() ?: break
            buildRunning = false
            changed = true

            if (result.generation != generation.get()) {
                continue
            }

            when (result) {
                is WorkerResult.Saved -> {
                    if (result.projectPreview != null) projectDirty = false
                    dirtyLayerIds.removeAll(result.layerPreviews.keys)
                    uiHandler.post {
                        onSaved(
                            ThumbnailBuildResult(
                                generation = result.generation,
                                projectPreview = result.projectPreview,
                                layerPreviews = result.layerPreviews,
                            ),
                        )
                    }
                }

                is WorkerResult.Failed -> uiHandler.post { onFailure(result.error) }

                is WorkerResult.Stale -> Unit
            }
        }
        return changed
    }

    /** Makes in-flight work obsolete and releases the background encoder. */
    fun shutdown() {
        GlCheck.checkOnGlThread()
        generation.incrementAndGet()
        projectDirty = false
        dirtyLayerIds.clear()
        buildRunning = false
        executor.shutdownNow()
        pendingResults.clear()
        renderer.release()
    }

    private fun encodeAndPublish(request: BuildRequest): WorkerResult = try {
        val temporaryProject = request.projectPixels?.let {
            temporaryFileFor(projectPreviewFile, request.generation)
        }
        val temporaryLayers = request.layerPixels.keys.associateWith { layerId ->
            temporaryFileFor(layerPreviewFile(layerId), request.generation)
        }

        try {
            request.projectPixels?.let { rgba ->
                // Project preview is small and opaque in the usual case: 88 is
                // a good size/quality balance.
                encoder.encodeTopDown(rgba, request.width, request.height, checkNotNull(temporaryProject), lossless = false)
            }
            request.layerPixels.forEach { (layerId, rgba) ->
                // Layer previews must preserve alpha and thin marks.
                encoder.encodeTopDown(rgba, request.width, request.height, checkNotNull(temporaryLayers[layerId]), lossless = true)
            }

            if (request.generation != generation.get()) {
                temporaryProject?.delete()
                temporaryLayers.values.forEach(File::delete)
                WorkerResult.Stale(request.generation)
            } else {
                val publishedProject = temporaryProject?.let {
                    publish(it, projectPreviewFile)
                    projectPreviewFile
                }
                val publishedLayers = temporaryLayers.mapNotNull { (layerId, temporary) ->
                    val target = layerPreviewFile(layerId)
                    publish(temporary, target)
                    layerId to target
                }.toMap()
                WorkerResult.Saved(request.generation, publishedProject, publishedLayers)
            }
        } catch (error: Throwable) {
            temporaryProject?.delete()
            temporaryLayers.values.forEach(File::delete)
            throw error
        }
    } catch (error: Throwable) {
        WorkerResult.Failed(request.generation, error)
    }

    private fun publish(temporary: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            throw IOException("Unable to publish thumbnail ${destination.path}", error)
        }
    }

    private fun temporaryFileFor(destination: File, jobGeneration: Long): File {
        val parent = destination.parentFile ?: error("Thumbnail output requires a parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create thumbnail directory: ${parent.path}")
        }
        return File(parent, ".${destination.name}.$jobGeneration.tmp")
    }

    private val projectPreviewFile: File
        get() = File(outputDirectory, PROJECT_PREVIEW_FILE_NAME)

    private fun layerPreviewFile(layerId: Long): File =
        File(File(outputDirectory, LAYER_PREVIEW_DIRECTORY), "$layerId.webp")

    /** Generates a file with the same aspect ratio as its document. */
    private fun previewDimensions(canvasWidth: Int, canvasHeight: Int): PreviewDimensions {
        val width = canvasWidth.coerceAtLeast(1)
        val height = canvasHeight.coerceAtLeast(1)
        return if (width >= height) {
            PreviewDimensions(previewSizePx, (previewSizePx.toLong() * height / width).toInt().coerceAtLeast(1))
        } else {
            PreviewDimensions((previewSizePx.toLong() * width / height).toInt().coerceAtLeast(1), previewSizePx)
        }
    }

    private data class BuildRequest(
        val generation: Long,
        val width: Int,
        val height: Int,
        val projectPixels: ByteArray?,
        val layerPixels: Map<Long, ByteArray>,
    )

    private data class PreviewDimensions(val width: Int, val height: Int)

    private sealed interface WorkerResult {
        val generation: Long

        data class Saved(
            override val generation: Long,
            val projectPreview: File?,
            val layerPreviews: Map<Long, File>,
        ) : WorkerResult

        data class Failed(
            override val generation: Long,
            val error: Throwable,
        ) : WorkerResult

        data class Stale(
            override val generation: Long,
        ) : WorkerResult

    }

    private companion object {
        const val DEFAULT_PREVIEW_SIZE_PX = 256
        const val PROJECT_PREVIEW_FILE_NAME = "thumbnail.webp"
        const val LAYER_PREVIEW_DIRECTORY = "previews"
    }
}
