package com.example.myapplication.ui.screen

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption

// ─────────────────────────────────────────────
// 环境状态机枚举
// ─────────────────────────────────────────────
enum class EnvironmentState {
    Checking,       // 检测环境现状中
    NotInstalled,   // 尚未部署 Debian 环境
    Downloading,    // 正在下载 rootfs 镜像
    Extracting,     // 正在解压并部署文件系统
    Ready           // 环境就绪，可以运行
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // ── 状态控制 ──
    var envState by remember { mutableStateOf(EnvironmentState.Checking) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var currentStatusMessage by remember { mutableStateOf("正在等待指令…") }

    // ── 键盘及焦点探测 ──
    val isKeyboardVisible = WindowInsets.isImeVisible
    val focusRequester = remember { FocusRequester() }

    // ── 终端历史纪录缓冲区 ──
    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

    // ── 终端控制台流与重构后的 TextFieldValue 状态 ──
    val terminalLines = remember { mutableStateListOf<String>() }
    var currentInput by remember { mutableStateOf(TextFieldValue("")) }

    // ── Termius 极客快捷按键交互状态 ──
    var isCtrlPressed by remember { mutableStateOf(false) }
    var showSecondaryPanel by remember { mutableStateOf(false) }

    // ── 真实交互进程控制 ──
    var shellProcess by remember { mutableStateOf<Process?>(null) }
    var shellWriter by remember { mutableStateOf<BufferedWriter?>(null) }

    // ── 核心路径定义 ──
    val rootfsDir = remember { File(context.filesDir, "debian_rootfs") }
    val tarXzFile = remember { File(context.cacheDir, "rootfs.tar.xz") }
    // 目标高版本镜像源
    val imageUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260627_14%3A22/rootfs.tar.xz"

    // ── Termius 主题配色配置 ──
    val terminalBackground = Color(0xFF111625) // 极地夜航蓝 (Termius Background)
    val terminalTextColor = Color(0xFFE2E8F0)  // 现代灰白主文本 (Termius Text)

    // ── 封装：安全初始化与启动全新 Shell 进程 ──
    fun startNewShell() {
        shellProcess?.destroy() // 先销毁可能残留的旧进程
        isCtrlPressed = false
        
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. 动态检索安装到宿主 lib 目录下的 PRoot 翻译桥接二进制文件（Android 10+ 限制了直接运行 filesDir 目录下的可执行文件，必须从此执行）
                val nativeDir = File(context.applicationInfo.nativeLibraryDir)
                val possibleNames = listOf("libproot.so", "libproot-userland.so", "libproot_real.so")
                var prootFile = possibleNames.map { File(nativeDir, it) }.firstOrNull { it.exists() }
                
                if (prootFile == null) {
                    prootFile = nativeDir.listFiles()?.firstOrNull { 
                        it.name.contains("proot", ignoreCase = true) && !it.name.contains("loader") 
                    }
                }

                val pb = if (prootFile != null && prootFile.exists()) {
                    // ─────────────────────────────────────────────
                    // A. PRoot 桥接器存在：构建命令列表启动真正的 Debian 内部 bash 环境
                    // ─────────────────────────────────────────────
                    val cmd = listOf(
                        prootFile.absolutePath,
                        "-r", rootfsDir.absolutePath,
                        "-0",          // 模拟 root 权限，允许执行 apt / dpkg 包管理
                        "-w", "/root",  // 设定虚拟 Linux 内部的工作目录为 /root
                        "-b", "/dev",  // 绑定系统硬件总线与临时目录，使 Debian 可读写设备
                        "-b", "/proc",
                        "-b", "/sys",
                        "/bin/bash"    // 启动 Debian 内部原生的 Bash 解释器，从而使 apt 命令完全可行
                    )
                    ProcessBuilder(cmd)
                } else {
                    // ─────────────────────────────────────────────
                    // B. PRoot 桥接器未找到：安全降级运行宿主 sh
                    // ─────────────────────────────────────────────
                    withContext(Dispatchers.Main) {
                        terminalLines.add("⚠️ 警告: 未在应用原生路径中找到 PRoot 翻译桥接程序 (libproot.so)，已自动安全降级运行 Android 宿主 sh 外壳。在此降级环境下，apt 等 Debian glibc 二进制文件将无法被执行。")
                    }
                    ProcessBuilder("/system/bin/sh").directory(rootfsDir)
                }

                pb.redirectErrorStream(true)

                // 2. 配置与宿主环境安全隔离的 Linux 系统环境变量
                val env = pb.environment()
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
                env["HOME"] = "/root"
                env["TERM"] = "xterm-256color"
                env["LANG"] = "C.UTF-8"
                env["USER"] = "root"

                val process = pb.start()
                shellProcess = process
                shellWriter = process.outputStream.bufferedWriter()

                val reader = process.inputStream.bufferedReader()
                
                // 持续行式读取后台进程的流输出，并推入 UI
                var line = reader.readLine()
                while (line != null) {
                    val finalLine = line
                    withContext(Dispatchers.Main) {
                        terminalLines.add(finalLine)
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    terminalLines.add("❌ 启动终端进程失败: ${e.localizedMessage}")
                }
            }
        }
    }

    // ── 封装：Termius 风格工具栏物理按键统一处理器 ──
    fun handleToolbarKeyPress(key: String) {
        val text = currentInput.text
        val selection = currentInput.selection

        when (key) {
            "Esc" -> {
                currentInput = TextFieldValue("")
            }
            "Ctrl" -> {
                isCtrlPressed = !isCtrlPressed
            }
            "Tab" -> {
                val newText = text.substring(0, selection.start) + "\t" + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + 1))
            }
            "↑" -> {
                if (commandHistory.isNotEmpty()) {
                    if (historyIndex == -1) {
                        historyIndex = commandHistory.size - 1
                    } else if (historyIndex > 0) {
                        historyIndex--
                    }
                    val histCmd = commandHistory[historyIndex]
                    currentInput = TextFieldValue(histCmd, TextRange(histCmd.length))
                }
            }
            "↓" -> {
                if (commandHistory.isNotEmpty()) {
                    if (historyIndex != -1) {
                        if (historyIndex < commandHistory.size - 1) {
                            historyIndex++
                            val histCmd = commandHistory[historyIndex]
                            currentInput = TextFieldValue(histCmd, TextRange(histCmd.length))
                        } else {
                            historyIndex = -1
                            currentInput = TextFieldValue("")
                        }
                    }
                }
            }
            "←" -> {
                if (selection.start > 0) {
                    currentInput = currentInput.copy(selection = TextRange(selection.start - 1))
                }
            }
            "→" -> {
                if (selection.end < text.length) {
                    currentInput = currentInput.copy(selection = TextRange(selection.end + 1))
                }
            }
            "Home" -> {
                currentInput = currentInput.copy(selection = TextRange(0))
            }
            "End" -> {
                currentInput = currentInput.copy(selection = TextRange(text.length))
            }
            "{ }", "[ ]", "( )" -> {
                val symbolToInsert = when (key) {
                    "{ }" -> "{}"
                    "[ ]" -> "[]"
                    "( )" -> "()"
                    else -> ""
                }
                val newText = text.substring(0, selection.start) + symbolToInsert + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + 1))
            }
            else -> {
                val newText = text.substring(0, selection.start) + key + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + key.length))
            }
        }
    }

    // ── 自动检测现存 Debian 环境是否健全 ──
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val isInstalled = isDebianInstalled(rootfsDir)
            if (isInstalled) {
                envState = EnvironmentState.Ready
                withContext(Dispatchers.Main) {
                    if (terminalLines.isEmpty()) {
                        terminalLines.add("Welcome to Debian GNU/Linux 13 (trixie) via PRoot!")
                        terminalLines.add("Type 'help' or explore directories using real Shell.")
                        terminalLines.add("Debian rootfs detected successfully.")
                        terminalLines.add("")
                    }
                }
            } else {
                envState = EnvironmentState.NotInstalled
            }
        }
    }

    // ── 当环境就绪时，拉起 Shell 执行并尝试自动请求输入框焦点 ──
    LaunchedEffect(envState) {
        if (envState == EnvironmentState.Ready) {
            startNewShell()
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── 智能随动滚动：每当日志更新或者键盘弹起，强制将视口对齐最底部的输入框 ──
    LaunchedEffect(terminalLines.size, isKeyboardVisible) {
        if (envState == EnvironmentState.Ready) {
            coroutineScope.launch {
                listState.animateScrollToItem(terminalLines.size)
            }
        }
    }

    // ── 页面销毁（Dispose）时彻底清理后台进程，防止进程泄露 ──
    DisposableEffect(Unit) {
        onDispose {
            shellProcess?.destroy()
        }
    }

    // ── 全屏点击监听介质 ──
    val screenClickInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(terminalBackground)
                .clickable(
                    interactionSource = screenClickInteractionSource,
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
        ) {
            // A. 数据流交互滚动区（包含日志与随流输入框）
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 1. 历史日志组
                items(terminalLines) { line ->
                    val textColor = when {
                        line.startsWith("❌") || line.contains("Error") || line.contains("inaccessible") -> Color(0xFFEF4444) // 警示红
                        line.startsWith("[sandbox@debian") -> Color(0xFF38BDF8) // 命令行输入回显天蓝
                        line.startsWith("Welcome") || line.startsWith("Type") || line.contains("successfully") -> Color(0xFF22C55E) // Fish Shell 翠绿
                        else -> terminalTextColor
                    }
                    Text(
                        text = line,
                        style = TextStyle(
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp
                        )
                    )
                }

                // 2. 随流命令输入行
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PromptPrefix()

                        BasicTextField(
                            value = currentInput,
                            onValueChange = { newVal ->
                                if (isCtrlPressed && newVal.text.lowercase().endsWith("c")) {
                                    terminalLines.add("[sandbox@debian:~]$ ^C")
                                    terminalLines.add("❌ Process Interrupted (Ctrl+C)")
                                    startNewShell() 
                                    currentInput = TextFieldValue("")
                                } else {
                                    currentInput = newVal
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            enabled = envState == EnvironmentState.Ready,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(Color.White),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val cmd = currentInput.text.trim()
                                    if (cmd.isNotBlank()) {
                                        val promptHeader = "[sandbox@debian:~]$ $cmd"
                                        terminalLines.add(promptHeader)

                                        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                                            commandHistory.add(cmd)
                                        }
                                        historyIndex = -1 

                                        if (cmd == "clear") {
                                            terminalLines.clear()
                                        } else {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                try {
                                                    shellWriter?.let { writer ->
                                                        writer.write(cmd + "\n")
                                                        writer.flush()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        terminalLines.add("❌ 命令发送失败: ${e.localizedMessage}")
                                                    }
                                                }
                                            }
                                        }
                                        currentInput = TextFieldValue("") 
                                    }
                                }
                            )
                        )
                    }
                }
            }

            // B. Termius 经典等宽极简辅助工具栏
            if (isKeyboardVisible && envState == EnvironmentState.Ready) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C2330)) 
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .drawBehind {
                                drawLine(
                                    color = Color(0xFF2E384D),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx()
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalKeyButton(icon = Icons.Default.TouchApp) { /* 模拟触控流 */ }
                        TerminalKeyDivider()
                        TerminalKeyButton(text = "Esc") { handleToolbarKeyPress("Esc") }
                        TerminalKeyDivider()
                        TerminalKeyButton(
                            text = "Ctrl",
                            isActive = isCtrlPressed,
                            accentColor = Color(0xFF00D2D7)
                        ) {
                            handleToolbarKeyPress("Ctrl")
                        }
                        TerminalKeyDivider()
                        TerminalKeyButton(icon = Icons.Default.KeyboardArrowUp) { handleToolbarKeyPress("↑") }
                        TerminalKeyDivider()
                        TerminalKeyButton(icon = Icons.Default.KeyboardArrowDown) { handleToolbarKeyPress("↓") }
                        TerminalKeyDivider()
                        TerminalKeyButton(icon = Icons.Default.KeyboardArrowLeft) { handleToolbarKeyPress("←") }
                        TerminalKeyDivider()
                        TerminalKeyButton(icon = Icons.Default.KeyboardArrowRight) { handleToolbarKeyPress("→") }
                        TerminalKeyDivider()
                        TerminalKeyButton(
                            icon = Icons.Default.MoreHoriz,
                            isActive = showSecondaryPanel,
                            accentColor = Color(0xFF38BDF8)
                        ) {
                            showSecondaryPanel = !showSecondaryPanel
                        }
                    }

                    if (showSecondaryPanel) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF141A24))
                                .drawBehind {
                                    drawLine(
                                        color = Color(0xFF242F41),
                                        start = Offset(0f, 0f),
                                        end = Offset(size.width, 0f),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            val secondarySymbols = listOf(
                                "Tab", "{ }", "[ ]", "( )", "Home", "End", "|", "/", "\\", "_", "-", "&", "$",
                                "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8"
                            )
                            secondarySymbols.forEach { sym ->
                                Box(
                                    modifier = Modifier
                                        .width(58.dp)
                                        .height(44.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { handleToolbarKeyPress(sym) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = sym,
                                        style = TextStyle(
                                            color = Color(0xFFE2E8F0),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // 2. 改造后的安装提示弹窗
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.NotInstalled) {
            AlertDialog(
                onDismissRequest = { },
                confirmButton = {
                    Button(
                        onClick = {
                            envState = EnvironmentState.Downloading
                            coroutineScope.launch {
                                val downloadSuccess = performDownloadRootfs(
                                    downloadUrl = imageUrl,
                                    targetFile = tarXzFile,
                                    onProgress = { progress -> downloadProgress = progress },
                                    onStatusChanged = { msg -> currentStatusMessage = msg }
                                )

                                if (downloadSuccess) {
                                    envState = EnvironmentState.Extracting
                                    val extractSuccess = performExtractTarXz(
                                        archiveFile = tarXzFile,
                                        destinationDir = rootfsDir,
                                        onStatusChanged = { msg -> currentStatusMessage = msg }
                                    )

                                    if (extractSuccess) {
                                        envState = EnvironmentState.Ready
                                        terminalLines.add("Debian rootfs dynamic deployment completely successful!")
                                        terminalLines.add("System environment ready. Enjoy full Linux terminal ecosystem.")
                                    } else {
                                        envState = EnvironmentState.NotInstalled
                                    }
                                } else {
                                    envState = EnvironmentState.NotInstalled
                                }
                            }
                        }
                    ) {
                        Text("立即下载并部署")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { }) {
                        Text("暂不配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                title = { Text("配置 Linux 开发运行环境", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "检测到应用尚未安装 Debian Linux 系统容器。运行多语言代码、格式化程序以及启动高级 LSP 服务需要下载并解压大约 90MB 的核心基础包。\n\n建议在 Wi-Fi 网络环境下进行该操作。",
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                        
                        if (currentStatusMessage.startsWith("❌")) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = currentStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }

        // ─────────────────────────────────────────────
        // 3. 弹窗层：全锁定下载与部署进度长效 Dialog
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.Downloading || envState == EnvironmentState.Extracting) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = if (envState == EnvironmentState.Downloading) "正在获取 Debian 系统镜像…" else "正在构建根文件系统…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (envState == EnvironmentState.Downloading) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = currentStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.CenterHorizontally),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = currentStatusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 封装：等宽均分扁平式 Terminal 按键（Termius 风格：下划线指示激活态）
// ─────────────────────────────────────────────
@Composable
fun RowScope.TerminalKeyButton(
    text: String? = null,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    accentColor: Color = Color(0xFF38BDF8),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f) // 完美均分屏幕宽度
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = if (isActive) accentColor else Color(0xFFE2E8F0),
                    modifier = Modifier.size(18.dp)
                )
                text != null -> Text(
                    text = text,
                    style = TextStyle(
                        color = if (isActive) accentColor else Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) accentColor else Color.Transparent)
            )
        }
    }
}

// ─────────────────────────────────────────────
// 封装：精致的半高按键分割线
// ─────────────────────────────────────────────
@Composable
fun TerminalKeyDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight(0.55f) // 分割线仅占用 55% 的高度并垂直居中
            .background(Color(0xFF2E384D))
    )
}

// ─────────────────────────────────────────────
// 封装：命令行提示符前缀
// ─────────────────────────────────────────────
@Composable
fun PromptPrefix() {
    Text(
        text = "[",
        style = TextStyle(
            color = Color(0xFF00D2D7),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
    Text(
        text = "sandbox@debian",
        style = TextStyle(
            color = Color(0xFF38BDF8),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
    Text(
        text = ":",
        style = TextStyle(
            color = Color(0xFF94A3B8),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
    Text(
        text = "~",
        style = TextStyle(
            color = Color(0xFF22C55E),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
    Text(
        text = "]$ ",
        style = TextStyle(
            color = Color(0xFF00D2D7),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
}

// ─────────────────────────────────────────────
// 健全的环境存在性检测函数
// ─────────────────────────────────────────────
private fun isDebianInstalled(rootfsDir: File): Boolean {
    if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false

    val etcDir = File(rootfsDir, "etc")
    val usrDir = File(rootfsDir, "usr")

    val hasEtcPasswd = File(etcDir, "passwd").exists()
    val hasUsrBin = File(usrDir, "bin").exists() && File(usrDir, "bin").isDirectory

    val shFile = File(rootfsDir, "bin/sh")
    val hasShSymlink = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.exists(shFile.toPath(), LinkOption.NOFOLLOW_LINKS)
        } else {
            shFile.exists() || shFile.length() > 0 || shFile.parentFile?.exists() == true
        }
    } catch (e: Exception) {
        false
    }

    return (hasUsrBin && hasEtcPasswd) || (hasShSymlink && hasUsrBin)
}

// ─────────────────────────────────────────────
// 智能创建父级目录（支持 UsrMerge 软链接重定向）
// ─────────────────────────────────────────────
private fun safeCreateParentDirs(file: File, rootfsDir: File) {
    val parent = file.parentFile ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            Files.createDirectories(parent.toPath())
            return
        } catch (e: Exception) {
            // fallback
        }
    }
    parent.mkdirs()
}

// ─────────────────────────────────────────────
// 强力高防网络下载引擎
// ─────────────────────────────────────────────
private suspend fun performDownloadRootfs(
    downloadUrl: String,
    targetFile: File,
    onProgress: (Float) -> Unit,
    onStatusChanged: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    var inputStream: BufferedInputStream? = null
    var outputStream: FileOutputStream? = null
    
    var currentUrl = downloadUrl
    var redirectCount = 0
    val maxRedirects = 5
    var downloadSuccess = false

    try {
        if (targetFile.exists()) {
            targetFile.delete()
        }

        while (redirectCount < maxRedirects) {
            withContext(Dispatchers.Main) { onStatusChanged("正在建立安全数据连接…") }
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false
            
            connection.setRequestProperty(
                "User-Agent", 
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )

            val status = connection.responseCode
            
            if (status == HttpURLConnection.HTTP_MOVED_PERM || 
                status == HttpURLConnection.HTTP_MOVED_TEMP || 
                status == 303 || status == 307 || status == 308) {
                
                val newUrl = connection.getHeaderField("Location")
                if (!newUrl.isNullOrBlank()) {
                    currentUrl = newUrl
                    redirectCount++
                    connection.disconnect()
                    continue
                }
            }

            if (status == HttpURLConnection.HTTP_OK) {
                downloadSuccess = true
                break
            } else {
                withContext(Dispatchers.Main) { 
                    onStatusChanged("❌ 服务器拒绝请求: HTTP $status") 
                }
                return@withContext false
            }
        }

        if (!downloadSuccess) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 错误: CDN 重定向次数过多") }
            return@withContext false
        }

        val fileLength = connection!!.contentLengthLong
        inputStream = BufferedInputStream(connection.inputStream, 16384)
        outputStream = FileOutputStream(targetFile)

        val data = ByteArray(16384)
        var totalBytesRead: Long = 0
        var bytesRead: Int

        while (inputStream.read(data).also { bytesRead = it } != -1) {
            totalBytesRead += bytesRead
            outputStream.write(data, 0, bytesRead)

            if (fileLength > 0) {
                val progress = totalBytesRead.toFloat() / fileLength.toFloat()
                val readMb = String.format("%.1f", totalBytesRead.toFloat() / (1024 * 1024))
                val totalMb = String.format("%.1f", fileLength.toFloat() / (1024 * 1024))

                withContext(Dispatchers.Main) {
                    onStatusChanged("已下载 $readMb MB / $totalMb MB")
                    onProgress(progress)
                }
            } else {
                val readMb = String.format("%.1f", totalBytesRead.toFloat() / (1024 * 1024))
                withContext(Dispatchers.Main) {
                    onStatusChanged("已下载 $readMb MB (流式无界传输中…)")
                    onProgress(0f)
                }
            }
        }
        outputStream.flush()
        return@withContext true
    } catch (e: SecurityException) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 安全限制: 请检查 AndroidManifest.xml 是否配置了 INTERNET 权限")
        }
        return@withContext false
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 网络异常: ${e.localizedMessage ?: "数据握手失败"}")
        }
        return@withContext false
    } finally {
        outputStream?.close()
        inputStream?.close()
        connection?.disconnect()
    }
}

// ─────────────────────────────────────────────
// 支持 Linux 软/硬链接还原的解压部署引擎
// ─────────────────────────────────────────────
private suspend fun performExtractTarXz(
    archiveFile: File,
    destinationDir: File,
    onStatusChanged: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
        destinationDir.mkdirs()

        onStatusChanged("正在解密 XZ 压缩矩阵…")
        val fileIn = archiveFile.inputStream()
        val bufferedIn = BufferedInputStream(fileIn, 16384)
        val xzIn = XZInputStream(bufferedIn)
        val tarIn = TarArchiveInputStream(xzIn)
        
        var entry = tarIn.nextEntry
        var fileCount = 0

        while (entry != null) {
            val targetFile = File(destinationDir, entry.name)
            
            if (!targetFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                entry = tarIn.nextEntry
                continue
            }

            if (entry.isDirectory) {
                targetFile.mkdirs()
            } else if (entry.isSymbolicLink) {
                val target = entry.linkName
                safeCreateParentDirs(targetFile, destinationDir)
                targetFile.delete()
                try {
                    Os.symlink(target, targetFile.absolutePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (entry.isLink) {
                val target = entry.linkName
                safeCreateParentDirs(targetFile, destinationDir)
                targetFile.delete()
                try {
                    val existingFile = File(destinationDir, target.removePrefix("/"))
                    if (existingFile.exists()) {
                        Os.link(existingFile.absolutePath, targetFile.absolutePath)
                    } else {
                        Os.symlink(target, targetFile.absolutePath)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                safeCreateParentDirs(targetFile, destinationDir)
                targetFile.delete()
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(16384)
                    var len: Int
                    while (tarIn.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                    fos.flush()
                }
                
                if (entry.mode and 0x40 != 0 || entry.name.contains("bin/")) {
                    targetFile.setExecutable(true, false)
                }
                targetFile.setReadable(true, false)
            }

            fileCount++
            if (fileCount % 300 == 0) {
                withContext(Dispatchers.Main) {
                    onStatusChanged("已释放 $fileCount 个 Linux 系统节点文件…")
                }
            }
            entry = tarIn.nextEntry
        }

        tarIn.close()
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        return@withContext true
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 部署异常: ${e.localizedMessage ?: "系统解压中断"}")
        }
        return@withContext false
    }
}