package com.example.myapplication.ui.screen

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
import androidx.lifecycle.viewmodel.compose.viewModel

// ─────────────────────────────────────────────
// 环境状态机枚举
// ─────────────────────────────────────────────
enum class EnvironmentState {
    Checking,
    NotInstalled,
    Downloading,
    Extracting,
    Initializing,   // 解压完成后的环境初始化（sources.list / DNS / bashrc）
    Ready
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    vm: TerminalViewModel = viewModel()
) {
    val listState = rememberLazyListState()

    // ── 从 ViewModel 读取持久状态 ──
    val terminalLines = vm.terminalLines
    val commandHistory = vm.commandHistory
    val envState = vm.envState
    val downloadProgress = vm.downloadProgress
    val currentStatusMessage = vm.currentStatusMessage

    // ── 纯 UI 状态（切 Tab 可以重置，不需要保活）──
    val isKeyboardVisible = WindowInsets.isImeVisible
    val focusRequester = remember { FocusRequester() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var currentInput by remember { mutableStateOf(TextFieldValue("")) }
    var isCtrlPressed by remember { mutableStateOf(false) }
    var showSecondaryPanel by remember { mutableStateOf(false) }

    // ── Termius 主题配色 ──
    val terminalBackground = Color(0xFF111625)
    val terminalTextColor = Color(0xFFE2E8F0)

    // ── 工具栏按键处理（纯 UI 操作，不涉及 Shell 进程）──
    fun handleToolbarKeyPress(key: String) {
        val text = currentInput.text
        val selection = currentInput.selection
        when (key) {
            "Esc" -> currentInput = TextFieldValue("")
            "Ctrl" -> isCtrlPressed = !isCtrlPressed
            "Tab" -> {
                val newText = text.substring(0, selection.start) + "\t" + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + 1))
            }
            "↑" -> {
                if (commandHistory.isNotEmpty()) {
                    if (historyIndex == -1) historyIndex = commandHistory.size - 1
                    else if (historyIndex > 0) historyIndex--
                    val h = commandHistory[historyIndex]
                    currentInput = TextFieldValue(h, TextRange(h.length))
                }
            }
            "↓" -> {
                if (commandHistory.isNotEmpty()) {
                    if (historyIndex != -1) {
                        if (historyIndex < commandHistory.size - 1) {
                            historyIndex++
                            val h = commandHistory[historyIndex]
                            currentInput = TextFieldValue(h, TextRange(h.length))
                        } else {
                            historyIndex = -1
                            currentInput = TextFieldValue("")
                        }
                    }
                }
            }
            "←" -> {
                if (selection.start > 0)
                    currentInput = currentInput.copy(selection = TextRange(selection.start - 1))
            }
            "→" -> {
                if (selection.end < text.length)
                    currentInput = currentInput.copy(selection = TextRange(selection.end + 1))
            }
            "Home" -> currentInput = currentInput.copy(selection = TextRange(0))
            "End" -> currentInput = currentInput.copy(selection = TextRange(text.length))
            "{ }", "[ ]", "( )" -> {
                val sym = when (key) { "{ }" -> "{}"; "[ ]" -> "[]"; else -> "()" }
                val newText = text.substring(0, selection.start) + sym + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + 1))
            }
            else -> {
                val newText = text.substring(0, selection.start) + key + text.substring(selection.end)
                currentInput = TextFieldValue(newText, TextRange(selection.start + key.length))
            }
        }
    }

    // ── 回到终端 Tab 时自动请求焦点 ──
    LaunchedEffect(envState) {
        if (envState == EnvironmentState.Ready) {
            try { focusRequester.requestFocus() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ── 智能随动滚动 ──
    LaunchedEffect(terminalLines.size, isKeyboardVisible) {
        if (envState == EnvironmentState.Ready && terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size)
        }
    }

    // ── 注意：不再有 DisposableEffect 销毁进程，进程生命周期由 ViewModel 管理 ──

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
                ) { focusRequester.requestFocus() }
        ) {
            // A. 终端滚动输出区
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(terminalLines) { line ->
                    val textColor = when {
                        line.startsWith("❌") || line.contains("Error") || line.contains("inaccessible") ->
                            Color(0xFFEF4444)
                        line.startsWith("[sandbox@debian") -> Color(0xFF38BDF8)
                        line.startsWith("Welcome") || line.startsWith("Type") || line.contains("successfully") ->
                            Color(0xFF22C55E)
                        line.startsWith("⚠️") -> Color(0xFFFBBF24)
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

                // 随流命令输入行
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
                                    isCtrlPressed = false
                                    currentInput = TextFieldValue("")
                                    vm.restartShell()
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
                                        terminalLines.add("[sandbox@debian:~]$ $cmd")
                                        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                                            commandHistory.add(cmd)
                                        }
                                        historyIndex = -1
                                        if (cmd == "clear") {
                                            terminalLines.clear()
                                        } else {
                                            vm.sendCommand(cmd)
                                        }
                                        currentInput = TextFieldValue("")
                                    }
                                }
                            )
                        )
                    }
                }
            }

            // B. Termius 风格工具栏
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
                        TerminalKeyButton(icon = Icons.Default.TouchApp) { }
                        TerminalKeyDivider()
                        TerminalKeyButton(text = "Esc") { handleToolbarKeyPress("Esc") }
                        TerminalKeyDivider()
                        TerminalKeyButton(
                            text = "Ctrl",
                            isActive = isCtrlPressed,
                            accentColor = Color(0xFF00D2D7)
                        ) { handleToolbarKeyPress("Ctrl") }
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
                        ) { showSecondaryPanel = !showSecondaryPanel }
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
                                "Tab", "{ }", "[ ]", "( )", "Home", "End", "|", "/", "\\", "_",
                                "-", "&", "$", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8"
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
        // 安装提示弹窗
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.NotInstalled) {
            AlertDialog(
                onDismissRequest = { },
                confirmButton = {
                    Button(onClick = { vm.startInstallDebian() }) {
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
        // 下载 / 解压进度弹窗
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.Downloading ||
            envState == EnvironmentState.Extracting ||
            envState == EnvironmentState.Initializing
        ) {
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
                        // 标题随状态变化
                        Text(
                            text = when (envState) {
                                EnvironmentState.Downloading   -> "正在获取 Debian 系统镜像…"
                                EnvironmentState.Extracting    -> "正在构建根文件系统…"
                                EnvironmentState.Initializing  -> "正在初始化运行环境…"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (envState == EnvironmentState.Downloading) {
                            // 下载：显示带百分比的线性进度条
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
                            // 解压 / 初始化：不定时 spinner
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

                            // 初始化阶段：展示正在完成的配置项列表
                            if (envState == EnvironmentState.Initializing) {
                                val steps = listOf(
                                    "APT 镜像源（USTC）",
                                    "DNS 解析（阿里/腾讯）",
                                    "主机名 & hosts",
                                    "Shell 环境（.bashrc）",
                                    "APT 优化配置"
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    steps.forEach { step ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "·",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = step,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // 加载中遮罩
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.Checking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(terminalBackground),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                    Text(
                        text = "正在检测 Linux 环境…",
                        style = TextStyle(
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Termius 风格等宽均分按键
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
            .weight(1f)
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
// 精致的半高分割线
// ─────────────────────────────────────────────
@Composable
fun TerminalKeyDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight(0.55f)
            .background(Color(0xFF2E384D))
    )
}

// ─────────────────────────────────────────────
// 命令行提示符前缀
// ─────────────────────────────────────────────
@Composable
fun PromptPrefix() {
    Text(text = "[", style = TextStyle(color = Color(0xFF00D2D7), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    Text(text = "sandbox@debian", style = TextStyle(color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    Text(text = ":", style = TextStyle(color = Color(0xFF94A3B8), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    Text(text = "~", style = TextStyle(color = Color(0xFF22C55E), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    Text(text = "]$ ", style = TextStyle(color = Color(0xFF00D2D7), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold))
}
