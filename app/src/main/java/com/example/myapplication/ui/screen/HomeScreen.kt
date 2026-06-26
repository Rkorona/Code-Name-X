package com.example.myapplication.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable // ⬅️ 修复 clickable 报错
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
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
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectType

// ---------- Sample Data ----------
private val sampleProjects = listOf(
    Project("1", "MyApplication", "local, android, compose", ProjectType.LOCAL, "今天", Color(0xFFE53935), false),
    Project("2", "sing-box-dashboard", "github, flutter, proxy-ui", ProjectType.GITHUB, "昨天", Color(0xFF1E3A5F), true),
    Project("3", "fishing-venue-app", "github, react-native, management", ProjectType.GITHUB, "3 天前", Color(0xFF1565C0), false)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProjectClick: (Project) -> Unit = {},
    onAddProject: () -> Unit = {}, // ⬅️ 修复 MainActivity 报错，补全回调参数
    onSettingsClick: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(imageVector = Icons.Outlined.Hub, contentDescription = "主页", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = { Text(text = "Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(imageVector = Icons.Outlined.SortByAlpha, contentDescription = "排序", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("按名称排序") }, onClick = { sortMenuExpanded = false })
                            DropdownMenuItem(text = { Text("按修改时间排序") }, onClick = { sortMenuExpanded = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSheet = true },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新建项目", modifier = Modifier.size(28.dp))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Outlined.FolderOpen, "Projects") }, label = { Text("Projects") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Outlined.Hub, "GitHub") }, label = { Text("GitHub") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Outlined.Settings, "设置") }, label = { Text("设置") })
            }
        }
    ) { innerPadding ->
        ProjectList(
            projects = sampleProjects,
            onProjectClick = onProjectClick,
            modifier = Modifier.padding(innerPadding)
        )

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "创建或导入项目",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // 选项 1：克隆 Git 仓库
                    FabSheetItem(
                        icon = Icons.Outlined.Hub,
                        iconColor = Color(0xFF1E3A5F),
                        title = "克隆 Git 仓库",
                        description = "从 GitHub、GitLab 或自定义 URL 克隆远程项目",
                        onClick = {
                            showSheet = false
                        }
                    )

                    // 选项 2：新建本地项目 (替换为自带的标准 Icons.Default.Add 图标)
                    FabSheetItem(
                        icon = Icons.Default.Add,
                        iconColor = Color(0xFFE53935),
                        title = "新建本地项目",
                        description = "在沙盒中创建一个空白项目或选择运行模版",
                        onClick = {
                            showSheet = false
                            onAddProject() // 💡 成功联动！通知 MainActivity 跳转到编辑器
                        }
                    )

                    // 选项 3：导入本地存储 (替换为自带的标准 Icons.Outlined.FolderOpen 图标)
                    FabSheetItem(
                        icon = Icons.Outlined.FolderOpen,
                        iconColor = Color(0xFFF57C00),
                        title = "导入本地文件夹",
                        description = "通过系统文件选择器，授权外部文件夹访问权限",
                        onClick = {
                            showSheet = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FabSheetItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────
// 原有列表渲染组件保持不变
// ─────────────────────────────────────────────
@Composable
fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(projects) { project ->
            ProjectCard(project = project, onClick = { onProjectClick(project) })
        }
    }
}

@Composable
fun ProjectCard(project: Project, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(project.color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (project.type == ProjectType.GITHUB) Icons.Outlined.Hub else Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = Color.white,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = project.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = project.updatedAt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (project.hasWarning) {
                    Spacer(modifier = Modifier.height(6.dp))
                    WarningBadge()
                }
            }
        }
    }
}

@Composable
fun WarningBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Text(
            text = "需要同步",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium
        )
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

// ---------- Active Badge ----------
@Composable
fun ActiveBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = "Active",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
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

