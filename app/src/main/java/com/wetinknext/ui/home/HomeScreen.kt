package com.wetinknext.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wetinknext.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Minimal models for Home UI shell
data class HomeProject(
    val id: String,
    val name: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val updatedAtMs: Long,
    val thumbnailBytes: ByteArray? = null,
    val thumbnailVersion: Long = 0L,
)

data class DeletedHomeProject(
    val project: HomeProject,
    val deletedAtMs: Long,
)

private data class ProjectThumbnailKey(
    val projectId: String,
    val version: Long,
)

/** Bytes are primed from ProjectSummary; decoded bitmaps survive grid row recycling. */
private object ProjectThumbnailBitmapCache {
    private val encoded = LruCache<ProjectThumbnailKey, ByteArray>(32)
    private val decoded = LruCache<ProjectThumbnailKey, ImageBitmap>(32)

    fun prime(key: ProjectThumbnailKey, bytes: ByteArray?) {
        if (bytes != null) encoded.put(key, bytes)
    }

    fun bitmap(key: ProjectThumbnailKey): ImageBitmap? = decoded.get(key)

    fun decode(key: ProjectThumbnailKey): ImageBitmap? {
        decoded.get(key)?.let { return it }
        val bytes = encoded.get(key) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?.asImageBitmap()
            ?.also { decoded.put(key, it) }
    }
}

@Composable
private fun rememberProjectThumbnail(
    projectId: String,
    version: Long,
): ImageBitmap? {
    val key = remember(projectId, version) { ProjectThumbnailKey(projectId, version) }
    // Preserve the old preview for this project until a newly saved WebP is decoded.
    var bitmap by remember(projectId) { mutableStateOf(ProjectThumbnailBitmapCache.bitmap(key)) }

    LaunchedEffect(key) {
        withContext(Dispatchers.Default) {
            ProjectThumbnailBitmapCache.decode(key)
        }?.let { bitmap = it }
    }
    return bitmap
}

@Composable
fun HomeScreen(
    theme: AppTheme,
    projects: List<HomeProject>,
    deletedProjects: List<DeletedHomeProject> = emptyList(),
    onOpenProject: (HomeProject) -> Unit,
    onCreateProject: (name: String, width: Int, height: Int, dpi: Int, colorProfile: String) -> Unit,
    onRenameProject: (HomeProject, String) -> Unit,
    onDuplicateProject: (HomeProject) -> Unit,
    onDeleteProject: (HomeProject) -> Unit,
    onRestoreProject: (DeletedHomeProject) -> Unit,
    onDeleteForever: (DeletedHomeProject) -> Unit,
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<HomeProject?>(null) }
    var deleting by remember { mutableStateOf<HomeProject?>(null) }
    var showTrash by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.panelBgSolid),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeTopBar(
                theme = theme,
                total = projects.size,
                onCreate = { showNewDialog = true },
                onTrash = { showTrash = true },
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectTile(
                        project = project,
                        theme = theme,
                        onOpen = { onOpenProject(project) },
                        onRename = { renaming = project },
                        onDuplicate = { onDuplicateProject(project) },
                        onDelete = { deleting = project },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewCanvasDialog(
            theme = theme,
            defaultWidth = 2800,
            defaultHeight = 1840,
            onDismiss = { showNewDialog = false },
            onConfirm = { name, width, height, dpi, colorProfile ->
                showNewDialog = false
                onCreateProject(name, width, height, dpi, colorProfile)
            },
        )
    }

    renaming?.let { project ->
        RenameDialog(
            theme = theme,
            initial = project.name,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                onRenameProject(project, newName)
                renaming = null
            },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProject(project)
                        deleting = null
                    },
                ) {
                    Text("Удалить", color = theme.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("Отмена", color = theme.textSecondary)
                }
            },
            title = { Text("Удалить холст?", color = theme.textPrimary) },
            text = { Text(project.name, color = theme.textSecondary) },
            containerColor = theme.panelBgSolid,
        )
    }

    if (showTrash) {
        TrashDialog(
            theme = theme,
            deletedProjects = deletedProjects,
            onRestore = { deleted ->
                onRestoreProject(deleted)
            },
            onDeleteForever = { deleted ->
                onDeleteForever(deleted)
            },
            onDismiss = { showTrash = false },
        )
    }
}

@Composable
private fun HomeTopBar(
    theme: AppTheme,
    total: Int,
    onCreate: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Мои работы",
                color = theme.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Всего: $total",
                color = theme.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        TopIconButton(theme = theme, onClick = onCreate) {
            Icon(Icons.Outlined.Add, null, tint = theme.textPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(10.dp))
        TopIconButton(theme = theme, onClick = onTrash) {
            Icon(Icons.Outlined.Delete, null, tint = theme.textPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TopIconButton(
    theme: AppTheme,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.panelBg)
            .border(1.dp, theme.panelStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ProjectTile(
    project: HomeProject,
    theme: AppTheme,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.panelBg)
            .border(1.2.dp, theme.panelStroke, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
    ) {
        ProjectPreview(project = project, theme = theme)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = theme.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${project.canvasWidth} x ${project.canvasHeight} px · ${formatDate(project.updatedAtMs)}",
                    color = theme.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { menuExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.MoreVert, null, tint = theme.textSecondary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(theme.panelBg)
                ) {
                    DropdownMenuItem(
                        text = { Text("Переименовать", color = theme.textPrimary) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = theme.textSecondary) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Дублировать", color = theme.textPrimary) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = theme.textSecondary) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить", color = theme.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = theme.danger) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectPreview(
    project: HomeProject,
    theme: AppTheme,
) {
    // Placeholder for actual preview loading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(
                project.canvasWidth.toFloat() /
                    project.canvasHeight.coerceAtLeast(1).toFloat(),
            )
            .clip(RoundedCornerShape(6.dp))
            .background(theme.panelInset)
            .border(1.dp, theme.panelStroke, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        ProjectThumbnailBitmapCache.prime(
            key = ProjectThumbnailKey(project.id, project.thumbnailVersion),
            bytes = project.thumbnailBytes,
        )
        val preview = rememberProjectThumbnail(
            projectId = project.id,
            version = project.thumbnailVersion,
        )
        if (preview != null) {
            Image(bitmap = preview, contentDescription = "Превью ${project.name}", modifier = Modifier.fillMaxSize())
            return@Box
        }
        EmptyProjectPlaceholder(theme)
    }
}

@Composable
private fun EmptyProjectPlaceholder(theme: AppTheme) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFAFAFA))
            .border(1.dp, theme.panelStroke, RoundedCornerShape(4.dp)),
    )
}

@Composable
private fun RenameDialog(
    theme: AppTheme,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val cleaned = name.trim()
                    if (cleaned.isNotEmpty()) onConfirm(cleaned)
                },
            ) {
                Text("Готово", color = theme.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = theme.textSecondary)
            }
        },
        title = { Text("Название", color = theme.textPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.panelStroke,
                    focusedTextColor = theme.textPrimary,
                    unfocusedTextColor = theme.textPrimary
                )
            )
        },
        containerColor = theme.panelBgSolid,
    )
}

@Composable
private fun TrashDialog(
    theme: AppTheme,
    deletedProjects: List<DeletedHomeProject>,
    onRestore: (DeletedHomeProject) -> Unit,
    onDeleteForever: (DeletedHomeProject) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово", color = theme.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        title = { Text("Корзина", color = theme.textPrimary) },
        text = {
            if (deletedProjects.isEmpty()) {
                Text("Удалённые работы хранятся здесь 3 дня.", color = theme.textSecondary, fontSize = 13.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Работы хранятся 3 дня.",
                        color = theme.textSecondary,
                        fontSize = 12.sp,
                    )
                    deletedProjects.sortedByDescending { it.deletedAtMs }.forEach { deleted ->
                        DeletedProjectRow(
                            deleted = deleted,
                            theme = theme,
                            onRestore = { onRestore(deleted) },
                            onDeleteForever = { onDeleteForever(deleted) },
                        )
                    }
                }
            }
        },
        containerColor = theme.panelBgSolid,
    )
}

@Composable
private fun DeletedProjectRow(
    deleted: DeletedHomeProject,
    theme: AppTheme,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val project = deleted.project
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.panelBg)
            .border(1.dp, theme.panelStroke, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.panelInset)
                .border(1.dp, theme.panelStroke, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .aspectRatio(project.canvasWidth.toFloat() / project.canvasHeight.coerceAtLeast(1).toFloat())
                    .background(Color(0xFFFAFAFA)),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                project.name,
                color = theme.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Осталось: ${trashDaysLeft(deleted.deletedAtMs)} дн.",
                color = theme.textSecondary,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore) {
                    Text("Восстановить", color = theme.accent, fontSize = 12.sp)
                }
                TextButton(onClick = onDeleteForever) {
                    Text("Удалить", color = theme.textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun formatDate(timeMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(timeMs))

private fun trashDaysLeft(deletedAtMs: Long): Int {
    val retentionMs = 3L * 24L * 60L * 60L * 1000L
    val remainingMs = (retentionMs - (System.currentTimeMillis() - deletedAtMs)).coerceAtLeast(0L)
    return ((remainingMs + 24L * 60L * 60L * 1000L - 1L) / (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(1)
}
