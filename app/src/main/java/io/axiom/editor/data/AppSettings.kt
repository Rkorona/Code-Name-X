package io.axiom.editor.data

data class AppSettings(
    val themeOption: ThemeMode = ThemeMode.SYSTEM,
    // 编辑器
    val editorFontSize: Float = 14f,
    val editorFontUri: String = "",
    val editorFontName: String = "",
    val autoComplete: Boolean = true,
    val showLineNumbers: Boolean = true,
    val wordWrap: Boolean = false,
    val tabWidth: TabWidthMode = TabWidthMode.FOUR,
    val autoSave: Boolean = false,
    val autoSaveInterval: AutoSaveMode = AutoSaveMode.MIN1,
    val fileEncoding: EncodingMode = EncodingMode.AUTO,
    // 终端
    val terminalFontSize: Float = 13f,
    val terminalFontUri: String = "",
    val terminalFontName: String = "",
    val terminalTheme: TerminalThemeMode = TerminalThemeMode.TERMIUS,
    val keepScreenOn: Boolean = false,
    // 项目
    val defaultSort: SortMode = SortMode.DEFAULT,
    val confirmDelete: Boolean = true,
)

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

enum class TabWidthMode(val label: String, val size: Int) {
    TWO("2 个空格", 2), FOUR("4 个空格", 4), EIGHT("8 个空格", 8)
}

enum class AutoSaveMode(val label: String, val ms: Long) {
    S30("30 秒", 30_000L),
    MIN1("1 分钟", 60_000L),
    MIN3("3 分钟", 180_000L),
    MIN5("5 分钟", 300_000L),
}

enum class EncodingMode(val label: String) {
    AUTO("自动检测"), UTF8("UTF-8"), GBK("GBK / GB18030"), UTF16("UTF-16")
}

enum class TerminalThemeMode(
    val label: String,
    val bg: String,
    val fg: String
) {
    TERMIUS("Termius 深蓝", "#111625", "#e2e8f0"),
    DARK("纯黑", "#000000", "#e2e8f0"),
    GRAY("深灰", "#1e1e1e", "#d4d4d4"),
}

enum class SortMode(val label: String) {
    DEFAULT("默认"), NAME_ASC("名称 A→Z"), NAME_DESC("名称 Z→A"), TYPE("按类型")
}
