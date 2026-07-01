package io.axiom.editor.data

data class AppSettings(
    val themeOption: ThemeMode = ThemeMode.SYSTEM,
    // 编辑器
    val editorFontSize: Float = 14f,
    val editorFontUri: String = "",
    val editorFontName: String = "",
    val editorFontWeight: FontWeightMode = FontWeightMode.NORMAL,
    val autoComplete: Boolean = true,
    val showLineNumbers: Boolean = true,
    val wordWrap: Boolean = false,
    val editorTheme: EditorThemeMode = EditorThemeMode.AUTO,
    val tabWidth: TabWidthMode = TabWidthMode.FOUR,
    val autoSave: Boolean = false,
    val autoSaveInterval: AutoSaveMode = AutoSaveMode.MIN1,
    val fileEncoding: EncodingMode = EncodingMode.AUTO,
    val enableFileTabs: Boolean = true,
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
    S5("5 秒", 5_000L),
    S15("15 秒", 15_000L),
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

enum class FontWeightMode(val label: String, val cssValue: Int) {
    LIGHT("细", 300),
    NORMAL("默认", 400),
    BOLD("粗", 600),
    MEDIUM("中粗", 500),
    EXTRABOLD("特粗", 700),
}

/**
 * 编辑器配色主题。
 * [id] 必须与 codemirror6/main.js 中 THEME_MAP 的 key 完全一致，
 * 修改需同步更新两端。
 */
enum class EditorThemeMode(val id: String, val label: String) {
    AUTO("auto", "跟随应用主题"),
    ONE_DARK("oneDark", "One Dark"),
    ABCDEF("abcdef", "Abcdef"),
    ABYSS("abyss", "Abyss"),
    ANDROIDSTUDIO("androidstudio", "Android Studio"),
    ANDROMEDA("andromeda", "Andromeda"),
    ATOMONE("atomone", "Atom One"),
    AURA("aura", "Aura"),
    BASIC_LIGHT("basicLight", "Basic Light"),
    BASIC_DARK("basicDark", "Basic Dark"),
    BBEDIT("bbedit", "BBEdit"),
    BESPIN("bespin", "Bespin"),
    CONSOLE_LIGHT("consoleLight", "Console Light"),
    CONSOLE_DARK("consoleDark", "Console Dark"),
    COPILOT("copilot", "Copilot"),
    DARCULA("darcula", "Darcula"),
    DRACULA("dracula", "Dracula"),
    DUOTONE_LIGHT("duotoneLight", "Duotone Light"),
    DUOTONE_DARK("duotoneDark", "Duotone Dark"),
    ECLIPSE("eclipse", "Eclipse"),
    GITHUB_LIGHT("githubLight", "GitHub Light"),
    GITHUB_DARK("githubDark", "GitHub Dark"),
    GRUVBOX_DARK("gruvboxDark", "Gruvbox Dark"),
    GRUVBOX_LIGHT("gruvboxLight", "Gruvbox Light"),
    KIMBIE("kimbie", "Kimbie"),
    MATERIAL_LIGHT("materialLight", "Material Light"),
    MATERIAL_DARK("materialDark", "Material Dark"),
    MONOKAI("monokai", "Monokai"),
    MONOKAI_DIMMED("monokaiDimmed", "Monokai Dimmed"),
    NOCTIS_LILAC("noctisLilac", "Noctis Lilac"),
    NORD("nord", "Nord"),
    OKAIDIA("okaidia", "Okaidia"),
    QUIETLIGHT("quietlight", "Quiet Light"),
    RED("red", "Red"),
    SOLARIZED_LIGHT("solarizedLight", "Solarized Light"),
    SOLARIZED_DARK("solarizedDark", "Solarized Dark"),
    SUBLIME("sublime", "Sublime"),
    TOKYO_NIGHT("tokyoNight", "Tokyo Night"),
    TOKYO_NIGHT_STORM("tokyoNightStorm", "Tokyo Night Storm"),
    TOKYO_NIGHT_DAY("tokyoNightDay", "Tokyo Night Day"),
    TOMORROW_NIGHT_BLUE("tomorrowNightBlue", "Tomorrow Night Blue"),
    VSCODE_LIGHT("vscodeLight", "VS Code Light"),
    VSCODE_DARK("vscodeDark", "VS Code Dark"),
    WHITE_LIGHT("whiteLight", "White Light"),
    WHITE_DARK("whiteDark", "White Dark"),
    XCODE_LIGHT("xcodeLight", "Xcode Light"),
    XCODE_DARK("xcodeDark", "Xcode Dark"),
}
