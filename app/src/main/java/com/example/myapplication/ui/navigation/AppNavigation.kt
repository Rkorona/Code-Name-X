package com.example.myapplication.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import com.example.myapplication.data.ProjectRepository
import com.example.myapplication.ui.component.NewLocalProjectDialog
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectLanguage
import com.example.myapplication.ui.model.ProjectType
import com.example.myapplication.ui.screen.EditorScreen
import com.example.myapplication.ui.screen.HomeScreen
import kotlinx.coroutines.launch
import java.io.File

// ─────────────────────────────────────────────
// 导航路由定义
// ─────────────────────────────────────────────
sealed class Screen {
    object Home : Screen()
    data class Editor(val filePath: String, val projectId: String) : Screen()
}


// ─────────────────────────────────────────────
// 导航状态机
// ─────────────────────────────────────────────
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 通过 Repository 从数据库加载项目列表
    val repository = remember { ProjectRepository(context) }
    val projects by repository.projects.collectAsState(initial = emptyList())

    // 对语言仍为 UNKNOWN 且路径为普通文件系统路径的项目，后台重新扫描并更新
    LaunchedEffect(projects) {
        projects.filter { it.language == ProjectLanguage.UNKNOWN && it.localPath != null }
            .forEach { project ->
                val path = project.localPath!!
                // 只处理文件系统路径（不处理 content:// SAF URI）
                if (!path.startsWith("content://")) {
                    val files = File(path).list()?.toList() ?: emptyList()
                    val detected = ProjectLanguage.detect(files)
                    if (detected != ProjectLanguage.UNKNOWN) {
                        scope.launch { repository.updateProjectLanguage(project.id, detected) }
                    }
                }
            }
    }

    // 弹窗状态
    var showNewLocalDialog by remember { mutableStateOf(false) }

    // ── 文件夹选择器（导入已有本地项目）──────────────────
    val importFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // 核心安全：持久化 SAF 目录访问权限，App 重启后依然具有读写该目录及其子节点的完整权限
        val persistFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, persistFlags)
        }

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

        // 用 DocumentFile 列出根目录文件名，推断项目语言
        val fileNames = DocumentFile.fromTreeUri(context, uri)
            ?.listFiles()
            ?.mapNotNull { it.name?.lowercase() }
            ?: emptyList()
        val detectedLanguage = ProjectLanguage.detect(fileNames)

        val newProject = Project(
            id = System.currentTimeMillis().toString(),
            name = folderName,
            description = readablePath,
            type = ProjectType.LOCAL,
            lastModified = System.currentTimeMillis(),
            isActive = false,
            localPath = uri.toString(),
            language = detectedLanguage,
        )
        scope.launch { repository.addProject(newProject) }
    }

    var selectedProject by remember { mutableStateOf<Project?>(null) }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                projects = projects,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                selectedProject = selectedProject,
                onProjectClick = { project ->
                    if (!project.localPath.isNullOrBlank()) {
                        selectedProject = project
                    }
                },
                onProjectSheetDismiss = {
                    selectedProject = null
                },
                onOpenFile = { filePath ->
                    val pid = selectedProject?.id ?: ""
                    selectedProject = null
                    currentScreen = Screen.Editor(filePath = filePath, projectId = pid)
                },
                onNewLocalProject = {
                    showNewLocalDialog = true
                },
                onCloneGithub = {
                    // TODO: 预留：打开 GitHub 克隆对话框
                },
                onImportFile = {
                    importFolderLauncher.launch(null)
                },
                onFromTemplate = {
                    // TODO: 预留：打开模板选择页
                },
                onSettingsClick = {
                    // TODO: 预留：后续接入设置页
                }
            )
        }

        is Screen.Editor -> {
            EditorScreen(
                filePath = screen.filePath,
                onNavigateBack = {
                    currentScreen = Screen.Home
                },
                onFileSaved = {
                    if (screen.projectId.isNotEmpty()) {
                        scope.launch { repository.updateLastModified(screen.projectId) }
                    }
                }
            )
        }
    }

    if (showNewLocalDialog) {
        NewLocalProjectDialog(
            onConfirm = { name, localPath ->
                // 新建空项目，暂无文件可检测；后续打开时可再推断
                val newProject = Project(
                    id = System.currentTimeMillis().toString(),
                    name = name,
                    description = "本地项目",
                    type = ProjectType.LOCAL,
                    lastModified = System.currentTimeMillis(),
                    isActive = false,
                    localPath = localPath,
                    language = localPath?.let { path ->
                        val files = java.io.File(path).list()?.toList() ?: emptyList()
                        ProjectLanguage.detect(files)
                    } ?: ProjectLanguage.UNKNOWN,
                )
                scope.launch { repository.addProject(newProject) }
                showNewLocalDialog = false
            },
            onDismiss = { showNewLocalDialog = false }
        )
    }
}
