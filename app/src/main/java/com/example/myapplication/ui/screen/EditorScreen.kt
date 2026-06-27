package com.example.myapplication.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
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

    // 1. 文件及文件信息解析
    val isSafUri = filePath.startsWith("content://")
    val file = remember(filePath) { if (isSafUri) null else File(filePath) }
    val fileName = remember(filePath) {
        if (isSafUri) {
            Uri.parse(filePath).lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast('%')
                ?.let { seg ->
                    // document id is like "primary:QLPanel/main.js" — take part after last /
                    Uri.decode(Uri.parse(filePath).lastPathSegment ?: "")
                        .substringAfterLast('/')
                        .ifBlank { "untitled" }
                } ?: "untitled"
        } else {
            file!!.name
        }
    }
    val fileExtension = fileName.substringAfterLast('.', "")

    // 2. 状态保持与监听
    var fileContent by remember { mutableStateOf("") }
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

    // 3. 异步读取本地文件内容
    LaunchedEffect(filePath) {
        launch(Dispatchers.IO) {
            try {
                if (isSafUri) {
                    val uri = Uri.parse(filePath)
                    val text = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: ""
                    fileContent = text
                } else {
                    val f = file!!
                    if (f.exists()) {
                        fileContent = f.readText(Charsets.UTF_8)
                    } else {
                        f.parentFile?.mkdirs()
                        f.createNewFile()
                        fileContent = ""
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 4. 持有 WebView 引用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 安全、线程安全的 JS 命令执行工具
    fun executeJs(script: String) {
        webViewRef?.let { wv ->
            wv.post {
                wv.evaluateJavascript(script, null)
            }
        }
    }

    // 5. 文件保存业务逻辑
    val saveFile = {
        webViewRef?.let { wv ->
            // 调用 JS 获取当前最新的 Base64 文本
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

    // 6. 系统原生剪贴板接管与中转
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

    // 7. 页面 UI 布局构建
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
                            .navigationBarsPadding() // 确保全面屏手势不遮挡
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
                        webViewRef = this

                        // 必要的 WebView 安全与功能配置
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        // 注入桥接，回调全部分发至 Compose 状态层（切回 Dispatchers.Main 线程）
                        addJavascriptInterface(
                            WebAppInterface(
                                onReady = {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        isEditorReady = true
                                        // 1. 初始化写入文件内容 (Base64格式规避复杂符号崩溃)
                                        executeJs("window.editorAPI.setContentBase64('${fileContent.toBase64()}')")
                                        // 2. 初始化切换语言
                                        executeJs("window.editorAPI.setLanguage('$fileExtension')")
                                        // 3. 同步主题状态
                                        executeJs("window.editorAPI.setTheme($isDarkTheme)")
                                        // 4. 同步只读状态
                                        executeJs("window.editorAPI.setReadOnly($isReadOnly)")
                                    }
                                },
                                onStatsChanged = { lines, length ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        linesCount = lines
                                        charCount = length
                                    }
                                },
                                onCursorChanged = { line, col ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        cursorLine = line
                                        cursorCol = col
                                    }
                                }
                            ),
                            "AndroidBridge"
                        )

                        // 阻止 WebView 跳转外部分页
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                            }
                        }

                        // 加载编译后的 H5 静态资源
                        loadUrl("file:///android_asset/editor/index.html")
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    // 彻底销毁 WebView 规避内存泄漏
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