package com.wetinknext.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wetinknext.data.project.CreateProjectRequest
import com.wetinknext.data.project.AutosaveCoordinator
import com.wetinknext.data.project.FileProjectRepository
import com.wetinknext.data.project.ProjectRepository
import com.wetinknext.data.project.ProjectSummary
import com.wetinknext.data.project.TrashedProjectSummary
import com.wetinknext.domain.document.ProjectDocument
import com.wetinknext.engine.undo.TileCoord
import com.wetinknext.engine.core.ThumbnailCapture
import com.wetinknext.data.project.ThumbnailEncoder
import com.wetinknext.engine.thumbnail.ThumbnailBuildResult
import com.wetinknext.domain.document.LoadedWetInkProject
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AppRoute {
    data object Home : AppRoute
    data class Editor(val projectId: String) : AppRoute
}

data class AppUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val trashedProjects: List<TrashedProjectSummary> = emptyList(),
    val isLoading: Boolean = true,
    val recoveryProject: ProjectSummary? = null,
    val errorMessage: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
)

/** Owns app navigation and project catalog state, never GL resources. */
class AppViewModel(
    private val repository: ProjectRepository,
    private val session: SharedPreferences,
) : ViewModel() {
    var route: AppRoute by mutableStateOf(AppRoute.Home)
        private set
    var uiState: AppUiState by mutableStateOf(AppUiState())
        private set
    var openedDocument: ProjectDocument? by mutableStateOf(null)
        private set
    var openedProject: LoadedWetInkProject? by mutableStateOf(null)
        private set
    private val autosaveCoordinator = AutosaveCoordinator(viewModelScope)
    private var recoveryJob: Job? = null
    private var pendingSaveDocument: ProjectDocument? = null
    private var pendingLayerPayloads: Map<Long, ByteArray> = emptyMap()
    private var pendingTilesAcknowledgement: (() -> Unit)? = null
    private var pendingThumbnailWebp: ByteArray? = null
    private var pendingLayerPreviewsWebp: Map<Long, ByteArray> = emptyMap()
    private var latestThumbnailSequence = 0L

    init {
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            runCatching { repository.list() to repository.listTrash() }
                .onSuccess { (projects, trashedProjects) ->
                    val interruptedId = session.getString(PREF_ACTIVE_PROJECT_ID, null)
                    val shouldRecover = session.getBoolean(PREF_EDITOR_SESSION_OPEN, false)
                    uiState = AppUiState(
                        projects = projects,
                        trashedProjects = trashedProjects,
                        isLoading = false,
                        recoveryProject = if (shouldRecover) {
                            projects.firstOrNull { it.id == interruptedId }
                        } else {
                            null
                        },
                    )
                }
                .onFailure { error ->
                    uiState = uiState.copy(isLoading = false, errorMessage = error.message)
                }
        }
    }

    fun createProject(request: CreateProjectRequest) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            runCatching {
                val clamped = request.copy(
                    width = request.width.coerceIn(1, com.wetinknext.engine.gl.GlCaps.MAX_CANVAS_DIMENSION),
                    height = request.height.coerceIn(1, com.wetinknext.engine.gl.GlCaps.MAX_CANVAS_DIMENSION),
                )
                val summary = repository.create(clamped)
                summary to repository.openForEditor(summary.id)
            }.onSuccess { (summary, loadedProject) ->
                openedProject = loadedProject
                openedDocument = loadedProject.document
                route = AppRoute.Editor(summary.id)
                markEditorSession(summary.id)
                uiState = uiState.copy(
                    projects = (uiState.projects + summary).sortedByDescending(ProjectSummary::updatedAt),
                    isLoading = false,
                    recoveryProject = null,
                )
            }.onFailure { error ->
                uiState = uiState.copy(isLoading = false, errorMessage = error.message)
            }
        }
    }

    fun openProject(projectId: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, errorMessage = null)
            runCatching {
                val recovery = repository.openRecovery(projectId)
                if (recovery != null) {
                    repository.save(recovery.document, recovery.assets.layerTiles)
                    repository.clearRecovery(projectId)
                    recovery
                } else {
                    repository.openForEditor(projectId)
                }
            }
                .onSuccess { loadedProject ->
                    openedProject = loadedProject
                    openedDocument = loadedProject.document
                    route = AppRoute.Editor(projectId)
                    markEditorSession(projectId)
                    uiState = uiState.copy(isLoading = false, recoveryProject = null)
                }
                .onFailure { error ->
                    uiState = uiState.copy(isLoading = false, errorMessage = error.message)
                }
        }
    }

    fun renameProject(projectId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(projectId, name) }
                .onSuccess { refreshProjects() }
                .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.duplicate(projectId) }
                .onSuccess { refreshProjects() }
                .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    fun moveProjectToTrash(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.moveToTrash(projectId) }
                .onSuccess { refreshProjects() }
                .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    fun restoreProject(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.restore(projectId) }
                .onSuccess { refreshProjects() }
                .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    fun deleteProjectForever(projectId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteForever(projectId) }
                .onSuccess { refreshProjects() }
                .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    fun dismissRecovery() {
        session.edit().remove(PREF_ACTIVE_PROJECT_ID).putBoolean(PREF_EDITOR_SESSION_OPEN, false).apply()
        uiState = uiState.copy(recoveryProject = null)
    }

    /** Coalesces document metadata changes from the GL thread. */
    fun scheduleAutosave(document: ProjectDocument) {
        if (document.id != openedDocument?.id) return
        openedDocument = document
        pendingSaveDocument = document
        schedulePendingSave()
    }

    fun scheduleTileAutosave(
        document: ProjectDocument,
        payloads: Map<Long, ByteArray>,
        dirty: Map<Long, Set<TileCoord>>,
        onSaved: () -> Unit,
    ) {
        if (document.id != openedDocument?.id) return
        openedDocument = document
        pendingSaveDocument = document
        // LayerTileStore keeps tiles dirty until this acknowledgement. Therefore
        // the newest snapshot always contains every cancelled autosave's tiles.
        pendingLayerPayloads = payloads
        pendingTilesAcknowledgement = onSaved
        schedulePendingSave()
    }

    fun submitThumbnail(image: ThumbnailCapture.Rgba) {
        val document = openedDocument ?: return
        val projectId = document.id
        val sequence = ++latestThumbnailSequence
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) {
                ThumbnailEncoder.encodeWebp(image)
            }
            // Encoders may finish out of order. An older stroke must never
            // replace the preview captured for a newer committed stroke.
            if (sequence != latestThumbnailSequence) return@launch
            if (projectId != openedDocument?.id) {
                // The editor may have just been closed while encoding was in
                // flight. Persist this last valid preview by itself instead of
                // dropping it and leaving the gallery with a stale card. Read
                // current metadata first: the captured callback must not
                // overwrite a later layer/property save with stale metadata.
                runCatching {
                    val currentDocument = withContext(Dispatchers.IO) { repository.open(projectId) }
                    withContext(Dispatchers.IO) { repository.save(currentDocument, emptyMap(), encoded) }
                }
                    .onSuccess { refreshProjects() }
                    .onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
                return@launch
            }
            pendingThumbnailWebp = encoded
            pendingSaveDocument = openedDocument
            schedulePendingSave()
        }
    }

    /**
     * Receives files already encoded by ThumbnailBuildScheduler. Reading their
     * bytes on Dispatchers.IO keeps Android's main thread free; autosave then
     * publishes the previews together with document.json in one .wetink write.
     */
    fun submitThumbnailBuild(result: ThumbnailBuildResult) {
        val document = openedDocument ?: return
        val projectId = document.id
        viewModelScope.launch {
            val previews = withContext(Dispatchers.IO) {
                val project = result.projectPreview
                    ?.takeIf(File::isFile)
                    ?.readBytes()
                val layers = result.layerPreviews.mapNotNull { (layerId, file) ->
                    file.takeIf(File::isFile)?.readBytes()?.let { layerId to it }
                }.toMap()
                project to layers
            }
            if (projectId != openedDocument?.id) return@launch

            previews.first?.let { pendingThumbnailWebp = it }
            if (previews.second.isNotEmpty()) {
                pendingLayerPreviewsWebp = pendingLayerPreviewsWebp + previews.second
            }
            pendingSaveDocument = openedDocument
            schedulePendingSave()
        }
    }

    /**
     * One coalesced save owns document metadata, changed layer payloads and the
     * latest canvas thumbnail. Restarting its debounce is intentional: it keeps
     * those three representations in the same atomic .wetink publication.
     */
    private fun schedulePendingSave() {
        val document = pendingSaveDocument ?: return
        uiState = uiState.copy(isDirty = true)
        scheduleRecoverySnapshot(document)
        autosaveCoordinator.schedule {
            val documentToSave = pendingSaveDocument ?: return@schedule
            val payloadsToSave = pendingLayerPayloads
            val thumbnailToSave = pendingThumbnailWebp
            val layerPreviewsToSave = pendingLayerPreviewsWebp
            val acknowledge = pendingTilesAcknowledgement

            uiState = uiState.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                runCatching {
                    repository.save(
                        document = documentToSave,
                        changedLayerTiles = payloadsToSave,
                        thumbnailWebp = thumbnailToSave,
                        layerPreviewsWebp = layerPreviewsToSave,
                    )
                }
            }
                .onSuccess {
                    if (pendingSaveDocument == documentToSave) {
                        pendingSaveDocument = null
                        pendingLayerPayloads = emptyMap()
                        pendingTilesAcknowledgement = null
                        if (thumbnailToSave != null) pendingThumbnailWebp = null
                        if (layerPreviewsToSave.isNotEmpty()) pendingLayerPreviewsWebp = emptyMap()
                    }
                    repository.clearRecovery(documentToSave.id)
                    uiState = uiState.copy(isDirty = false, isSaving = false)
                    acknowledge?.invoke()
                }
                .onFailure { error ->
                    uiState = uiState.copy(isSaving = false, errorMessage = error.message)
                }
        }
    }

    /**
     * Publishes the crash-recovery snapshot immediately, outside the autosave
     * debounce: a crash can arrive exactly while the user keeps drawing, so the
     * debounced autosave must not own the recovery write.
     */
    private fun scheduleRecoverySnapshot(document: ProjectDocument) {
        val payloads = pendingLayerPayloads
        if (payloads.isEmpty()) return
        recoveryJob?.cancel()
        recoveryJob = viewModelScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching { repository.saveRecovery(document, payloads) }
            }.onFailure { error -> uiState = uiState.copy(errorMessage = error.message) }
        }
    }

    /**
     * Force-flushes pending edits. Called on editor pause: Android may kill
     * the process without onDestroy, so the recovery snapshot plus the real
     * save must land on disk before the app goes to the background.
     */
    fun flushPendingSave() {
        val document = openedDocument ?: return
        val payloads = pendingLayerPayloads
        val docToSave = pendingSaveDocument ?: document
        if (payloads.isEmpty() && pendingSaveDocument == null) return
        val thumbnail = pendingThumbnailWebp
        val layerPreviews = pendingLayerPreviewsWebp
        val acknowledge = pendingTilesAcknowledgement
        viewModelScope.launch {
            uiState = uiState.copy(isSaving = true)
            autosaveCoordinator.flushNow {
                runCatching {
                    repository.save(docToSave, payloads, thumbnail, layerPreviews)
                }.onSuccess {
                    if (pendingSaveDocument == docToSave) {
                        pendingSaveDocument = null
                        pendingLayerPayloads = emptyMap()
                        pendingTilesAcknowledgement = null
                        if (thumbnail != null) pendingThumbnailWebp = null
                        if (layerPreviews.isNotEmpty()) pendingLayerPreviewsWebp = emptyMap()
                    }
                    repository.clearRecovery(docToSave.id)
                    acknowledge?.invoke()
                    uiState = uiState.copy(isDirty = false, isSaving = false)
                }.onFailure { error ->
                    uiState = uiState.copy(isSaving = false, errorMessage = error.message)
                }
            }
        }
    }

    fun closeEditor() {
        val document = openedDocument
        if (document == null) {
            route = AppRoute.Home
            return
        }

        val payloads = pendingLayerPayloads
        val thumbnail = pendingThumbnailWebp
        val layerPreviews = pendingLayerPreviewsWebp
        val acknowledge = pendingTilesAcknowledgement

        // Leave the editor immediately so the UI never blocks on disk I/O, but
        // keep the recovery flag until the final write has actually landed.
        openedDocument = null
        openedProject = null
        route = AppRoute.Home
        pendingSaveDocument = null
        pendingLayerPayloads = emptyMap()
        pendingTilesAcknowledgement = null
        pendingThumbnailWebp = null
        pendingLayerPreviewsWebp = emptyMap()

        viewModelScope.launch {
            uiState = uiState.copy(isSaving = true)
            // flushNow replaces the debounce: it runs the write under the same
            // mutex as autosave, so the two can never interleave.
            val result = runCatching {
                autosaveCoordinator.flushNow {
                    withContext(Dispatchers.IO) {
                        repository.save(document, payloads, thumbnail, layerPreviews)
                    }
                }
            }
            result
                .onSuccess {
                    repository.clearRecovery(document.id)
                    // Only now is it safe to forget the interrupted session.
                    dismissRecovery()
                    acknowledge?.invoke()
                    uiState = uiState.copy(isDirty = false, isSaving = false)
                }
                .onFailure { error ->
                    // Keep the recovery flag set so the next launch offers restore.
                    uiState = uiState.copy(isSaving = false, errorMessage = error.message)
                }
            refreshProjects()
        }
    }


    private fun markEditorSession(projectId: String) {
        session.edit()
            .putString(PREF_ACTIVE_PROJECT_ID, projectId)
            .putBoolean(PREF_EDITOR_SESSION_OPEN, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "wetinknext_project_session"
        private const val PREF_ACTIVE_PROJECT_ID = "active_project_id"
        private const val PREF_EDITOR_SESSION_OPEN = "editor_session_open"
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(AppViewModel::class.java))
            return AppViewModel(
                repository = FileProjectRepository(File(appContext.filesDir, "projects")),
                session = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ) as T
        }
    }
}
