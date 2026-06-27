package com.example.myapplication.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.ui.component.NewLocalProjectDialog
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectType
import com.example.myapplication.ui.screen.EditorFile
import com.example.myapplication.ui.screen.EditorScreen
import com.example.myapplication.ui.screen.HomeScreen

// ─────────────────────────────────────────────
// 导航路由定义
// ─────────────────────────────────────────────

sealed class Screen {
    object Home : Screen()
    data class Editor(val file: EditorFile) : Screen()
}

// ─────────────────────────────────────────────
// 本地项目图标颜色循环色盘
// ─────────────────────────────────────────────

private val localProjectIconColors = listOf(
    Color(0xFFE53935), // 红
    Color(0xFF1565C0), // 深蓝
    Color(0xFF2E7D32), // 绿
    Color(0xFF6A1B9A), // 紫
    Color(0xFFE65100), // 橙
    Color(0xFF00838F), // 青
    Color(0xFF558B2F), // 草绿
    Color(0xFF4527A0), // 靛蓝
)

// ─────────────────────────────────────────────
// 导航状态机
// ─────────────────────────────────────────────

@Composable
fun AppNavigation() {
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 项目列表：动态管理，无硬编码
    val projects = remember { mutableStateListOf<Project>() }

    // 弹窗状态
    var showNewLocalDialog by remember { mutableStateOf(false) }

    // ── 工具函数：计算下一个本地项目图标颜色 ────────────
    fun nextLocalColor(): Color {
        val localCount = projects.count { it.type == ProjectType.LOCAL }
        return localProjectIconColors[localCount % localProjectIconColors.size]
    }

    // ── 文件夹选择器（导入已有本地项目）──────────────────
    val importFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // 持久化 URI 访问权限，app 重启后仍可读写该目录
        val persistFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, persistFlags)
        }

        // 从 URI 解析文件夹名和可读路径
        // URI lastPathSegment 格式通常是 "primary:Documents/my-project"
        val rawSegment = uri.lastPathSegment ?: uri.toString()
        val folderName = rawSegment
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { rawSegment }

        val readablePath = rawSegment
            .substringAfterLast(':')
            .let { relativePath ->
                if (relativePath.isNotBlank()) "/storage/emulated/0/$relativePath"
                else uri.toString()
            }

        val newProject = Project(
            id = System.currentTimeMillis().toString(),
            name = folderName,
            description = readablePath,
            type = ProjectType.LOCAL,
            lastModified = "刚刚",
            iconColor = nextLocalColor(),
            isActive = false,
            localPath = uri.toString()
        )
        projects.add(0, newProject)
    }

    // 底部弹出文件树的选中项目（null = 未展开）
    var selectedProject by remember { mutableStateOf<Project?>(null) }

    // ── 页面渲染 ─────────────────────────────────────────
    when (val screen = currentScreen) {

        // ── 主页 ────────────────────────────────────────
        is Screen.Home -> {
            HomeScreen(
                projects = projects,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                selectedProject = selectedProject,
                onProjectClick = { project ->
                    // 点击项目 → 底部弹出文件树（无本地路径则打开空编辑器）
                    if (!project.localPath.isNullOrBlank()) {
                        selectedProject = project
                    } else {
                        currentScreen = Screen.Editor(
                            file = EditorFile(
                                name = project.name,
                                code = "// ${project.name}\n",
                                lang = "js"
                            )
                        )
                    }
                },
                onProjectSheetDismiss = {
                    selectedProject = null
                },
                onOpenFile = { editorFile ->
                    selectedProject = null
                    currentScreen = Screen.Editor(file = editorFile)
                },
                onNewLocalProject = {
                    showNewLocalDialog = true
                },
                onCloneGithub = {
                    // TODO: 打开 GitHub 克隆对话框
                },
                onImportFile = {
                    importFolderLauncher.launch(null)
                },
                onFromTemplate = {
                    // TODO: 打开模板选择页
                },
                onSettingsClick = {
                    // TODO: 后续接入设置页
                }
            )
        }

        // ── 代码编辑器 ───────────────────────────────────
        is Screen.Editor -> {
            EditorScreen(
                file = screen.file,
                onBack = {
                    currentScreen = Screen.Home
                },
                onSave = { _ ->
                    // TODO: 写入本地文件或推送到 GitHub
                    currentScreen = Screen.Home
                }
            )
        }
    }

    // ── 新建本地项目对话框 ────────────────────────────────
    if (showNewLocalDialog) {
        NewLocalProjectDialog(
            onConfirm = { name, localPath ->
                val newProject = Project(
                    id = System.currentTimeMillis().toString(),
                    name = name,
                    description = "本地项目",
                    type = ProjectType.LOCAL,
                    lastModified = "刚刚",
                    iconColor = nextLocalColor(),
                    isActive = false,
                    localPath = localPath
                )
                projects.add(0, newProject)
                showNewLocalDialog = false
            },
            onDismiss = { showNewLocalDialog = false }
        )
    }
}
