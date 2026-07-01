package io.axiom.editor.ui.screen

import androidx.compose.foundation.layout.ExperimentalLayoutApi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.view.MotionEvent
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
import kotlin.math.abs
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
import io.axiom.editor.data.AppSettings
import io.axiom.editor.data.EncodingMode
import io.axiom.editor.data.ThemeMode
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectLanguage
import io.axiom.editor.ui.model.ProjectType

// ═════════════════════════════════════════════════════════════
// 安全编解码工具函数与双通道编码自动检测
// 规避 Kotlin 与 Javascript 通讯时的特殊字符、换行、单双引号转义问题
// ═════════════════════════════════════════════════════════════
fun String.toBase64(): String {
    return Base64.encodeToString(this.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

fun String.fromBase64(): String {
    return String(Base64.decode(this, Base64.NO_WRAP), Charsets.UTF_8)
}

/**
 * 智能读取文件字节流并自动检测编码 (新增)
 * 优先使用标准 UTF-8 进行解码校验，若产生无效字符编码异常，自动无损降级切换至中国国家标准 GBK。
 */
/**
 * 高级字符编码自适应检测器 (升级版)
 * 1. 优先通过 BOM 头部检测 UTF-16LE, UTF-16BE 和带 BOM 的 UTF-8。
 * 2. 依次尝试严格解码：UTF-8 -> GB18030 (完美兼容 GBK/GB2312) -> Big5 (繁体中文)。
 * 3. 失败时保底使用标准 UTF-8。
 */
fun detectEncoding(bytes: ByteArray): java.nio.charset.Charset {
    if (bytes.isEmpty()) return java.nio.charset.StandardCharsets.UTF_8

    // 1. 优先检查 BOM 头部标识
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return java.nio.charset.StandardCharsets.UTF_8
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return java.nio.charset.StandardCharsets.UTF_16BE
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return java.nio.charset.StandardCharsets.UTF_16LE
    }

    // 2. 有序的严格多字节编码候选列表
    val candidates = listOf(
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.charset.Charset.forName("GB18030"), // 国家标准，完全兼容并超越 GBK/GB2312
        java.nio.charset.Charset.forName("Big5")      // 繁体中文
    )

    for (charset in candidates) {
        try {
            val decoder = charset.newDecoder()
            // 关键：强制要求遇到错误字符或畸形输入时抛出异常，否则默认行为是替换为 '' 而不报错
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            return charset // 解码成功，直接返回该字符集
        } catch (e: Exception) {
            // 继续尝试列表中的下一个候选
        }
    }

    // 3. 所有多字节候选均告失败后的保底返回
    return java.nio.charset.StandardCharsets.UTF_8
}
// ═════════════════════════════════════════════════════════════
// WebView 桥接接口类
// 所有接口方法均在 WebView 的私有 Binder 线程中被调用
// ═════════════════════════════════════════════════════════════
class WebAppInterface(
    private val onReadyCallback: () -> Unit,
    private val onStatsChangedCallback: (lines: Int, length: Int, indentLabel: String) -> Unit, // 传入智能缩进标签
    private val onCursorChangedCallback: (line: Int, col: Int) -> Unit,
    private val onDiagnosticsChangedCallback: (errors: Int, warnings: Int) -> Unit
) {
    @JavascriptInterface
    fun onReady() {
        onReadyCallback()
    }

    @JavascriptInterface
    fun onStatsChanged(lines: Int, length: Int, indentLabel: String) {
        onStatsChangedCallback(lines, length, indentLabel)
    }

    @JavascriptInterface
    fun onCursorChanged(line: Int, col: Int) {
        onCursorChangedCallback(line, col)
    }

    @JavascriptInterface
    fun onDiagnosticsChanged(errors: Int, warnings: Int) {
        onDiagnosticsChangedCallback(errors, warnings)
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class) 
@Composable
fun EditorScreen(
    filePath: String,
    onNavigateBack: () -> Unit,
    onFileSaved: (() -> Unit)? = null,
    settings: AppSettings = AppSettings(),
    modifier: Modifier = Modifier,
    projectName: String = "",
    projectLocalPath: String? = null,
    // 文件选项卡
    tabFilePaths: List<String> = emptyList(),
    activeTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onTabClose: (Int) -> Unit = {},
    onOpenNewTab: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 拦截硬件返回键，返回首页而不是退出应用
    BackHandler { onNavigateBack() }

    // ─────────────────────────────────────────────────────────
    // 1. 持有 WebView 引用及 JS 执行函数
    // ─────────────────────────────────────────────────────────
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun executeJs(script: String) {
        webViewRef?.let { wv ->
            wv.post {
                wv.evaluateJavascript(script, null)
            }
        }
    }

    // ── 修改状态 & 保存提示 ──────────────────────────────────
    // 用原子布尔区分「用户编辑」与「程序注入」触发的 onStatsChanged
    val suppressMod = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun suppressModFor(ms: Long = 700L) {
        suppressMod.set(true)
        coroutineScope.launch { kotlinx.coroutines.delay(ms); suppressMod.set(false) }
    }

    // ─────────────────────────────────────────────────────────
    // 注入编辑器外观设置（字体、行号、换行、补全等）
    // ─────────────────────────────────────────────────────────
    fun applyEditorSettings(isDark: Boolean) {
        suppressModFor(700L)   // setLineWrapping / setLanguage 等会触发 onStatsChanged
        val fs = settings.editorFontSize.toInt()
        val tabSz = settings.tabWidth.size
        val showGutter = settings.showLineNumbers
        val wrap = settings.wordWrap
        val autocomplete = settings.autoComplete
        val fw = settings.editorFontWeight.cssValue

        // 注入或更新自定义样式块（字号、行号、自动补全、字体粗细）
        val css = buildString {
            append(".cm-content,.cm-gutters{font-size:${fs}px!important}")
            append(".cm-content,.cm-content .cm-line,.cm-content span{font-weight:${fw}!important}")
            // 收紧行号列内边距，避免默认过宽
            append(".cm-lineNumbers .cm-gutterElement{padding:0 6px 0 2px!important}")
            append(".cm-lineNumbers{min-width:0!important}")
            // 确保 scroller 横向可滚动，touch-action 允许双向平移
            append(".cm-scroller{overflow:auto!important;touch-action:pan-x pan-y!important}")
            if (!showGutter) append(".cm-gutters{display:none!important}")
            if (!autocomplete) append(".cm-tooltip{display:none!important}")
        }
        executeJs("""
            (function(){
              var s=document.getElementById('__app_style');
              if(!s){s=document.createElement('style');s.id='__app_style';document.head.appendChild(s);}
              s.textContent=${'"'}${css}${'"'};
            })()
        """.trimIndent())

        // 自动换行（通过 CodeMirror 扩展正确切换，避免 CSS hack 无法触发高度重算）
        executeJs("if(window.editorAPI&&window.editorAPI.setLineWrapping)window.editorAPI.setLineWrapping($wrap)")

        // Tab 宽度
        executeJs("window.editorAPI.setIndentation('Space',$tabSz)")
    }

    fun applyEditorFont() {
        val fontUri = settings.editorFontUri
        if (fontUri.isEmpty()) {
            executeJs("""
                (function(){
                  var s=document.getElementById('__app_font');
                  if(s) s.remove();
                  var s2=document.getElementById('__app_font_apply');
                  if(s2) s2.remove();
                })()
            """.trimIndent())
            return
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(
                    android.net.Uri.parse(fontUri)
                )?.use { it.readBytes() } ?: return@launch
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val ext = fontUri.substringAfterLast('.').lowercase()
                val mime = if (ext == "otf") "font/otf" else "font/ttf"
                launch(Dispatchers.Main) {
                    executeJs("""
                        (function(){
                          var s=document.getElementById('__app_font');
                          if(!s){s=document.createElement('style');s.id='__app_font';document.head.appendChild(s);}
                          s.textContent='@font-face{font-family:"AppCustomFont";src:url("data:$mime;base64,$b64");}';
                          var s2=document.getElementById('__app_font_apply');
                          if(!s2){s2=document.createElement('style');s2.id='__app_font_apply';document.head.appendChild(s2);}
                          s2.textContent='.cm-scroller,.cm-content,.cm-line{font-family:"AppCustomFont",monospace!important}';
                        })()
                    """.trimIndent())
                }
            } catch (_: Exception) {}
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
    var isWebViewLoading by remember { mutableStateOf(true) }

    // 状态统计与光标位置
    var linesCount by rememberSaveable { mutableIntStateOf(0) }
    var charCount by rememberSaveable { mutableIntStateOf(0) }
    var cursorLine by rememberSaveable { mutableIntStateOf(1) }
    var cursorCol by rememberSaveable { mutableIntStateOf(1) }

    // 动态缩进规格与编码格式 (新增)
    var indentLabel by rememberSaveable { mutableStateOf("Spaces: 4") }
    var fileEncoding by rememberSaveable { mutableStateOf("UTF-8") }

    // 语法诊断结果状态
    var errorCount by rememberSaveable { mutableIntStateOf(0) }
    var warningCount by rememberSaveable { mutableIntStateOf(0) }

    // 主题、键盘控制
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settings.themeOption) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
    }
    var isKeyboardEnabled by rememberSaveable { mutableStateOf(false) }

    // 文件树底部抽屉状态
    var showFileTree by remember { mutableStateOf(false) }
    var showFileDropdown by remember { mutableStateOf(false) }

    // ── 修改状态 & 保存提示 ──────────────────────────────────
    var isModified by remember { mutableStateOf(false) }
    var showSavedIndicator by remember { mutableStateOf(false) }
    var savedIndicatorTick by remember { mutableStateOf(0) }
    val treeProject = remember(projectName, projectLocalPath) {
        if (projectLocalPath != null) {
            Project(
                id = "editor_tree",
                name = projectName.ifBlank { "项目文件" },
                description = projectLocalPath,
                type = ProjectType.LOCAL,
                isActive = true,
                localPath = projectLocalPath,
                language = ProjectLanguage.UNKNOWN
            )
        } else null
    }

    // ─────────────────────────────────────────────────────────
    // 4. 异步读取本地文件内容 (已升级双通道编码自适应读取)
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(filePath) {
        isFileLoaded = false
        isWebViewLoading = true

        // 快照：记录本次 Effect 启动时的路径，防止旧协程注入新文件内容
        val targetPath = filePath
        val editorAlreadyReady = isEditorReady

        // 提前计算文件扩展名（不依赖 remember，避免批处理竞态）
        val targetExt = if (targetPath.startsWith("content://")) {
            Uri.decode(Uri.parse(targetPath).lastPathSegment ?: "")
                .substringAfterLast('/').substringAfterLast('.', "")
        } else {
            File(targetPath).extension
        }

        launch(Dispatchers.IO) {
            try {
                val bytes = if (isSafUri) {
                    val uri = Uri.parse(targetPath)
                    context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: byteArrayOf()
                } else {
                    val f = File(targetPath)
                    if (f.exists()) {
                        f.readBytes()
                    } else {
                        f.parentFile?.mkdirs()
                        f.createNewFile()
                        byteArrayOf()
                    }
                }

                // 根据设置选择编码方式（手动指定或自动嗅探）
                val charset = when (settings.fileEncoding) {
                    EncodingMode.UTF8  -> java.nio.charset.StandardCharsets.UTF_8
                    EncodingMode.GBK   -> java.nio.charset.Charset.forName("GB18030")
                    EncodingMode.UTF16 -> java.nio.charset.StandardCharsets.UTF_16
                    EncodingMode.AUTO  -> detectEncoding(bytes)
                }
                val text = bytes.toString(charset)

                launch(Dispatchers.Main) {
                    fileContent = text
                    fileEncoding = charset.name()
                    isModified = false
                    isFileLoaded = true

                    // 若编辑器已就绪（文件切换场景），直接注入新内容，
                    // 避免依赖 isFileLoaded 状态翻转被 Compose 批处理吞掉
                    if (editorAlreadyReady) {
                        val isDark = isDarkTheme
                        val bg = if (isDark) "#141729" else "#ffffff"
                        suppressModFor(800L)  // 注入内容会触发 onStatsChanged
                        executeJs("document.documentElement.style.setProperty('--editor-bg','$bg')")
                        executeJs("window.editorAPI.setContentBase64('${text.toBase64()}')")
                        executeJs("window.editorAPI.setLanguage('$targetExt')")
                        executeJs("window.editorAPI.setTheme($isDark)")
                        applyEditorSettings(isDark)
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    LaunchedEffect(isDarkTheme) {
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
            suppressModFor(800L)  // 初始注入内容也要抑制
            executeJs("document.documentElement.style.setProperty('--editor-bg','$bg')")
            executeJs("window.editorAPI.setContentBase64('${fileContent.toBase64()}')")
            executeJs("window.editorAPI.setLanguage('$fileExtension')")
            executeJs("window.editorAPI.setTheme($isDarkTheme)")
            applyEditorSettings(isDarkTheme)
            applyEditorFont()
            webViewRef?.postDelayed({
                webViewRef?.evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'))", null
                )
            }, 150)
        }
    }

    // 设置变化时实时同步到编辑器
    LaunchedEffect(settings) {
        if (isEditorReady) {
            applyEditorSettings(isDarkTheme)
            applyEditorFont()
        }
    }

    // ─────────────────────────────────────────────────────────
    // 6. 文件保存业务逻辑 (已升级保持原编码格式保存)
    // ─────────────────────────────────────────────────────────
    val saveFile: () -> Unit = {
        webViewRef?.let { wv ->
            wv.evaluateJavascript("window.editorAPI.getContentBase64()") { base64WithQuotes ->
                // 只排除真正的 null / JS undefined，允许空字符串（空文件）正常保存
                val rawValue = base64WithQuotes?.trim('"') ?: ""
                if (rawValue != "null") {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val content = if (rawValue.isEmpty()) "" else rawValue.fromBase64()

                            // 还原为读取时的编码格式进行二进制物理写入，保护编码属性不被强制破坏为默认的 UTF-8
                            val charset = java.nio.charset.Charset.forName(fileEncoding)
                            val encodedBytes = content.toByteArray(charset)

                            if (isSafUri) {
                                val uri = Uri.parse(filePath)
                                context.contentResolver.openOutputStream(uri, "wt")
                                    ?.use { it.write(encodedBytes) }
                                    ?: throw Exception("无法打开文件输出流")
                            } else {
                                file!!.writeBytes(encodedBytes)
                            }
                            fileContent = content
                            launch(Dispatchers.Main) {
                                isModified = false
                                savedIndicatorTick++
                                onFileSaved?.invoke()
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
    // 7. 自动保存定时器（仅在文件被修改时触发）
    // ─────────────────────────────────────────────────────────
    LaunchedEffect(settings.autoSave, settings.autoSaveInterval, isModified) {
        if (settings.autoSave) {
            while (true) {
                kotlinx.coroutines.delay(settings.autoSaveInterval.ms)
                if (isEditorReady && isFileLoaded && isModified) {
                    saveFile()
                }
            }
        }
    }

    // 保存提示计时器：显示 2.5 秒后隐藏
    LaunchedEffect(savedIndicatorTick) {
        if (savedIndicatorTick > 0) {
            showSavedIndicator = true
            kotlinx.coroutines.delay(2500)
            showSavedIndicator = false
        }
    }

    // ─────────────────────────────────────────────────────────
    // 8. 页面 UI 布局构建
    // ─────────────────────────────────────────────────────────
    val showTabStrip = settings.enableFileTabs && tabFilePaths.size >= 1

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                title = {
                    val canShowFileDropdown = !settings.enableFileTabs && !isSafUri
                    val siblingFiles = remember(filePath) {
                        if (!isSafUri) {
                            File(filePath).parentFile?.listFiles()
                                ?.filter { it.isFile }
                                ?.sortedBy { it.name.lowercase() }
                                ?: emptyList()
                        } else emptyList()
                    }
                    Box {
                        Column(
                            modifier = if (canShowFileDropdown && siblingFiles.size > 1)
                                Modifier.clickable { showFileDropdown = true } else Modifier,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // 文件名 + 语言类型徽章 + 下拉箭头
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
                                        color = if (isModified)
                                            MaterialTheme.colorScheme.errorContainer
                                        else
                                            MaterialTheme.colorScheme.primaryContainer,
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
                                            color = if (isModified)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (canShowFileDropdown && siblingFiles.size > 1) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = "切换文件",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            // 文件路径提示
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AnimatedContent(
                                    targetState = showSavedIndicator,
                                    transitionSpec = {
                                        (fadeIn() + expandVertically()).togetherWith(fadeOut() + shrinkVertically())
                                    },
                                    label = "subtitle"
                                ) { saved ->
                                    if (saved) {
                                        Text(
                                            text = "✓ 已保存",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                            maxLines = 1
                                        )
                                    } else {
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
                            }
                        }
                        // ── 同目录文件下拉菜单（无选项卡模式）──
                        DropdownMenu(
                            expanded = showFileDropdown,
                            onDismissRequest = { showFileDropdown = false }
                        ) {
                            siblingFiles.forEach { f ->
                                val isCurrentFile = f.absolutePath == filePath
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = f.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isCurrentFile) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isCurrentFile)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        showFileDropdown = false
                                        if (!isCurrentFile) {
                                            onOpenNewTab?.invoke(f.absolutePath)
                                        }
                                    }
                                )
                            }
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
                expandedHeight = 52.dp,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
                )
                // ── 文件选项卡栏 ──────────────────────────────────
                if (showTabStrip) {
                    EditorTabStrip(
                        tabFilePaths = tabFilePaths,
                        activeTabIndex = activeTabIndex,
                        onTabSelected = onTabSelected,
                        onTabClose = onTabClose
                    )
                }
            }
        },
        bottomBar = {
            val isImeVisible = WindowInsets.isImeVisible
            Column {
                // ── Replit 风格状态栏：键盘弹出时隐藏 ──────────────────────
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
                                .height(30.dp)
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ── 左侧：语言与诊断（已彻底删除无用的 AI 图标模块） ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 语言类型 ({} Kotlin/JS 风格)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = "{}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    StatusBarLabel(
                                        text = fileExtension.uppercase().ifBlank { "TEXT" }
                                    )
                                }

                                StatusBarDot()

                                // 错误提示 (语义红) - 绑定动态 errorCount
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Errors",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    StatusBarLabel("$errorCount", color = MaterialTheme.colorScheme.error)
                                }

                                // 警告提示 (语义橙) - 绑定动态 warningCount
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warnings",
                                        tint = Color(0xFFF5A623),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    StatusBarLabel("$warningCount", color = Color(0xFFF5A623))
                                }
                            }

                            // ── 右侧：光标定位、排版与同步（已升级动态缩进与动态编码展示） ──
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 光标位置
                                StatusBarLabel("Ln $cursorLine, Col $cursorCol")

                                StatusBarDot()

                                // 动态缩进规格 (例如显示 Tab: 4 或 Spaces: 2)
                                StatusBarLabel(indentLabel)

                                StatusBarDot()

                                // 动态显示文件解析出来的编码格式 (UTF-8 或 GBK)
                                StatusBarLabel(fileEncoding)

                                // 历史/同步图标
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "History",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(11.dp)
                                )
                            }
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
                            },
                            hasFileTree = treeProject != null,
                            onOpenFileTree = { showFileTree = true }
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

                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                        this.settings.apply {
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
                                onStatsChangedCallback = { lines, length, indent ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        linesCount = lines
                                        charCount = length
                                        indentLabel = indent
                                        // 用户编辑才标记为已修改（程序注入时 suppressMod=true）
                                        if (!suppressMod.get() && isFileLoaded && isEditorReady) {
                                            isModified = true
                                        }
                                    }
                                },
                                onCursorChangedCallback = { line, col ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        cursorLine = line
                                        cursorCol = col
                                    }
                                },
                                onDiagnosticsChangedCallback = { errors, warnings ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        errorCount = errors
                                        warningCount = warnings
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
                                isWebViewLoading = false
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

                        // 允许 CodeMirror 内部横向滚动：
                        // 在超过触摸斜率阈值后判断手势方向，一旦确认横向则在整个手势生命周期
                        // 内持续禁止父层拦截，使 cm-scroller overflow:auto 的横向滚动正常工作。
                        val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
                        var touchStartX = 0f
                        var touchStartY = 0f
                        var horizontalScrollLocked = false
                        @Suppress("ClickableViewAccessibility")
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    touchStartX = event.x
                                    touchStartY = event.y
                                    horizontalScrollLocked = false
                                    // DOWN 阶段不干预，让父层保留初始判断权
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (!horizontalScrollLocked) {
                                        val dx = abs(event.x - touchStartX)
                                        val dy = abs(event.y - touchStartY)
                                        // 超过系统触摸斜率阈值后才判断方向，避免微抖动误判
                                        if (dx > touchSlop || dy > touchSlop) {
                                            if (dx > dy) {
                                                // 确认横向手势：锁定并持续禁止父层拦截
                                                horizontalScrollLocked = true
                                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                            }
                                            // 纵向手势：不修改拦截状态，让父层/WebView 正常处理
                                        }
                                    }
                                    // horizontalScrollLocked 为 true 时不再重复调用，保持已设置的状态
                                }
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> {
                                    horizontalScrollLocked = false
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false // 不消费事件，继续交给 WebView 默认处理
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webView ->
                    webView.destroy()
                    webViewRef = null
                }
            )

            if (isWebViewLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isDarkTheme) Color(0xFF141729) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    // ── 文件树底部抽屉 ──────────────────────────────────────────
    if (showFileTree && treeProject != null) {
        FileExplorerSheet(
            project = treeProject,
            onDismiss = { showFileTree = false },
            onOpenFile = { newFilePath ->
                showFileTree = false
                if (newFilePath != filePath) {
                    onOpenNewTab?.invoke(newFilePath)
                }
            }
        )
    }
}

// ═════════════════════════════════════════════════════════════
// 文件选项卡栏
// ═════════════════════════════════════════════════════════════
@Composable
private fun EditorTabStrip(
    tabFilePaths: List<String>,
    activeTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onTabClose: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val tabBarBg = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val activeTabBg = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    val activeBorderColor = MaterialTheme.colorScheme.primary

    Surface(
        color = tabBarBg,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabFilePaths.forEachIndexed { index, path ->
                val isActive = index == activeTabIndex
                val isSafPath = path.startsWith("content://")
                val tabName = if (isSafPath) {
                    Uri.decode(Uri.parse(path).lastPathSegment ?: "")
                        .substringAfterLast('/')
                        .ifBlank { "untitled" }
                } else {
                    path.substringAfterLast('/').ifBlank { "untitled" }
                }
                val tabBg = if (isActive) activeTabBg else tabBarBg
                val textColor = if (isActive) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(
                            if (isActive) Modifier.border(
                                BorderStroke(1.5.dp, activeBorderColor),
                                shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            ) else Modifier
                        )
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(tabBg)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tabName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 11.sp
                            ),
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onTabClose(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭选项卡",
                                modifier = Modifier.size(10.dp),
                                tint = if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
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

@Composable
private fun StatusBarDot() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(3.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
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
    modifier: Modifier = Modifier,
    hasFileTree: Boolean = false,
    onOpenFileTree: () -> Unit = {}
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── 左侧：保存 + 软键盘 ──
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "保存文件",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

            Spacer(modifier = Modifier.weight(1f))

            // ── 右侧：文件树入口 ──
            if (hasFileTree) {
                IconButton(onClick = onOpenFileTree) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = "文件树",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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