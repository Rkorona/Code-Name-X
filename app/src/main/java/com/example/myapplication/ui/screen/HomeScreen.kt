package com.example.myapplication.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.component.AddProjectAction
import com.example.myapplication.ui.component.AddProjectSheet
import com.example.myapplication.ui.component.AppBottomNavBar
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectType

// ---------- Screen ----------
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
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
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
                    // Search button
                    IconButton(onClick = {}) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Sort button with dropdown
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.SortByAlpha,
                                contentDescription = "排序",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("按名称排序") },
                                onClick = { sortMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("按修改时间排序") },
                                onClick = { sortMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("按类型排序") },
                                onClick = { sortMenuExpanded = false }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
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
            projects = projects,
            onProjectClick = onProjectClick,
            modifier = Modifier.padding(innerPadding)
        )
    }

    // Bottom Sheet：在 Scaffold 外层渲染，避免被 innerPadding 影响
    if (showAddSheet) {
        AddProjectSheet(
            onAction = { action ->
                when (action) {
                    AddProjectAction.NEW_LOCAL    -> onNewLocalProject()
                    AddProjectAction.CLONE_GITHUB -> onCloneGithub()
                    AddProjectAction.IMPORT_FILE  -> onImportFile()
                    AddProjectAction.FROM_TEMPLATE -> onFromTemplate()
                }
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // 文件树底部弹窗：点击项目后弹出，无需跳转新页面
    if (selectedProject != null) {
        FileExplorerSheet(
            project = selectedProject,
            onDismiss = onProjectSheetDismiss,
            onOpenFile = onOpenFile
        )
    }
}

// ---------- Project List ----------
@Composable
fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    if (projects.isEmpty()) {
        // 空列表提示
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
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Projects",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                AvatarGroup()
            }
        }

        // Project cards
        items(projects, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                onClick = { onProjectClick(project) }
            )
        }
    }
}

// ---------- Project Card ----------
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = when {
            // Mimic Termius grouped card style with rounded groups
            else -> RoundedCornerShape(12.dp)
        },
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
            // Project type icon block (mimics Termius colored square icon)
            ProjectIcon(
                color = project.iconColor,
                type = project.type
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Name + description
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

            // 右侧徽章：仅按项目来源类型区分
            Spacer(modifier = Modifier.width(8.dp))
            when (project.type) {
                ProjectType.LOCAL  -> LocalBadge()
                ProjectType.GITHUB -> GithubBadge()
            }
        }
    }
}

// ---------- Project Icon ----------
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
            ProjectType.LOCAL -> Icons.Outlined.FolderOpen
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

// ---------- LOCAL 徽章 ----------
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

// ---------- GITHUB 徽章 ----------
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

// ---------- Avatar Group (decorative) ----------
@Composable
fun AvatarGroup() {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        // User avatar placeholder
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
        // Add collaborator button
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
