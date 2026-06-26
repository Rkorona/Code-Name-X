package com.example.myapplication.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.myapplication.ui.model.Project
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
// 导航状态机（无需 NavHost，轻量实现）
// ─────────────────────────────────────────────

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onProjectClick = { project ->
                    currentScreen = Screen.Editor(
                        file = projectToEditorFile(project)
                    )
                },
                onAddProject = {
                    currentScreen = Screen.Editor(
                        file = EditorFile(
                            name = "untitled.js",
                            code = "// untitled.js\n// Start coding here...\n\n",
                            lang = "js"
                        )
                    )
                },
                onSettingsClick = {
                    // 后续接入设置页
                }
            )
        }

        is Screen.Editor -> {
            EditorScreen(
                file = screen.file,
                onBack = {
                    currentScreen = Screen.Home
                },
                onSave = { content ->
                    // TODO: 写入本地文件或推送到 GitHub
                    currentScreen = Screen.Home
                }
            )
        }
    }
}

// ─────────────────────────────────────────────
// Project → EditorFile 转换
// ─────────────────────────────────────────────

private fun projectToEditorFile(project: Project): EditorFile {
    val lang = when {
        "flutter" in project.description -> "dart"
        "compose" in project.description -> "kotlin"
        "react" in project.description  -> "jsx"
        else                             -> "js"
    }
    return EditorFile(
        name = project.name,
        code = "// ${project.name}\n// ${project.description}\n",
        lang = lang
    )
}
