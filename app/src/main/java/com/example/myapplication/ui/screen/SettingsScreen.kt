package com.example.myapplication.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────
// 颜色
// ─────────────────────────────────────────────────────────────────
private val CardBg        = Color(0xFF1E2130)
private val DividerColor  = Color(0xFF2A2D3E)
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
private val ValueColor    = Color(0xFF8A8FA8)
private val ChevronColor  = Color(0xFF555A70)

// ─────────────────────────────────────────────────────────────────
// 枚举
// ─────────────────────────────────────────────────────────────────
private enum class ThemeOption(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}
private enum class TabWidthOption(val label: String) {
    TWO("2 个空格"), FOUR("4 个空格"), EIGHT("8 个空格")
}
private enum class AutoSaveInterval(val label: String) {
    S30("30 秒"), MIN1("1 分钟"), MIN3("3 分钟"), MIN5("5 分钟")
}
private enum class EncodingOption(val label: String) {
    AUTO("自动检测"), UTF8("UTF-8"), GBK("GBK / GB18030"), UTF16("UTF-16")
}
private enum class TerminalThemeOption(val label: String) {
    TERMIUS("Termius 深蓝"), DARK("纯黑"), GRAY("深灰")
}
private enum class DefaultSortOption(val label: String) {
    DEFAULT("默认"), NAME_ASC("名称 A→Z"), NAME_DESC("名称 Z→A"), TYPE("按类型")
}

// ─────────────────────────────────────────────────────────────────
// 主入口
// ─────────────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {

    // 外观
    var themeOption         by remember { mutableStateOf(ThemeOption.SYSTEM) }
    // 编辑器
    var editorFontSize      by remember { mutableStateOf(14f) }
    var autoComplete        by remember { mutableStateOf(true) }
    var showLineNumbers     by remember { mutableStateOf(true) }
    var wordWrap            by remember { mutableStateOf(false) }
    var tabWidth            by remember { mutableStateOf(TabWidthOption.FOUR) }
    var autoSave            by remember { mutableStateOf(false) }
    var autoSaveInterval    by remember { mutableStateOf(AutoSaveInterval.MIN1) }
    var encoding            by remember { mutableStateOf(EncodingOption.AUTO) }
    // 终端
    var terminalFontSize    by remember { mutableStateOf(13f) }
    var terminalTheme       by remember { mutableStateOf(TerminalThemeOption.TERMIUS) }
    var keepScreenOn        by remember { mutableStateOf(false) }
    // 项目
    var defaultSort         by remember { mutableStateOf(DefaultSortOption.DEFAULT) }
    var confirmDelete       by remember { mutableStateOf(true) }
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
                    value = themeOption.label,
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
                    value = "${editorFontSize.toInt()} sp",
                    onClick = { showEditorFontDialog = true }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.Translate,
                    iconBg = IconBgDefault,
                    title = "文件编码",
                    value = encoding.label,
                    onClick = { showEncodingDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.AutoAwesome,
                    iconBg = IconBgDefault,
                    title = "自动补全",
                    checked = autoComplete,
                    onCheckedChange = { autoComplete = it }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.FormatListNumbered,
                    iconBg = IconBgDefault,
                    title = "显示行号",
                    checked = showLineNumbers,
                    onCheckedChange = { showLineNumbers = it }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.WrapText,
                    iconBg = IconBgDefault,
                    title = "自动换行",
                    checked = wordWrap,
                    onCheckedChange = { wordWrap = it }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.SpaceBar,
                    iconBg = IconBgDefault,
                    title = "Tab 宽度",
                    value = tabWidth.label,
                    onClick = { showTabWidthDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.Save,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "自动保存",
                    checked = autoSave,
                    onCheckedChange = { autoSave = it }
                )
                if (autoSave) {
                    CardDivider()
                    CardRowClickable(
                        icon = Icons.Filled.Timer,
                        iconBg = IconBgGreen,
                        iconTint = IconTintGreen,
                        title = "保存间隔",
                        value = autoSaveInterval.label,
                        isLast = true,
                        onClick = { showAutoSaveDialog = true }
                    )
                } else {
                    // isLast on the switch row when autoSave is off
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
                    value = "${terminalFontSize.toInt()} sp",
                    onClick = { showTerminalFontDialog = true }
                )
                CardDivider()
                CardRowClickable(
                    icon = Icons.Filled.ColorLens,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "配色方案",
                    value = terminalTheme.label,
                    onClick = { showTerminalThemeDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.PhoneAndroid,
                    iconBg = IconBgGreen,
                    iconTint = IconTintGreen,
                    title = "屏幕常亮",
                    subtitle = "终端运行时保持屏幕不息屏",
                    checked = keepScreenOn,
                    isLast = true,
                    onCheckedChange = { keepScreenOn = it }
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
                    value = defaultSort.label,
                    onClick = { showDefaultSortDialog = true }
                )
                CardDivider()
                CardRowSwitch(
                    icon = Icons.Filled.DeleteForever,
                    iconBg = IconBgRed,
                    iconTint = IconTintRed,
                    title = "删除前确认",
                    subtitle = "删除项目时弹出确认对话框",
                    checked = confirmDelete,
                    isLast = true,
                    onCheckedChange = { confirmDelete = it }
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

    // ── 清除缓存确认 ──────────────────────────────────────────────
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null,
                tint = IconTintRed) },
            title = { Text("清理终端缓存") },
            text = { Text("将清除终端历史记录和临时文件，不会影响已安装的 Debian 环境。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    showCacheClearedDialog = true
                }) { Text("清理", color = IconTintRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 清理完成提示 ──────────────────────────────────────────────
    if (showCacheClearedDialog) {
        AlertDialog(
            onDismissRequest = { showCacheClearedDialog = false },
            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null,
                tint = IconTintGreen) },
            title = { Text("清理完成") },
            text = { Text("终端缓存已清除。") },
            confirmButton = {
                TextButton(onClick = { showCacheClearedDialog = false }) { Text("好的") }
            }
        )
    }

    // ── 检查更新 ──────────────────────────────────────────────────
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
            title = { Text("检查更新") },
            text = { Text("当前已是最新版本 1.0.0") },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("好的") }
            }
        )
    }

    // ── 开源许可 ──────────────────────────────────────────────────
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
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") }
            }
        )
    }

    // ── 主题 ──────────────────────────────────────────────────────
    if (showThemeDialog) {
        SingleChoiceDialog(
            title = "选择主题",
            options = ThemeOption.entries.map { it.label },
            selectedIndex = ThemeOption.entries.indexOf(themeOption),
            onSelect = { themeOption = ThemeOption.entries[it] },
            onDismiss = { showThemeDialog = false }
        )
    }

    // ── Tab 宽度 ──────────────────────────────────────────────────
    if (showTabWidthDialog) {
        SingleChoiceDialog(
            title = "Tab 宽度",
            options = TabWidthOption.entries.map { it.label },
            selectedIndex = TabWidthOption.entries.indexOf(tabWidth),
            onSelect = { tabWidth = TabWidthOption.entries[it] },
            onDismiss = { showTabWidthDialog = false }
        )
    }

    // ── 文件编码 ──────────────────────────────────────────────────
    if (showEncodingDialog) {
        SingleChoiceDialog(
            title = "文件编码",
            options = EncodingOption.entries.map { it.label },
            selectedIndex = EncodingOption.entries.indexOf(encoding),
            onSelect = { encoding = EncodingOption.entries[it] },
            onDismiss = { showEncodingDialog = false }
        )
    }

    // ── 自动保存间隔 ──────────────────────────────────────────────
    if (showAutoSaveDialog) {
        SingleChoiceDialog(
            title = "自动保存间隔",
            options = AutoSaveInterval.entries.map { it.label },
            selectedIndex = AutoSaveInterval.entries.indexOf(autoSaveInterval),
            onSelect = { autoSaveInterval = AutoSaveInterval.entries[it] },
            onDismiss = { showAutoSaveDialog = false }
        )
    }

    // ── 终端配色 ──────────────────────────────────────────────────
    if (showTerminalThemeDialog) {
        SingleChoiceDialog(
            title = "终端配色方案",
            options = TerminalThemeOption.entries.map { it.label },
            selectedIndex = TerminalThemeOption.entries.indexOf(terminalTheme),
            onSelect = { terminalTheme = TerminalThemeOption.entries[it] },
            onDismiss = { showTerminalThemeDialog = false }
        )
    }

    // ── 默认排序 ──────────────────────────────────────────────────
    if (showDefaultSortDialog) {
        SingleChoiceDialog(
            title = "默认排序方式",
            options = DefaultSortOption.entries.map { it.label },
            selectedIndex = DefaultSortOption.entries.indexOf(defaultSort),
            onSelect = { defaultSort = DefaultSortOption.entries[it] },
            onDismiss = { showDefaultSortDialog = false }
        )
    }

    // ── 编辑器字体 ────────────────────────────────────────────────
    if (showEditorFontDialog) {
        FontSizeDialog(
            title = "编辑器字体大小",
            value = editorFontSize,
            range = 10f..24f,
            onConfirm = { editorFontSize = it },
            onDismiss = { showEditorFontDialog = false }
        )
    }

    // ── 终端字体 ──────────────────────────────────────────────────
    if (showTerminalFontDialog) {
        FontSizeDialog(
            title = "终端字体大小",
            value = terminalFontSize,
            range = 10f..24f,
            onConfirm = { terminalFontSize = it },
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
// 圆角卡片容器
// ─────────────────────────────────────────────────────────────────
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
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
        color = DividerColor
    )
}

// ─────────────────────────────────────────────────────────────────
// 可点击行（右侧值 + 箭头）
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardRowClickable(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = Color(0xFFCCCCCC),
    title: String,
    value: String,
    isLast: Boolean = false,
    onClick: () -> Unit
) {
    val shape = if (isLast)
        RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else RoundedCornerShape(0.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon, iconBg, iconTint)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White,
            modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = ValueColor)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            tint = ChevronColor, modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 只读信息行（右侧 badge 文字，无箭头）
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardRowInfo(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = Color(0xFFCCCCCC),
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
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White,
            modifier = Modifier.weight(1f))
        Text(badge, style = MaterialTheme.typography.bodyMedium, color = ValueColor)
    }
}

// ─────────────────────────────────────────────────────────────────
// Switch 行
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CardRowSwitch(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color = Color(0xFFCCCCCC),
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
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ValueColor)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.height(28.dp))
    }
}

// ─────────────────────────────────────────────────────────────────
// 图标盒子（圆角正方形）
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
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
                    Text("${range.start.toInt()} sp", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${range.endInclusive.toInt()} sp", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current); onDismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
