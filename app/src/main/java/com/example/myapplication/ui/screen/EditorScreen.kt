package com.example.myapplication.ui.screen

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// ═════════════════════════════════════════════════════════════
// 安全编解码工具函数
// 规避 Kotlin 与 Javascript 通讯时的特殊字符、换行、单双引号转义问题
// ═════════════════════════════════════════════════════════════
fun String.toBase64(): String {
    return Base64.encodeToString(this.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

fun String.fromBase64(): String {
    return String(Base64.decode(this, Base64.NO_WRAP), Charsets.UTF_8)
}

// ═════════════════════════════════════════════════════════════
// WebView 桥接接口类
// 所有接口方法均在 WebView 的私有 Binder 线程中被调用
// ═════════════════════════════════════════════════════════════
class WebAppInterface(
    private val onReady: () -> Unit,
    private val onStatsChanged: (lines: Int, length: Int) -> Unit,
    private val onCursorChanged: (line: Int, col: Int) -> Unit
) {
    @JavascriptInterface
    fun onReady() {
        onReady()
    }

    @JavascriptInterface
    fun onStatsChanged(lines: Int, length: Int) {
        onStatsChanged(lines, length)
    }

    @JavascriptInterface
    fun onCursorChanged(line: Int, col: Int) {
        onCursorChanged(line, col)
    }
}

// ═════════════════════════════════════════════════════════════
// 编辑器主页面 Composable
// ═════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    filePath: String, // 需要打开和编辑的文件绝对路径
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ─────────────────────────────────────────────────────────
    // 1. 持有 WebView 引用及 JS 执行函数（挪到顶部，确保后续逻辑可安全引用）
    // ─────────────────────────────────────────────────────────
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun executeJs(script: String) {
        webViewRef?.let { wv ->
            wv.post {
                wv.evaluateJavascript(script, null)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 2. 文件及文件信息解析
    // ─────────────────────────────────────────────────────────
    val isSafUri = filePath.startsWith("content://")
    val file = remember(filePath) { if (isSafUri) null else File(filePath) }
    val fileName = remember(filePath) {
        if (isSafUri) {
            Uri.parse(filePath).lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast('%')
                ?.let { seg ->
                    Uri.decode(Uri.parse(filePath).lastPathSegment ?: "")
                        .substringAfterLast('/')
                        .ifBlank { "untitled" }
                } ?: "untitled"
        } else {
            file!!.name
        }
    }
    val fileExtension = fileName.substringAfterLast('.', "")

    // ─────────────────────────────────────────────────────────
    // 3. 状态保持与监听
    // ─────────────────────────────────────────────────────────
    var fileContent by remember { mutableStateOf("") }
    var isFileLoaded by remember { mutableStateOf(false) } // 标记文件是否真正读取就绪
    var isEditorReady by remember { mutableStateOf(false) }
    
    // 状态统计与光标位置
    var linesCount by rememberSaveable { mutableIntStateOf(0) }
    var charCount by rememberSaveable { mutableIntStateOf(0) }
    var cursorLine by rememberSaveable { mutableIntStateOf(1) }
    var cursorCol by rememberSaveable { mutableIntStateOf(1) }

    // 主题、只读、键盘控制（支持旋屏状态保留）
    var isDarkTheme by rememberSaveable { mutableStateOf(true) } 
    var isReadOnly by rememberSaveable { mutableStateOf(false) }
    var isKeyboardEnabled by rememberSaveable { mutableStateOf(false) }

    // ─────────────────────────────────────────────────────────
    // 4. 异步读取本地文件内容
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(filePath) {
        // 每次文件路径改变时，重置加载状态
        isFileLoaded = false 
        
        launch(Dispatchers.IO) {
            try {
                val text = if (isSafUri) {
                    val uri = Uri.parse(filePath)
                    context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: ""
                } else {
                    val f = file!!
                    if (f.exists()) {
                        f.readText(Charsets.UTF_8)
                    } else {
                        f.parentFile?.mkdirs()
                        f.createNewFile()
                        ""
                    }
                }
                
                launch(Dispatchers.Main) {
                    fileContent = text
                    isFileLoaded = true // 标记读取完毕
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 5. 初始化加载逻辑：当 H5 准备好且文件读取完毕时注入
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(isEditorReady, isFileLoaded) {
        if (isEditorReady && isFileLoaded) {
            executeJs("window.editorAPI.setContentBase64('${fileContent.toBase64()}')")
            executeJs("window.editorAPI.setLanguage('$fileExtension')")
            executeJs("window.editorAPI.setTheme($isDarkTheme)")
            executeJs("window.editorAPI.setReadOnly($isReadOnly)")
        }
    }

    // ─────────────────────────────────────────────────────────
    // 6. 文件保存业务逻辑
    // ─────────────────────────────────────────────────────────
    val saveFile = {
        webViewRef?.let { wv ->
            wv.evaluateJavascript("window.editorAPI.getContentBase64()") { base64WithQuotes ->
                val cleanBase64 = base64WithQuotes?.trim('"') ?: ""
                if (cleanBase64.isNotEmpty() && cleanBase64 != "null") {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val content = cleanBase64.fromBase64()
                            if (isSafUri) {
                                val uri = Uri.parse(filePath)
                                context.contentResolver.openOutputStream(uri, "wt")
                                    ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                                    ?: throw Exception("无法打开文件输出流")
                            } else {
                                file!!.writeText(content, Charsets.UTF_8)
                            }
                            fileContent = content
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "文件已保存", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 7. 系统原生剪贴板接管与中转
    // ─────────────────────────────────────────────────────────
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val handleCopy = {
        webViewRef?.let { wv ->
            wv.evaluateJavascript("window.editorAPI.copySelected()") { base64WithQuotes ->
                val cleanBase64 = base64WithQuotes?.trim('"') ?: ""
                if (cleanBase64.isNotEmpty() && cleanBase64 != "null") {
                    try {
                        val text = cleanBase64.fromBase64()
                        if (text.isNotEmpty()) {
                            val clip = ClipData.newPlainText("Code", text)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制到系统剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    val handleCut = {
        webViewRef?.let { wv ->
            wv.evaluateJavascript("window.editorAPI.cutSelected()") { base64WithQuotes ->
                val cleanBase64 = base64WithQuotes?.trim('"') ?: ""
                if (cleanBase64.isNotEmpty() && cleanBase64 != "null") {
                    try {
                        val text = cleanBase64.fromBase64()
                        if (text.isNotEmpty()) {
                            val clip = ClipData.newPlainText("Code", text)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "已剪切到系统剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    val handlePaste = {
        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty()) {
                executeJs("window.editorAPI.insertTextBase64('${text.toBase64()}')")
            }
        } else {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────
    // 8. 页面 UI 布局构建
    // ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            text = "行: $cursorLine, 列: $cursorCol",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 只读/编辑模式切换
                    IconButton(onClick = {
                        isReadOnly = !isReadOnly
                        executeJs("window.editorAPI.setReadOnly($isReadOnly)")
                        Toast.makeText(
                            context,
                            if (isReadOnly) "只读模式" else "编辑模式",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (isReadOnly) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "只读状态"
                        )
                    }

                    // 暗黑/亮色主题切换
                    IconButton(onClick = {
                        isDarkTheme = !isDarkTheme
                        executeJs("window.editorAPI.setTheme($isDarkTheme)")
                    }) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "切换主题"
                        )
                    }

                    // 保存按钮
                    IconButton(onClick = { saveFile() }) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "保存"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            Column {
                // 顶部滑动辅助输入工具栏（方向键、快捷符号、剪贴板管理）
                QuickActionButtonBar(
                    isKeyboardEnabled = isKeyboardEnabled,
                    onToggleKeyboard = {
                        isKeyboardEnabled = !isKeyboardEnabled
                        if (isKeyboardEnabled) {
                            executeJs("window.editorAPI.enableKeyboard()")
                        } else {
                            executeJs("window.editorAPI.disableKeyboard()")
                        }
                    },
                    onInsertChar = { char ->
                        executeJs("window.editorAPI.insertTextBase64('${char.toBase64()}')")
                    },
                    onMoveCursor = { dir ->
                        executeJs("window.editorAPI.moveCursor('$dir')")
                    },
                    onSelectAll = {
                        executeJs("window.editorAPI.selectAll()")
                    },
                    onCopy = { handleCopy() },
                    onCut = { handleCut() },
                    onPaste = { handlePaste() },
                    onSearch = {
                        executeJs("window.editorAPI.openSearch()")
                    }
                )

                // 统计信息底栏
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${linesCount} 行 | ${charCount} 字符",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = fileExtension.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(if (isDarkTheme) Color(0xFF282C34) else Color.White)
        ) {
            
           
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        settings.javaScriptEnabled = true
            
                        addJavascriptInterface(
                            WebAppInterface(
                                onReady = {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        isEditorReady = true
                                    }
                                },
                                onStatsChanged = { _, _ -> },
                                onCursorChanged = { _, _ -> }
                            ),
                            "AndroidBridge"
                        )
            
                        val testHtml = """<!doctype html>
            <html>
            <body style="background:white;color:black;font-size:16px;padding:20px;">
            <div id="out">检测中...</div>
            <script>
              if (window.AndroidBridge) {
                AndroidBridge.onReady();
                document.getElementById('out').innerText = 'Bridge OK！onReady已调用';
              } else {
                document.getElementById('out').innerText = 'AndroidBridge不存在！';
                document.getElementById('out').style.color = 'red';
              }
            </script>
            </body>
            </html>"""
            
                        loadDataWithBaseURL("file:///android_asset/editor/", testHtml, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    webView.destroy()
                    webViewRef = null
                }
            )
            
            // WebView 未初始化完成前展示转圈
            if (!isEditorReady) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════
// 横向滚动辅助输入工具栏（键盘上方附件栏）
// ═════════════════════════════════════════════════════════════
@Composable
fun QuickActionButtonBar(
    isKeyboardEnabled: Boolean,
    onToggleKeyboard: () -> Unit,
    onInsertChar: (String) -> Unit,
    onMoveCursor: (String) -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 软键盘编辑锁定状态控制
            IconButton(onClick = onToggleKeyboard) {
                Icon(
                    imageVector = if (isKeyboardEnabled) Icons.Default.KeyboardHide else Icons.Default.Edit,
                    contentDescription = "软键盘控制键",
                    tint = if (isKeyboardEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            // 分割线
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // 方向导航
            IconButton(onClick = { onMoveCursor("left") }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "向左")
            }
            IconButton(onClick = { onMoveCursor("right") }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "向右")
            }
            IconButton(onClick = { onMoveCursor("up") }) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "向上")
            }
            IconButton(onClick = { onMoveCursor("down") }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "向下")
            }

            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // 全选与剪贴板控制、搜索
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "全选")
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制")
            }
            IconButton(onClick = onCut) {
                Icon(Icons.Default.ContentCut, contentDescription = "剪切")
            }
            IconButton(onClick = onPaste) {
                Icon(Icons.Default.ContentPaste, contentDescription = "粘贴")
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }

            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // 针对触屏键盘难以输入的符号、Tab键、大括号直接一键快捷插入
            val programmingChars = listOf(
                "\t" to "Tab",
                "{" to "{",
                "}" to "}",
                "(" to "(",
                ")" to ")",
                "[" to "[",
                "]" to "]",
                ";" to ";",
                "=" to "=",
                "<" to "<",
                ">" to ">",
                "\"" to "\"",
                "'" to "'",
                "/" to "/",
                "\\" to "\\",
                "!" to "!",
                "&" to "&",
                "|" to "|",
                "_" to "_",
                "$" to "$"
            )

            programmingChars.forEach { (charValue, display) ->
                TextButton(
                    onClick = { onInsertChar(charValue) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
                ) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}