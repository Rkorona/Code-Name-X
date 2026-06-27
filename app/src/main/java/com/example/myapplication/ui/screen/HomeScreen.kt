package com.example.myapplication.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.component.AddProjectAction
import com.example.myapplication.ui.component.AddProjectSheet
import com.example.myapplication.ui.component.AppBottomNavBar
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectType

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

/**
 * 根据搜索词和排序方式对项目列表进行过滤和排序。
 * [projects] 原始列表（顺序代表创建时间，index 0 最新）
 * [query]    搜索关键词（忽略大小写，空串 = 不过滤）
 * [order]    排序方式
 */
private fun filterAndSortProjects(
    projects: List<Project>,
    query: String,
    order: ProjectSortOrder
): List<Project> {
    // 1. 过滤：按名称或描述匹配搜索词
    val filtered = if (query.isBlank()) {
        projects
    } else {
        val lower = query.trim().lowercase()
        projects.filter { project ->
            project.name.lowercase().contains(lower) ||
                project.description.lowercase().contains(lower)
        }
    }

    // 2. 排序
    return when (order) {
        ProjectSortOrder.DEFAULT -> filtered  // 保持原顺序（newest first）

        ProjectSortOrder.NAME_ASC ->
            filtered.sortedWith(compareBy<Project>(String.CASE_INSENSITIVE_ORDER) { it.name })

        ProjectSortOrder.NAME_DESC ->
            // reversed() 是 Comparator 的 Java8 方法，Kotlin 可直接调用
            filtered.sortedWith(compareBy<Project>(String.CASE_INSENSITIVE_ORDER) { it.name }.reversed())

        ProjectSortOrder.TYPE_LOCAL_FIRST ->
            // 多选择器 compareBy：先按类型（LOCAL=0，其他=1），再按名称忽略大小写
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
// HomeScreen
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    selectedProject: Project? = null,
    onProjectClick: (Project) -> Unit = {},
    onProjectSheetDismiss: () -> Unit = {},
    onOpenFile: (EditorFile) -> Unit = {},
    onNewLocalProject: () -> Unit = {},
    onCloneGithub: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onFromTemplate: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    // ── 搜索状态 ──
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 排序状态 ──
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(ProjectSortOrder.DEFAULT) }

    // ── AddProject sheet 状态 ──
    var showAddSheet by remember { mutableStateOf(false) }

    // ── 派生显示列表：过滤 + 排序 ──
    val displayedProjects = remember(projects, searchQuery, sortOrder) {
        filterAndSortProjects(projects, searchQuery, sortOrder)
    }

    // 搜索栏激活时自动弹出键盘
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                // ── 搜索模式 TopAppBar ──────────────────────────
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                keyboardController?.hide()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭搜索",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            placeholder = {
                                Text(
                                    text = "搜索项目名称或路径…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { keyboardController?.hide() }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                errorIndicatorColor = Color.Transparent,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    actions = {
                        // 有内容时显示清空按钮
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空搜索词",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                // ── 普通模式 TopAppBar ──────────────────────────
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Projects",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        // 搜索按钮
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // 排序按钮（AZ 图标）+ 下拉菜单
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.SortByAlpha,
                                    contentDescription = "排序",
                                    tint = if (sortOrder == ProjectSortOrder.DEFAULT)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.primary  // 激活状态用主色提示
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                ProjectSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                color = if (sortOrder == order)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (sortOrder == order)
                                                    FontWeight.SemiBold
                                                else
                                                    FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            sortOrder = order
                                            sortMenuExpanded = false
                                        },
                                        leadingIcon = if (sortOrder == order) ({
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "已选中",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }) else null
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        floatingActionButton = {
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
        },
        bottomBar = {
            AppBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        ProjectList(
            projects = displayedProjects,
            searchQuery = searchQuery,
            sortOrder = sortOrder,
            isSearchActive = isSearchActive,
            onProjectClick = onProjectClick,
            modifier = Modifier.padding(innerPadding)
        )
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
// 项目列表（支持搜索结果提示 + 空态）
// ─────────────────────────────────────────────

@Composable
fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    sortOrder: ProjectSortOrder = ProjectSortOrder.DEFAULT,
    isSearchActive: Boolean = false,
) {
    // 空态
    if (projects.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
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
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 区块标题：显示数量和当前排序方式
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 搜索时显示结果数量
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
                // 非默认排序时显示排序标签
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

        // 项目卡片
        items(projects, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                onClick = { onProjectClick(project) }
            )
        }
    }
}

// ─────────────────────────────────────────────
// 项目卡片
// ─────────────────────────────────────────────

@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProjectIcon(
                color = project.iconColor,
                type = project.type
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            when (project.type) {
                ProjectType.LOCAL  -> LocalBadge()
                ProjectType.GITHUB -> GithubBadge()
            }
        }
    }
}

// ─────────────────────────────────────────────
// 项目图标
// ─────────────────────────────────────────────

@Composable
fun ProjectIcon(
    color: Color,
    type: ProjectType,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        val icon: ImageVector = when (type) {
            ProjectType.LOCAL  -> Icons.Outlined.FolderOpen
            ProjectType.GITHUB -> Icons.Outlined.Hub
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

// ─────────────────────────────────────────────
// 徽章组件
// ─────────────────────────────────────────────

@Composable
fun LocalBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = "LOCAL",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun GithubBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = "GITHUB",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────
// 头像组（装饰性）
// ─────────────────────────────────────────────

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
