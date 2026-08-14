package com.wetinknext.app

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import com.wetinknext.data.project.CreateProjectRequest
import com.wetinknext.data.project.ProjectSummary
import com.wetinknext.ui.home.HomeProject
import com.wetinknext.ui.home.HomeScreen
import com.wetinknext.ui.home.DeletedHomeProject
import com.wetinknext.ui.theme.WetInkTheme
import com.wetinknext.ui.theme.rememberThemeController

@Composable
fun AppRoot(viewModel: AppViewModel) {
    when (val currentRoute = viewModel.route) {
        AppRoute.Home -> ProjectHomeRoute(viewModel)
        is AppRoute.Editor -> {
            val document = viewModel.openedDocument
            val loadedProject = viewModel.openedProject
            if (document?.id == currentRoute.projectId && loadedProject?.document?.id == currentRoute.projectId) {
                BackHandler { viewModel.closeEditor() }
                EditorScreen(
                    projectId = currentRoute.projectId,
                    document = document,
                    layerTiles = loadedProject.assets.layerTiles,
                    layerPreviews = loadedProject.assets.layerPreviewsWebp,
                    saveStatus = when {
                        viewModel.uiState.isSaving -> "Сохраняется..."
                        viewModel.uiState.isDirty -> "Есть несохранённые изменения"
                        else -> "Сохранено"
                    },
                    onDocumentChanged = viewModel::scheduleAutosave,
                    onDirtyLayerTiles = viewModel::scheduleTileAutosave,
                    onThumbnailCaptured = viewModel::submitThumbnail,
                    onThumbnailBuildSaved = viewModel::submitThumbnailBuild,
                    onBack = viewModel::closeEditor,
                    onEditorPause = viewModel::flushPendingSave,
                )
            } else {
                LaunchedEffect(currentRoute.projectId) {
                    viewModel.openProject(currentRoute.projectId)
                }
            }
        }
    }
}

/** Kept for existing previews and callers while MainActivity uses AppRoot. */
@Composable
fun WetInkApp(viewModel: AppViewModel) = AppRoot(viewModel)

@Composable
private fun ProjectHomeRoute(viewModel: AppViewModel) {
    val themeController = rememberThemeController()
    val theme = themeController.current
    val state = viewModel.uiState

    WetInkTheme(theme = theme, fontMode = themeController.font) {
        HomeScreen(
            theme = theme,
            projects = state.projects.map(ProjectSummary::toHomeProject),
            deletedProjects = state.trashedProjects.map { trashed ->
                DeletedHomeProject(
                    project = trashed.project.toHomeProject(),
                    deletedAtMs = trashed.deletedAt,
                )
            },
            onOpenProject = { viewModel.openProject(it.id) },
            onCreateProject = { name, width, height, dpi, colorProfile ->
                viewModel.createProject(
                    CreateProjectRequest(
                        name = name,
                        width = width,
                        height = height,
                        dpi = dpi,
                        colorProfile = colorProfile,
                    ),
                )
            },
            onRenameProject = { project, name -> viewModel.renameProject(project.id, name) },
            onDuplicateProject = { project -> viewModel.duplicateProject(project.id) },
            onDeleteProject = { project -> viewModel.moveProjectToTrash(project.id) },
            onRestoreProject = { deleted -> viewModel.restoreProject(deleted.project.id) },
            onDeleteForever = { deleted -> viewModel.deleteProjectForever(deleted.project.id) },
        )

        state.recoveryProject?.let { project ->
            AlertDialog(
                onDismissRequest = viewModel::dismissRecovery,
                title = { Text("Восстановить работу?") },
                text = { Text("Приложение было закрыто во время работы с «${project.name}».") },
                confirmButton = {
                    TextButton(onClick = { viewModel.openProject(project.id) }) {
                        Text("Открыть", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissRecovery) {
                        Text("Не сейчас")
                    }
                },
            )
        }
    }
}

private fun ProjectSummary.toHomeProject(): HomeProject = HomeProject(
    id = id,
    name = name,
    canvasWidth = width,
    canvasHeight = height,
    updatedAtMs = updatedAt,
    thumbnailBytes = thumbnailBytes,
    thumbnailVersion = thumbnailVersion,
)
