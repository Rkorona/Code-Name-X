package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.model.Project
import com.example.myapplication.ui.screen.EditorFile
import com.example.myapplication.ui.screen.EditorScreen
import com.example.myapplication.ui.screen.HomeScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppNavigation()
            }
        }
    }
}

// ─────────────────────────────────────────────
// 导航状态机（无需 NavHost，轻量实现）
// ─────────────────────────────────────────────

sealed class Screen {
    object Home : Screen()
    data class Editor(val file: EditorFile) : Screen()
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                onProjectClick = { project ->
                    currentScreen = Screen.Editor(
                        file = projectToEditorFile(project)
                    )
                },
                onAddProject = {
                    // 新建空文件直接进编辑器
                    currentScreen = Screen.Editor(
                        file = EditorFile(
                            name = "untitled.js",
                            code = "",
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
                    // saveToLocal(screen.file.name, content)
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
    // 根据项目 description 猜语言
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