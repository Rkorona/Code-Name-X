package com.example.myapplication.ui.screen

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
    private val onReadyCallback: () -> Unit,
    private val onStatsChangedCallback: (lines: Int, length: Int) -> Unit,
    private val onCursorChangedCallback: (line: Int, col: Int) -> Unit
) {
    @JavascriptInterface
    fun onReady() {
        onReadyCallback()
    }

    @JavascriptInterface
    fun onStatsChanged(lines: Int, length: Int) {
        onStatsChangedCallback(lines, length)
    }

    @JavascriptInterface
    fun onCursorChanged(line: Int, col: Int) {
        onCursorChangedCallback(line, col)
    }

    @JavascriptInterface
    fun log(message: String) {
        try {
            android.util.Log.d("WebViewJS", message)
        } catch (e: Exception) {
            // ignore logging failures
        }
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

    // 拦截硬件返回键，返回首页而不是退出应用
    BackHandler { onNavigateBack() }

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
    var isFileLoaded by remember { mutableStateOf(false) }
    var isEditorReady by remember { mutableStateOf(false) }

    // 状态统计与光标位置
    var linesCount by rememberSaveable { mutableIntStateOf(0) }
    var charCount by rememberSaveable { mutableIntStateOf(0) }
    var cursorLine by rememberSaveable { mutableIntStateOf(1) }
    var cursorCol by rememberSaveable { mutableIntStateOf(1) }

    // 主题、键盘控制（支持旋屏状态保留）
    val isSystemDark = isSystemInDarkTheme()
    var isDarkTheme by rememberSaveable { mutableStateOf(isSystemDark) }
    // var isReadOnly by rememberSaveable { mutableStateOf(false) }
    var isKeyboardEnabled by rememberSaveable { mutableStateOf(false) }

    // ─────────────────────────────────────────────────────────
    // 4. 异步读取本地文件内容
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(filePath) {
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
                    isFileLoaded = true
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    LaunchedEffect(isSystemDark) {
        isDarkTheme = isSystemDark
        if (isEditorReady) {
            val bg = if (isDarkTheme) "#141729" else "#ffffff"
            executeJs("document.documentElement.style.setProperty('--editor-bg','$bg')")
            executeJs("window.editorAPI.setTheme($isDarkTheme)")
        }
    }
    
    // ─────────────────────────────────────────────────────────
    // 5. 初始化加载逻辑：当 H5 准备好且文件读取完毕时注入
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(isEditorReady, isFileLoaded) {
        if (isEditorReady && isFileLoaded) {
            val bg = if (isDarkTheme) "#141729" else "#ffffff"
            executeJs("document.documentElement.style.setProperty('--editor-bg','$bg')")
            executeJs("window.editorAPI.setContentBase64('${fileContent.toBase64()}')")
            executeJs("window.editorAPI.setLanguage('$fileExtension')")
            executeJs("window.editorAPI.setTheme($isDarkTheme)")
            webViewRef?.postDelayed({
                webViewRef?.evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'))", null
                )
            }, 150)
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
    // 7. 页面 UI 布局构建
    // ─────────────────────────────────────────────────────────
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // 文件名 + 语言类型徽章
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (fileExtension.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(4.dp),
                                    tonalElevation = 0.dp
                                ) {
                                    Text(
                                        text = fileExtension.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        // 文件路径提示（只读时显示 READ ONLY 标签）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isSafUri) "外部文件" else filePath
                                    .substringBeforeLast('/')
                                    .let { dir -> if (dir.length > 28) "…" + dir.takeLast(28) else dir },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                actions = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            val isImeVisible = WindowInsets.isImeVisible
            Column {
                // ── IDE 风格状态栏：键盘弹出时隐藏 ──────────────────────
                AnimatedVisibility(
                    visible = !isImeVisible,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
                ) {
                    Surface(
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusBarLabel("Ln $cursorLine")
                                StatusBarLabel("Col $cursorCol")
                                StatusBarPipe()
                                StatusBarLabel("$linesCount 行")
                                StatusBarPipe()
                                StatusBarLabel("$charCount 字符")
                                StatusBarPipe()
                                StatusBarLabel("UTF-8")
                            }
                            StatusBarLabel(
                                text = fileExtension.uppercase().ifBlank { "TEXT" },
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // ── 底部栏：键盘弹出→符号栏，键盘收起→工具栏 ───────────
                AnimatedContent(
                    targetState = isImeVisible,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "BottomBarSwitch"
                ) { keyboardVisible ->
                    if (keyboardVisible) {
                        QuickActionButtonBar(
                            onInsertChar = { char ->
                                executeJs("window.editorAPI.insertTextBase64('${char.toBase64()}')")
                            },
                            onMoveCursor = { dir ->
                                executeJs("window.editorAPI.moveCursor('$dir')")
                            }
                        )
                    } else {
                        EditorActionsBar(
                            isKeyboardEnabled = isKeyboardEnabled,
                            onSave = saveFile,
                            onToggleKeyboard = {
                                isKeyboardEnabled = !isKeyboardEnabled
                                if (isKeyboardEnabled) {
                                    executeJs("window.editorAPI.enableKeyboard()")
                                } else {
                                    executeJs("window.editorAPI.disableKeyboard()")
                                }
                            }
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
                .background(if (isDarkTheme) Color(0xFF141729) else Color.White)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this

                        // 将 WebView 自身背景设为透明，使其下方 Compose Box 的背景色
                        // 在页面加载完成前就可见，彻底消除打开编辑器时的白色闪烁帧。
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = true
                            allowContentAccess = true
                            allowFileAccessFromFileURLs = true
                            allowUniversalAccessFromFileURLs = true
                        }

                        addJavascriptInterface(
                            WebAppInterface(
                                onReadyCallback = {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        isEditorReady = true
                                    }
                                },
                                onStatsChangedCallback = { lines, length ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        linesCount = lines
                                        charCount = length
                                    }
                                },
                                onCursorChangedCallback = { line, col ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        cursorLine = line
                                        cursorCol = col
                                    }
                                }
                            ),
                            "AndroidBridge"
                        )

                        val assetLoader = WebViewAssetLoader.Builder()
                            .setDomain("appassets.androidplatform.net")
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                            .build()

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return assetLoader.shouldInterceptRequest(request.url)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript(
                                    "window.editorAPI && window.editorAPI.notifyReady()",
                                    null
                                )
                                view.postDelayed({
                                    view.evaluateJavascript(
                                        "window.editorAPI && window.editorAPI.notifyReady()",
                                        null
                                    )
                                }, 200)
                            }
                        }

                        loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    webView.destroy()
                    webViewRef = null
                }
            )

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
// 状态栏辅助组件
// ═════════════════════════════════════════════════════════════

@Composable
private fun StatusBarLabel(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        ),
        color = color
    )
}

@Composable
private fun StatusBarPipe() {
    Box(
        modifier = Modifier
            .height(10.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// ═════════════════════════════════════════════════════════════
// 横向滚动辅助输入工具栏（键盘上方附件栏）
// ═════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionButtonBar(
    onInsertChar: (String) -> Unit,
    onMoveCursor: (String) -> Unit,
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
                .padding(vertical = 5.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // ── 方向键组 ─────────────────────────────────────────────────────
            EditorArrowKey(
                icon = Icons.Default.KeyboardArrowLeft,
                description = "向左",
                onClick = { onMoveCursor("left") }
            )
            EditorArrowKey(
                icon = Icons.Default.KeyboardArrowUp,
                description = "向上",
                onClick = { onMoveCursor("up") }
            )
            EditorArrowKey(
                icon = Icons.Default.KeyboardArrowDown,
                description = "向下",
                onClick = { onMoveCursor("down") }
            )
            EditorArrowKey(
                icon = Icons.Default.KeyboardArrowRight,
                description = "向右",
                onClick = { onMoveCursor("right") }
            )

            ToolbarDivider()

            // ── 编程符号快捷键 ────────────────────────────────────────────────
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
                EditorSymbolKey(
                    display = display,
                    onClick = { onInsertChar(charValue) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 工具栏子组件
// ─────────────────────────────────────────────────────────────

/** 工具栏竖向分隔线 */
@Composable
private fun ToolbarDivider() {
    Spacer(modifier = Modifier.width(2.dp))
    Box(
        modifier = Modifier
            .height(22.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
    Spacer(modifier = Modifier.width(2.dp))
}

// ═════════════════════════════════════════════════════════════
// 工具栏（键盘收起时替代符号栏显示）
// ═════════════════════════════════════════════════════════════
@Composable
private fun EditorActionsBar(
    isKeyboardEnabled: Boolean,
    onSave: () -> Unit,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 保存文件
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "保存文件",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 软键盘开关
            IconButton(onClick = onToggleKeyboard) {
                Icon(
                    imageVector = if (isKeyboardEnabled)
                        Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                    contentDescription = "软键盘",
                    modifier = Modifier.size(22.dp),
                    tint = if (isKeyboardEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 通用键帽按钮基座（支持激活高亮态） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorKeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = if (isActive)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp)

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(7.dp),
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/** 方向键（带图标的固定尺寸键帽） */
@Composable
private fun EditorArrowKey(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    EditorKeyButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 符号键（等宽字体文本，自适应宽度） */
@Composable
private fun EditorSymbolKey(
    display: String,
    onClick: () -> Unit
) {
    EditorKeyButton(
        onClick = onClick,
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 38.dp)
    ) {
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp)
        )
    }
}
