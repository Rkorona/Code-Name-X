package io.axiom.editor.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.axiom.editor.ui.component.AddProjectAction
import io.axiom.editor.ui.component.AddProjectSheet
import io.axiom.editor.ui.component.AppBottomNavBar
import io.axiom.editor.ui.component.AppTopBar
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectLanguage
import io.axiom.editor.ui.model.ProjectType
import io.axiom.editor.ui.theme.AxiomColors
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

    // ── 排序状态（从设置读取初始值）──
    val initialSortOrder = remember(settingsViewModel) {
        when (settingsViewModel?.settings?.defaultSort) {
            io.axiom.editor.data.SortMode.NAME_ASC  -> ProjectSortOrder.NAME_ASC
            io.axiom.editor.data.SortMode.NAME_DESC -> ProjectSortOrder.NAME_DESC
            io.axiom.editor.data.SortMode.TYPE       -> ProjectSortOrder.TYPE_LOCAL_FIRST
            else                                      -> ProjectSortOrder.DEFAULT
        }
    }
    var sortOrder by remember { mutableStateOf(initialSortOrder) }

    // ── AddProject sheet 状态 ──
    var showAddSheet by remember { mutableStateOf(false) }

    // ── 多选状态：长按进入多选，选中态下点击其他条目可继续勾选/取消 ──
    var selectedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ── 编辑对话框状态 ──
    var editingProject by remember { mutableStateOf<Project?>(null) }

    // ── 删除前确认对话框状态 ──
    var pendingDeleteProjects by remember { mutableStateOf<List<Project>>(emptyList()) }

    // ── 滚动状态（各 Tab 独立追踪，用于顶栏背景变化）──
    val listState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val gitHubListState = rememberLazyListState()

    fun LazyListState.isScrolled() =
        firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

    val isScrolled by remember(selectedTab) {
        derivedStateOf {
            when (selectedTab) {
                0    -> listState.isScrolled()
                1    -> gitHubListState.isScrolled()
                3    -> settingsListState.isScrolled()
                else -> false
            }
        }
    }

    // ── GitHub ──
    val gitHubViewModel: GitHubViewModel = viewModel()
    var showGitHubLoginSheet by remember { mutableStateOf(false) }

    // ── 派生显示列表：过滤 + 排序 ──
    val displayedProjects = remember(projects, searchQuery, sortOrder) {
        filterAndSortProjects(projects, searchQuery, sortOrder)
    }

    // ── 选中模式下，系统返回键优先退出选中态 ──
    BackHandler(enabled = selectedProjectIds.isNotEmpty()) {
        selectedProjectIds = emptySet()
    }

    Scaffold(
        topBar = {
            Crossfade(
                targetState = selectedTab == 0 && selectedProjectIds.isNotEmpty(),
                animationSpec = tween(180)
            ) { inSelectionMode ->
                if (inSelectionMode) {
                    val selectedProjects = projects.filter { it.id in selectedProjectIds }
                    SelectionTopBar(
                        selectedCount = selectedProjects.size,
                        onClose = { selectedProjectIds = emptySet() },
                        onEditClick = {
                            selectedProjects.singleOrNull()?.let { editingProject = it }
                        },
                        onNewClick = {
                            selectedProjectIds = emptySet()
                            showAddSheet = true
                        },
                        onCopyClick = {
                            selectedProjectIds = emptySet()
                            selectedProjects.forEach { onCopyProject(it) }
                        },
                        onDeleteClick = {
                            val confirmDelete = settingsViewModel?.settings?.confirmDelete ?: true
                            if (confirmDelete) {
                                pendingDeleteProjects = selectedProjects
                                selectedProjectIds = emptySet()
                            } else {
                                selectedProjectIds = emptySet()
                                selectedProjects.forEach { onDeleteProject(it) }
                            }
                        }
                    )
                } else {
                    AppTopBar(
                        selectedTab = selectedTab,
                        sortOrder = sortOrder,
                        onSortOrderChange = { sortOrder = it },
                        isSearchActive = isSearchActive,
                        onSearchActiveChange = { isSearchActive = it },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        isScrolled = isScrolled,
                        githubTrailingAction = {
                            if (gitHubViewModel.isLoggedIn) {
                                AsyncImage(
                                    model = gitHubViewModel.userAvatarUrl,
                                    contentDescription = "用户头像",
                                    modifier = androidx.compose.ui.Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                                )
                            } else {
                                IconButton(onClick = { showGitHubLoginSheet = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "添加账号",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    )
                }
            }
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
                    onProjectClick = onProjectClick,
                    onTerminalClick = onTerminalClick,
                    selectedProjectIds = selectedProjectIds,
                    onToggleSelect = { project ->
                        selectedProjectIds = if (project.id in selectedProjectIds)
                            selectedProjectIds - project.id
                        else
                            selectedProjectIds + project.id
                    },
                    listState = listState,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            1 -> {
                GitHubScreen(
                    modifier = Modifier.padding(innerPadding),
                    viewModel = gitHubViewModel,
                    listState = gitHubListState,
                    showLoginSheet = showGitHubLoginSheet,
                    onLoginSheetDismiss = { showGitHubLoginSheet = false }
                )
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
                selectedProjectIds = emptySet()
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

    // ── 删除前确认对话框 ─────────────────────────────────
    if (pendingDeleteProjects.isNotEmpty()) {
        val count = pendingDeleteProjects.size
        val desc = if (count == 1) "「${pendingDeleteProjects.first().name}」" else "$count 个项目"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteProjects = emptyList() },
            icon = { androidx.compose.material3.Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("确认删除") },
            text = { Text("将删除 $desc，此操作不可撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDeleteProjects.forEach { onDeleteProject(it) }
                    pendingDeleteProjects = emptyList()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteProjects = emptyList() }) { Text("取消") }
            }
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
// 选中态顶栏（长按选中项目后，替代默认 AppTopBar）
// 参考 Termius 的选中态设计：关闭 + 已选数量 + 编辑 + 更多
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onEditClick: () -> Unit,
    onNewClick: () -> Unit,
    onCopyClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = {
                Text(
                    text = "已选择 $selectedCount 项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消选择"
                    )
                }
            },
            actions = {
                if (selectedCount == 1) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "重命名"
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作"
                        )
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
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp
        )
    }
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
    selectedProjectIds: Set<String> = emptySet(),
    onToggleSelect: (Project) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    val isSelectionActive = selectedProjectIds.isNotEmpty()
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
                val isSelected = project.id in selectedProjectIds
                ProjectCard(
                    project = project,
                    isSelected = isSelected,
                    isSelectionActive = isSelectionActive,
                    onClick = { onProjectClick(project) },
                    onToggleSelect = { onToggleSelect(project) }
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
    isSelected: Boolean = false,
    isSelectionActive: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    var tickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tickMs = System.currentTimeMillis()
        }
    }

    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        else
            MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(180)
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(isSelectionActive) {
                detectTapGestures(
                    onTap = {
                        // 选中态下，点击任意条目都是勾选/取消勾选，可同时多选
                        if (isSelectionActive) onToggleSelect() else onClick()
                    },
                    onLongPress = {
                        if (!isSelectionActive) onToggleSelect()
                    }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crossfade(targetState = isSelected, animationSpec = tween(150)) { selected ->
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    LanguageIcon(language = project.language)
                }
            }
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
