package io.axiom.editor.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeOption        = ThemeMode.entries.getOrElse(prefs.getInt("themeOption", 0)) { ThemeMode.SYSTEM },
        editorFontSize     = prefs.getFloat("editorFontSize", 14f),
        editorFontUri      = prefs.getString("editorFontUri", "") ?: "",
        editorFontName     = prefs.getString("editorFontName", "") ?: "",
        editorFontWeight   = FontWeightMode.entries.getOrElse(prefs.getInt("editorFontWeight", 1)) { FontWeightMode.NORMAL },
        autoComplete       = prefs.getBoolean("autoComplete", true),
        showLineNumbers    = prefs.getBoolean("showLineNumbers", true),
        wordWrap           = prefs.getBoolean("wordWrap", false),
        tabWidth           = TabWidthMode.entries.getOrElse(prefs.getInt("tabWidth", 1)) { TabWidthMode.FOUR },
        autoSave           = prefs.getBoolean("autoSave", false),
        autoSaveInterval   = AutoSaveMode.entries.find { it.name == prefs.getString("autoSaveInterval", "MIN1") } ?: AutoSaveMode.MIN1,
        fileEncoding       = EncodingMode.entries.getOrElse(prefs.getInt("fileEncoding", 0)) { EncodingMode.AUTO },
        enableFileTabs     = prefs.getBoolean("enableFileTabs", true),
        terminalFontSize   = prefs.getFloat("terminalFontSize", 13f),
        terminalFontUri    = prefs.getString("terminalFontUri", "") ?: "",
        terminalFontName   = prefs.getString("terminalFontName", "") ?: "",
        terminalTheme      = TerminalThemeMode.entries.getOrElse(prefs.getInt("terminalTheme", 0)) { TerminalThemeMode.TERMIUS },
        keepScreenOn       = prefs.getBoolean("keepScreenOn", false),
        defaultSort        = SortMode.entries.getOrElse(prefs.getInt("defaultSort", 0)) { SortMode.DEFAULT },
        confirmDelete      = prefs.getBoolean("confirmDelete", true),
    )

    fun save(s: AppSettings) {
        prefs.edit().apply {
            putInt("themeOption",        s.themeOption.ordinal)
            putFloat("editorFontSize",   s.editorFontSize)
            putString("editorFontUri",   s.editorFontUri)
            putString("editorFontName",  s.editorFontName)
            putInt("editorFontWeight",   s.editorFontWeight.ordinal)
            putBoolean("autoComplete",   s.autoComplete)
            putBoolean("showLineNumbers", s.showLineNumbers)
            putBoolean("wordWrap",       s.wordWrap)
            putInt("tabWidth",           s.tabWidth.ordinal)
            putBoolean("autoSave",       s.autoSave)
            putString("autoSaveInterval", s.autoSaveInterval.name)
            putInt("fileEncoding",       s.fileEncoding.ordinal)
            putBoolean("enableFileTabs", s.enableFileTabs)
            putFloat("terminalFontSize", s.terminalFontSize)
            putString("terminalFontUri",  s.terminalFontUri)
            putString("terminalFontName", s.terminalFontName)
            putInt("terminalTheme",      s.terminalTheme.ordinal)
            putBoolean("keepScreenOn",   s.keepScreenOn)
            putInt("defaultSort",        s.defaultSort.ordinal)
            putBoolean("confirmDelete",  s.confirmDelete)
        }.apply()
    }
}
