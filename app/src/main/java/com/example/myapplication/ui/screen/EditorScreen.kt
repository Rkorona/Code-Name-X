package com.example.myapplication.ui.screen

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.example.myapplication.ui.editor.EditorBridge
import com.example.myapplication.ui.editor.EditorCallback
import com.example.myapplication.ui.editor.EditorCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// 数据模型
// ─────────────────────────────────────────────

data class EditorFile(
    val name: String,
    val code: String,
    val lang: String = name.substringAfterLast('.', "js")
)

// ─────────────────────────────────────────────
// EditorScreen
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorScreen(
    file: EditorFile,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var isEditorReady by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf(file.lang) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    DisposableEffect(view) {
        val window = (view.context as? androidx.activity.ComponentActivity)?.window
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        onDispose {
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    fun saveFile() {
        val wv = webViewRef.value ?: return
        isSaving = true
        EditorCommands.getContent(wv) { content ->
            scope.launch(Dispatchers.Main) {
                onSave(content)
                hasUnsavedChanges = false
                isSaving = false
                snackbarMessage = "已保存"
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file.name + if (hasUnsavedChanges) " ●" else "",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = currentLang.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showDiscardDialog = true
                        else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    LangDropdown(
                        current = currentLang,
                        onSelect = { lang ->
                            currentLang = lang
                            webViewRef.value?.let { EditorCommands.setLanguage(it, lang) }
                        }
                    )
                    IconButton(
                        onClick = { saveFile() },
                        enabled = !isSaving && isEditorReady
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccessFromFileURLs = true
                            allowUniversalAccessFromFileURLs = true
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                        }

                        val bridge = EditorBridge(
                            scope = scope,
                            callback = object : EditorCallback {
                                override fun onReady() {
                                    isEditorReady = true
                                    EditorCommands.init(this@apply, file.code, file.lang)
                                }
                                override fun onContentChange(content: String) {
                                    hasUnsavedChanges = true
                                }
                                override fun onSelectionChange(line: Int, col: Int) {}
                                override fun onError(message: String) {
                                    snackbarMessage = "编辑器错误：$message"
                                }
                            }
                        )
                        addJavascriptInterface(bridge, "Android")

                        webViewRef.value = this

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean = true
                        }

                        loadUrl("file:///android_asset/editor/src/index.html")
                    }
                }
            )

            if (!isEditorReady) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("加载编辑器…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃更改？") },
            text = { Text("${file.name} 有未保存的修改，离开后将丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("取消") }
            }
        )
    }
}

// ─────────────────────────────────────────────
// 语言切换下拉菜单
// ─────────────────────────────────────────────

@Composable
private fun LangDropdown(
    current: String,
    onSelect: (String) -> Unit
) {
    val langs = listOf("js", "ts", "jsx", "py", "cpp", "c", "java", "kotlin", "dart", "html", "css", "json")
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.uppercase())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            langs.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.uppercase()) },
                    onClick = {
                        onSelect(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}
