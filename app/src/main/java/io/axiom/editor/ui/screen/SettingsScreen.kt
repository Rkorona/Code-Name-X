package io.axiom.editor.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.axiom.editor.data.*

// ─────────────────────────────────────────────────────────────────
// 固定强调色（主题无关，仅用于图标背景）
// ─────────────────────────────────────────────────────────────────
private val LabelBlue     = Color(0xFF4D9FFF)
private val IconBgDefault = Color(0xFF3A3F55)
private val IconBgGreen   = Color(0xFF153D2E)
private val IconBgOrange  = Color(0xFF3D2A15)
private val IconBgPurple  = Color(0xFF2D1E3E)
private val IconBgRed     = Color(0xFF3E1E1E)
private val IconTintGreen  = Color(0xFF2ECC8E)
private val IconTintOrange = Color(0xFFE89A3C)
private val IconTintPurple = Color(0xFFBB86FC)
private val IconTintRed    = Color(0xFFEF4444)
private val IconTintDefault = Color(0xFFCCCCCC)

// ─────────────────────────────────────────────────────────────────
// 主入口
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    SettingsContent(
        viewModel = viewModel,
        modifier = modifier
    )
}

@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val s = viewModel.settings
    val context = LocalContext.current

    // 弹窗开关
    var showThemeDialog         by remember { mutableStateOf(false) }
    var showTabWidthDialog      by remember { mutableStateOf(false) }
    var showEditorFontDialog    by remember { mutableStateOf(false) }
    var showTerminalFontDialog  by remember { mutableStateOf(false) }
    var showAutoSaveDialog      by remember { mutableStateOf(false) }
    var showEncodingDialog      by remember { mutableStateOf(false) }
    var showTerminalThemeDialog by remember { mutableStateOf(false) }
    var showDefaultSortDialog   by remember { mutableStateOf(false) }
    var showClearCacheDialog    by remember { mutableStateOf(false) }
    var showCacheClearedDialog  by remember { mutableStateOf(false) }
    var showUpdateDialog        by remember { mutableStateOf(false) }
    var showLicenseDialog       by remember { mutableStateOf(false) }

    // 字体文件选择器（编辑器）
    val editorFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.ifBlank { "custom_font" }
                ?: "custom_font"
            viewModel.setEditorFont(uri.toString(), name)
        }
    }

    // 字体文件选择器（终端）
    val terminalFontLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.ifBlank { "custom_font" }
                ?: "custom_font"
            viewModel.setTerminalFont(uri.toString(), name)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {

        // ── 外观 ──────────────────────────────────────────────────
        item {
            SectionLabel("外观")
            SettingsCard {
                CardRowClickable(
                    icon = Icons.Filled.Palette,
                    iconBg = IconBgDefault,
                    title = "主题",
                    value = s.themeOption.label,
                    isLast = true,
                    onClick = { showThemeDialog = true }
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 编辑器 ────────────────────────────────────────────────
        item {
            SectionLabel("编辑器")
            SettingsCard {
                CardRowClickable(
                    icon = Icons.Filled.FormatSize,
                    iconBg = IconBgDefault,
                    title = "字体大小",
                    value = "${s.editorFontSize.toInt()} sp",
                    onClick = { showEditorFontDialog = true }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.FontDownload,
                    iconBg = IconBgDefault,
                    title = "自定义字体",
                    value = if (s.editorFontName.isNotEmpty()) s.editorFontName else "系统默认",
                    onClick = { editorFontLauncher.launch(arrayOf("*/*")) },
                    onLongClick = if (s.editorFontName.isNotEmpty()) {
                        { viewModel.clearEditorFont() }
                    } else null,
                    subtitle = if (s.editorFontName.isNotEmpty()) "长按重置为默认" else ""
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.Translate,
                    iconBg = IconBgDefault,
                    title = "文件编码",
                    value = s.fileEncoding.label,
                    onClick = { showEncodingDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.AutoAwesome,
                    iconBg = IconBgDefault,
                    title = "自动补全",
                    checked = s.autoComplete,
                    onCheckedChange = { viewModel.setAutoComplete(it) }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.FormatListNumbered,
                    iconBg = IconBgDefault,
                    title = "显示行号",
                    checked = s.showLineNumbers,
                    onCheckedChange = { viewModel.setShowLineNumbers(it) }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.WrapText,
                    iconBg = IconBgDefault,
                    title = "自动换行",
                    checked = s.wordWrap,
                    onCheckedChange = { viewModel.setWordWrap(it) }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.SpaceBar,
                    iconBg = IconBgDefault,
                    title = "Tab 宽度",
                    value = s.tabWidth.label,
                    onClick = { showTabWidthDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.Save,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "自动保存",
                    checked = s.autoSave,
                    onCheckedChange = { viewModel.setAutoSave(it) }
                )
                if (s.autoSave) {
                    CardDivider()
                    CardRowClickable(
                        icon = Icons.Filled.Timer,
                        iconBg = IconBgGreen,
                        iconTint = IconTintGreen,
                        title = "保存间隔",
                        value = s.autoSaveInterval.label,
                        isLast = true,
                        onClick = { showAutoSaveDialog = true }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 终端 ──────────────────────────────────────────────────
        item {
            SectionLabel("终端")
            SettingsCard {
                CardRowClickable(
                    icon = Icons.Filled.Terminal,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "字体大小",
                    value = "${s.terminalFontSize.toInt()} sp",
                    onClick = { showTerminalFontDialog = true }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.FontDownload,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "自定义字体",
                    value = if (s.terminalFontName.isNotEmpty()) s.terminalFontName else "系统默认",
                    onClick = { terminalFontLauncher.launch(arrayOf("*/*")) },
                    onLongClick = if (s.terminalFontName.isNotEmpty()) {
                        { viewModel.clearTerminalFont() }
                    } else null,
                    subtitle = if (s.terminalFontName.isNotEmpty()) "长按重置为默认" else ""
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.ColorLens,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "配色方案",
                    value = s.terminalTheme.label,
                    onClick = { showTerminalThemeDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.PhoneAndroid,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "屏幕常亮",
                    subtitle = "终端运行时保持屏幕不息屏",
                    checked = s.keepScreenOn,
                    isLast = true,
                    onCheckedChange = { viewModel.setKeepScreenOn(it) }
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 项目 ──────────────────────────────────────────────────
        item {
            SectionLabel("项目")
            SettingsCard {
                CardRowClickable(
                    icon = Icons.Filled.Sort,
                    iconBg = IconBgOrange,
                    iconTint = IconTintOrange,
                    title = "默认排序",
                    value = s.defaultSort.label,
                    onClick = { showDefaultSortDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.DeleteForever,
                    iconBg = IconBgRed,
                    iconTint = IconTintRed,
                    title = "删除前确认",
                    subtitle = "删除项目时弹出确认对话框",
                    checked = s.confirmDelete,
                    isLast = true,
                    onCheckedChange = { viewModel.setConfirmDelete(it) }
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 存储 ──────────────────────────────────────────────────
        item {
            SectionLabel("存储")
            SettingsCard {
                CardRowInfo(
                    icon = Icons.Filled.Memory,
                    iconBg = IconBgPurple,
                    iconTint = IconTintPurple,
                    title = "Debian 环境",
                    badge = "已安装"
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.CleaningServices,
                    iconBg = IconBgRed,
                    iconTint = IconTintRed,
                    title = "清理终端缓存",
                    value = "",
                    isLast = true,
                    onClick = { showClearCacheDialog = true }
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 关于 ──────────────────────────────────────────────────
        item {
            SectionLabel("关于")
            SettingsCard {
                CardRowInfo(
                    icon = Icons.Filled.Info,
                    iconBg = IconBgDefault,
                    title = "版本",
                    badge = "1.0.0"
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.SystemUpdate,
                    iconBg = IconBgDefault,
                    title = "检查更新",
                    value = "",
                    onClick = { showUpdateDialog = true }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.Article,
                    iconBg = IconBgDefault,
                    title = "开源许可",
                    value = "",
                    isLast = true,
                    onClick = { showLicenseDialog = true }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 弹窗 ─────────────────────────────────────────────────────

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null, tint = IconTintRed) },
            title = { Text("清理终端缓存") },
            text = { Text("将清除终端历史记录和临时文件，不会影响已安装的 Debian 环境。") },
            confirmButton = {
                TextButton(onClick = { showClearCacheDialog = false; showCacheClearedDialog = true }) {
                    Text("清理", color = IconTintRed)
                }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") } }
        )
    }

    if (showCacheClearedDialog) {
        AlertDialog(
            onDismissRequest = { showCacheClearedDialog = false },
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = IconTintGreen) },
            title = { Text("清理完成") },
            text = { Text("终端缓存已清除。") },
            confirmButton = { TextButton(onClick = { showCacheClearedDialog = false }) { Text("好的") } }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
            title = { Text("检查更新") },
            text = { Text("当前已是最新版本 1.0.0") },
            confirmButton = { TextButton(onClick = { showUpdateDialog = false }) { Text("好的") } }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            icon = { Icon(Icons.Filled.Article, contentDescription = null) },
            title = { Text("开源许可") },
            text = {
                Text(
                    "本项目使用以下开源库：\n\n" +
                    "• Jetpack Compose — Apache 2.0\n" +
                    "• Room — Apache 2.0\n" +
                    "• PRoot — GPL v2\n" +
                    "• Material Icons — Apache 2.0\n" +
                    "• CodeMirror 6 — MIT",
                    lineHeight = 22.sp
                )
            },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") } }
        )
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "选择主题",
            options = ThemeMode.entries.map { it.label },
            selectedIndex = ThemeMode.entries.indexOf(s.themeOption),
            onSelect = { viewModel.setThemeOption(ThemeMode.entries[it]) },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showTabWidthDialog) {
        SingleChoiceDialog(
            title = "Tab 宽度",
            options = TabWidthMode.entries.map { it.label },
            selectedIndex = TabWidthMode.entries.indexOf(s.tabWidth),
            onSelect = { viewModel.setTabWidth(TabWidthMode.entries[it]) },
            onDismiss = { showTabWidthDialog = false }
        )
    }

    if (showEncodingDialog) {
        SingleChoiceDialog(
            title = "文件编码",
            options = EncodingMode.entries.map { it.label },
            selectedIndex = EncodingMode.entries.indexOf(s.fileEncoding),
            onSelect = { viewModel.setFileEncoding(EncodingMode.entries[it]) },
            onDismiss = { showEncodingDialog = false }
        )
    }

    if (showAutoSaveDialog) {
        SingleChoiceDialog(
            title = "自动保存间隔",
            options = AutoSaveMode.entries.map { it.label },
            selectedIndex = AutoSaveMode.entries.indexOf(s.autoSaveInterval),
            onSelect = { viewModel.setAutoSaveInterval(AutoSaveMode.entries[it]) },
            onDismiss = { showAutoSaveDialog = false }
        )
    }

    if (showTerminalThemeDialog) {
        SingleChoiceDialog(
            title = "终端配色方案",
            options = TerminalThemeMode.entries.map { it.label },
            selectedIndex = TerminalThemeMode.entries.indexOf(s.terminalTheme),
            onSelect = { viewModel.setTerminalTheme(TerminalThemeMode.entries[it]) },
            onDismiss = { showTerminalThemeDialog = false }
        )
    }

    if (showDefaultSortDialog) {
        SingleChoiceDialog(
            title = "默认排序方式",
            options = SortMode.entries.map { it.label },
            selectedIndex = SortMode.entries.indexOf(s.defaultSort),
            onSelect = { viewModel.setDefaultSort(SortMode.entries[it]) },
            onDismiss = { showDefaultSortDialog = false }
        )
    }

    if (showEditorFontDialog) {
        FontSizeDialog(
            title = "编辑器字体大小",
            value = s.editorFontSize,
            range = 10f..24f,
            onConfirm = { viewModel.setEditorFontSize(it) },
            onDismiss = { showEditorFontDialog = false }
        )
    }

    if (showTerminalFontDialog) {
        FontSizeDialog(
            title = "终端字体大小",
            value = s.terminalFontSize,
            range = 10f..24f,
            onConfirm = { viewModel.setTerminalFontSize(it) },
            onDismiss = { showTerminalFontDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Section 蓝色标签
// ─────────────────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = LabelBlue,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

// ─────────────────────────────────────────────────────────────────
// 圆角卡片容器（主题自适应）
// ─────────────────────────────────────────────────────────────────
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

// ─────────────────────────────────────────────────────────────────
// 卡片内分割线
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

// ─────────────────────────────────────────────────────────────────
// 可点击行（支持长按）
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRowClickable(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = IconTintDefault,
    title: String,
    value: String,
    subtitle: String = "",
    isLast: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = if (isLast)
        RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else RoundedCornerShape(0.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = if (subtitle.isNotEmpty()) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon, iconBg, iconTint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) {
                Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value.isNotEmpty()) {
            Text(value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 只读信息行
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardRowInfo(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = IconTintDefault,
    title: String,
    badge: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon, iconBg, iconTint)
        Spacer(Modifier.width(14.dp))
        Text(title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Text(badge,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────────────────────────────────────────────
// Switch 行
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardRowSwitch(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = IconTintDefault,
    title: String,
    subtitle: String = "",
    checked: Boolean,
    isLast: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    val shape = if (isLast)
        RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else RoundedCornerShape(0.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon, iconBg, iconTint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            if (subtitle.isNotEmpty()) {
                Text(subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.height(28.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 图标盒子
// ─────────────────────────────────────────────────────────────────
@Composable
private fun IconBox(icon: ImageVector, bg: Color, tint: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 单选弹窗
// ─────────────────────────────────────────────────────────────────
@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(index); onDismiss() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index); onDismiss() }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ─────────────────────────────────────────────────────────────────
// 字体大小滑块弹窗
// ─────────────────────────────────────────────────────────────────
@Composable
private fun FontSizeDialog(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${current.toInt()} sp",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = current,
                    onValueChange = { current = it },
                    valueRange = range,
                    steps = ((range.endInclusive - range.start).toInt() - 1)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${range.start.toInt()} sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${range.endInclusive.toInt()} sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current); onDismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

