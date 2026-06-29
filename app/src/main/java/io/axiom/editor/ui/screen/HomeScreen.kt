package io.axiom.editor.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.axiom.editor.ui.component.AddProjectAction
import io.axiom.editor.ui.component.AddProjectSheet
import io.axiom.editor.ui.component.AppBottomNavBar
import io.axiom.editor.ui.component.AppTopBar
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectLanguage
import io.axiom.editor.ui.model.ProjectType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────
// 六边形 Shape（Node.js 图标专用）
// ─────────────────────────────────────────────
private class HexagonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(size.width, size.height) / 2f * 0.92f
        val path = Path()
        for (i in 0..5) {
            val angle = PI / 3.0 * i - PI / 6.0
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

// ─────────────────────────────────────────────
// 相对时间格式化
// ─────────────────────────────────────────────
fun formatRelativeTime(lastModifiedMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = nowMs - lastModifiedMs
    return when {
        diff < 60_000L         -> "刚刚"
        diff < 3_600_000L      -> "${diff / 60_000} 分钟前"
        diff < 86_400_000L     -> "${diff / 3_600_000} 小时前"
        diff < 2_592_000_000L  -> "${diff / 86_400_000} 天前"
        diff < 31_536_000_000L -> "${diff / 2_592_000_000} 个月前"
        else                   -> "${diff / 31_536_000_000L} 年前"
    }
}

// ─────────────────────────────────────────────
// 排序方式枚举
// ─────────────────────────────────────────────
enum class ProjectSortOrder(val label: String) {
    DEFAULT("默认顺序"),
    NAME_ASC("名称 A → Z"),
    NAME_DESC("名称 Z → A"),
    TYPE_LOCAL_FIRST("本地项目优先"),
    TYPE_GITHUB_FIRST("GitHub 项目优先"),
}

// ─────────────────────────────────────────────
// 项目列表排序 + 过滤工具函数
// ─────────────────────────────────────────────
private fun filterAndSortProjects(
    projects: List<Project>,
    query: String,
    order: ProjectSortOrder
): List<Project> {
    val filtered = if (query.isBlank()) {
        projects
    } else {
        val lower = query.trim().lowercase()
        projects.filter { project ->
            project.name.lowercase().contains(lower) ||
                project.description.lowercase().contains(lower)
        }
    }

    return when (order) {
        ProjectSortOrder.DEFAULT -> filtered
        ProjectSortOrder.NAME_ASC ->
            filtered.sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        ProjectSortOrder.NAME_DESC ->
            filtered.sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(b.name, a.name) }
        ProjectSortOrder.TYPE_LOCAL_FIRST ->
            filtered.sortedWith(
                compareBy<Project>(
                    { if (it.type == ProjectType.LOCAL) 0 else 1 },
                    { it.name.lowercase() }
                )
            )
        ProjectSortOrder.TYPE_GITHUB_FIRST ->
            filtered.sortedWith(
                compareBy<Project>(
                    { if (it.type == ProjectType.GITHUB) 0 else 1 },
                    { it.name.lowercase() }
                )
            )
    }
}

// ─────────────────────────────────────────────
// HomeScreen (主骨架重构)
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    selectedProject: Project? = null,
    settingsViewModel: io.axiom.editor.data.SettingsViewModel? = null,
    onProjectClick: (Project) -> Unit = {},
    onProjectSheetDismiss: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onNewLocalProject: () -> Unit = {},
    onCloneGithub: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onFromTemplate: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onEditProject: (Project, String) -> Unit = { _, _ -> },
    onDeleteProject: (Project) -> Unit = {},
    onCopyProject: (Project) -> Unit = {}
) {
    // ── 搜索状态 ──
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ── 排序状态 ──
    var sortOrder by remember { mutableStateOf(ProjectSortOrder.DEFAULT) }

    // ── AddProject sheet 状态 ──
    var showAddSheet by remember { mutableStateOf(false) }

    // ── 长按选中状态 ──
    var longPressedProjectId by remember { mutableStateOf<String?>(null) }

    // ── 编辑对话框状态 ──
    var editingProject by remember { mutableStateOf<Project?>(null) }

    // ── 滚动状态（各 Tab 独立追踪，用于顶栏背景变化）──
    val listState = rememberLazyListState()
    val settingsListState = rememberLazyListState()

    fun LazyListState.isScrolled() =
        firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

    val isScrolled by remember(selectedTab) {
        derivedStateOf {
            when (selectedTab) {
                0    -> listState.isScrolled()
                3    -> settingsListState.isScrolled()
                else -> false
            }
        }
    }

    // ── 派生显示列表：过滤 + 排序 ──
    val displayedProjects = remember(projects, searchQuery, sortOrder) {
        filterAndSortProjects(projects, searchQuery, sortOrder)
    }

    Scaffold(
        topBar = {
            AppTopBar(
                selectedTab = selectedTab,
                sortOrder = sortOrder,
                onSortOrderChange = { sortOrder = it },
                isSearchActive = isSearchActive,
                onSearchActiveChange = { isSearchActive = it },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isScrolled = isScrolled
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建项目",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        bottomBar = {
            if (!WindowInsets.isImeVisible) {
                AppBottomNavBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> {
                ProjectList(
                    projects = displayedProjects,
                    searchQuery = searchQuery,
                    sortOrder = sortOrder,
                    isSearchActive = isSearchActive,
                    onProjectClick = { project ->
                        if (longPressedProjectId != null) {
                            longPressedProjectId = null
                        } else {
                            onProjectClick(project)
                        }
                    },
                    onTerminalClick = onTerminalClick,
                    longPressedProjectId = longPressedProjectId,
                    onLongPress = { project -> longPressedProjectId = project.id },
                    onCancelLongPress = { longPressedProjectId = null },
                    onEditProject = { project -> editingProject = project },
                    onDeleteProject = { project ->
                        longPressedProjectId = null
                        onDeleteProject(project)
                    },
                    onCopyProject = { project ->
                        longPressedProjectId = null
                        onCopyProject(project)
                    },
                    onNewProject = {
                        longPressedProjectId = null
                        showAddSheet = true
                    },
                    listState = listState,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            1 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("GitHub 克隆管理页面（预留）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            3 -> {
                if (settingsViewModel != null) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        modifier = Modifier.padding(innerPadding),
                        listState = settingsListState
                    )
                }
            }
        }
    }

    // ── Bottom Sheet：新建/导入项目 ──────────────────────
    if (showAddSheet) {
        AddProjectSheet(
            onAction = { action ->
                when (action) {
                    AddProjectAction.NEW_LOCAL     -> onNewLocalProject()
                    AddProjectAction.CLONE_GITHUB  -> onCloneGithub()
                    AddProjectAction.IMPORT_FILE   -> onImportFile()
                    AddProjectAction.FROM_TEMPLATE -> onFromTemplate()
                }
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // ── 编辑项目对话框 ─────────────────────────────────
    if (editingProject != null) {
        EditProjectDialog(
            project = editingProject!!,
            onConfirm = { newName ->
                onEditProject(editingProject!!, newName)
                editingProject = null
                longPressedProjectId = null
            },
            onDismiss = {
                editingProject = null
            }
        )
    }

    // ── 文件树底部弹窗（点击项目后弹出） ─────────────────
    if (selectedProject != null) {
        FileExplorerSheet(
            project = selectedProject,
            onDismiss = onProjectSheetDismiss,
            onOpenFile = onOpenFile
        )
    }
}

// ─────────────────────────────────────────────
// 编辑项目对话框
// ─────────────────────────────────────────────
@Composable
fun EditProjectDialog(
    project: Project,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var projectName by remember { mutableStateOf(project.name) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "重命名项目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            OutlinedTextField(
                value = projectName,
                onValueChange = { input ->
                    val sanitized = input.filter { ch ->
                        ch != '/' && ch != '\\' && ch != ':' &&
                        ch != '*' && ch != '?' && ch != '"'  &&
                        ch != '<' && ch != '>'  && ch != '|'
                    }
                    projectName = sanitized
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text("项目名称") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = if (errorMessage != null) {
                    { Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (projectName.isNotBlank()) onConfirm(projectName.trim())
                    else errorMessage = "项目名称不能为空"
                }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (projectName.isNotBlank()) onConfirm(projectName.trim())
                    else errorMessage = "项目名称不能为空"
                },
                enabled = projectName.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("取消")
            }
        }
    )
}

// ─────────────────────────────────────────────
// 项目列表
// ─────────────────────────────────────────────
@Composable
fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    onTerminalClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    sortOrder: ProjectSortOrder = ProjectSortOrder.DEFAULT,
    isSearchActive: Boolean = false,
    longPressedProjectId: String? = null,
    onLongPress: (Project) -> Unit = {},
    onCancelLongPress: () -> Unit = {},
    onEditProject: (Project) -> Unit = {},
    onDeleteProject: (Project) -> Unit = {},
    onCopyProject: (Project) -> Unit = {},
    onNewProject: () -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Projects section header ──
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchActive && searchQuery.isNotBlank()) {
                    Text(
                        text = "找到 ${projects.size} 个项目",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "Projects",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (sortOrder != ProjectSortOrder.DEFAULT) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = sortOrder.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    AvatarGroup()
                }
            }
        }

        // ── Project cards or empty state ──
        if (projects.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    if (isSearchActive && searchQuery.isNotBlank()) {
                        Text(
                            text = "未找到匹配「$searchQuery」的项目",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "请检查拼写或尝试其他关键词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = "还没有项目",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "点击右下角的 + 按钮来添加项目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        } else {
            items(projects, key = { it.id }) { project ->
                val isLongPressed = longPressedProjectId == project.id
                ProjectCard(
                    project = project,
                    isLongPressed = isLongPressed,
                    onClick = { onProjectClick(project) },
                    onLongPress = { onLongPress(project) },
                    onCancelLongPress = onCancelLongPress,
                    onEditClick = { onEditProject(project) },
                    onDeleteClick = { onDeleteProject(project) },
                    onCopyClick = { onCopyProject(project) },
                    onNewClick = onNewProject
                )
            }
        }

        // ── Terminal section ──
        item {
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 4.dp)
            )
        }
        item {
            TerminalCard(onClick = onTerminalClick)
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ─────────────────────────────────────────────
// 项目卡片及语言图标组件
// ─────────────────────────────────────────────
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLongPressed: Boolean = false,
    onLongPress: () -> Unit = {},
    onCancelLongPress: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onCopyClick: () -> Unit = {},
    onNewClick: () -> Unit = {}
) {
    var tickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tickMs = System.currentTimeMillis()
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 长按操作栏（编辑 + 菜单）──
        AnimatedVisibility(
            visible = isLongPressed,
            enter = expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = tween(200)
            ) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = tween(180)
            ) + fadeOut(animationSpec = tween(120))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 编辑按钮
                Surface(
                    onClick = onEditClick,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.shadow(2.dp, RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "编辑",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 菜单按钮（含下拉菜单）
                Box {
                    Surface(
                        onClick = { menuExpanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.shadow(2.dp, RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "菜单",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 0.dp,
                        shadowElevation = 6.dp
                    ) {
                        DropdownMenuItem(
                            text = { Text("全新", fontWeight = FontWeight.Medium) },
                            onClick = {
                                menuExpanded = false
                                onNewClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.NoteAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("复制", fontWeight = FontWeight.Medium) },
                            onClick = {
                                menuExpanded = false
                                onCopyClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) },
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }

        // ── 卡片本体 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(isLongPressed) {
                    detectTapGestures(
                        onTap = {
                            if (isLongPressed) onCancelLongPress() else onClick()
                        },
                        onLongPress = {
                            if (!isLongPressed) onLongPress()
                        }
                    )
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isLongPressed)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else
                    MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageIcon(language = project.language)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        InlineTypeBadge(type = project.type)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = formatRelativeTime(project.lastModified, tickMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 终端入口卡片
// ─────────────────────────────────────────────
@Composable
fun TerminalCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C2330)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ">_",
                    color = Color(0xFF22C55E),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Linux Terminal",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF1C2330)
                    ) {
                        Text(
                            text = "DEBIAN",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = Color(0xFF22C55E),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Debian 13 · PRoot 容器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun LanguageIcon(language: ProjectLanguage, modifier: Modifier = Modifier) {
    val shape: Shape = if (language == ProjectLanguage.NODEJS) HexagonShape() else RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(shape)
            .background(language.bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = language.symbol,
            color = language.fgColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize = when (language.symbol.length) {
                1    -> 22.sp
                2    -> 17.sp
                else -> 13.sp
            },
            letterSpacing = 0.sp
        )
    }
}

@Composable
fun InlineTypeBadge(type: ProjectType) {
    val bgColor = if (type == ProjectType.LOCAL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (type == ProjectType.LOCAL) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (type == ProjectType.LOCAL) "LOCAL" else "GITHUB"
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = textColor,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
fun AvatarGroup() {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "D",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}
