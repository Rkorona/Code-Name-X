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
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutHeading
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.component.AddProjectAction
import com.example.myapplication.ui.component.AddProjectSheet
import com.example.myapplication.ui.component.AppBottomNavBar
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectLanguage
import com.example.myapplication.ui.model.ProjectType
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<Project>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    selectedProject: Project? = null,
    onProjectClick: (Project) -> Unit = {},
    onProjectSheetDismiss: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
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
            // 根据不同的 Tab 渲染对应的顶栏，保证视觉隔离
            when (selectedTab) {
                0 -> { // 项目列表顶栏
                    if (isSearchActive) {
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
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "搜索",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Box {
                                    IconButton(onClick = { sortMenuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Outlined.SortByAlpha,
                                            contentDescription = "排序",
                                            tint = if (sortOrder == ProjectSortOrder.DEFAULT)
                                                MaterialTheme.colorScheme.onSurface
                                            else
                                                MaterialTheme.colorScheme.primary
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
                }
                1 -> {
                    TopAppBar(
                        title = { Text("GitHub Repositories", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
                2 -> { // 全新终端顶栏
                    TopAppBar(
                        title = { Text("Linux Terminal (Debian)", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
                3 -> {
                    TopAppBar(
                        title = { Text("设置", fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            }
        },
        floatingActionButton = {
            // 只在项目列表页 (Tab 0) 显示创建项目的悬浮按钮
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
            AppBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        // 根据选中的 Tab 决定页面主体的渲染内容
        when (selectedTab) {
            0 -> {
                ProjectList(
                    projects = displayedProjects,
                    searchQuery = searchQuery,
                    sortOrder = sortOrder,
                    isSearchActive = isSearchActive,
                    onProjectClick = onProjectClick,
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
            2 -> {
                // 渲染全新的 PRoot Debian 终端交互页面
                TerminalScreen(
                    modifier = Modifier.padding(innerPadding)
                )
            }
            3 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("全局设置页面（预留）", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        items(projects, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                onClick = { onProjectClick(project) }
            )
        }
    }
}

// ─────────────────────────────────────────────
// 项目卡片及语言图标组件（保持不变）
// ─────────────────────────────────────────────
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tickMs = System.currentTimeMillis()
        }
    }

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
