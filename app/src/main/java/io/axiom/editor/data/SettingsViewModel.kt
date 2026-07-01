package io.axiom.editor.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)

    var settings by mutableStateOf(store.load())
        private set

    fun update(new: AppSettings) {
        settings = new
        store.save(new)
    }

    fun setThemeOption(v: ThemeMode)         = update(settings.copy(themeOption = v))
    fun setEditorFontSize(v: Float)          = update(settings.copy(editorFontSize = v))
    fun setEditorFont(uri: String, name: String) = update(settings.copy(editorFontUri = uri, editorFontName = name))
    fun clearEditorFont()                    = update(settings.copy(editorFontUri = "", editorFontName = ""))
    fun setAutoComplete(v: Boolean)          = update(settings.copy(autoComplete = v))
    fun setShowLineNumbers(v: Boolean)       = update(settings.copy(showLineNumbers = v))
    fun setWordWrap(v: Boolean)              = update(settings.copy(wordWrap = v))
    fun setEditorTheme(v: EditorThemeMode)   = update(settings.copy(editorTheme = v))
    fun setTabWidth(v: TabWidthMode)         = update(settings.copy(tabWidth = v))
    fun setAutoSave(v: Boolean)              = update(settings.copy(autoSave = v))
    fun setAutoSaveInterval(v: AutoSaveMode) = update(settings.copy(autoSaveInterval = v))
    fun setFileEncoding(v: EncodingMode)     = update(settings.copy(fileEncoding = v))
    fun setEnableFileTabs(v: Boolean)        = update(settings.copy(enableFileTabs = v))
    fun setEditorFontWeight(v: FontWeightMode) = update(settings.copy(editorFontWeight = v))
    fun setTerminalFontSize(v: Float)        = update(settings.copy(terminalFontSize = v))
    fun setTerminalFont(uri: String, name: String) = update(settings.copy(terminalFontUri = uri, terminalFontName = name))
    fun clearTerminalFont()                  = update(settings.copy(terminalFontUri = "", terminalFontName = ""))
    fun setTerminalTheme(v: TerminalThemeMode) = update(settings.copy(terminalTheme = v))
    fun setKeepScreenOn(v: Boolean)          = update(settings.copy(keepScreenOn = v))
    fun setDefaultSort(v: SortMode)          = update(settings.copy(defaultSort = v))
    fun setConfirmDelete(v: Boolean)         = update(settings.copy(confirmDelete = v))
}
