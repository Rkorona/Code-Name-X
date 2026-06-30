package io.axiom.editor.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.CommitRecord
import io.axiom.editor.ui.model.FileChangeStatus
import io.axiom.editor.ui.model.LocalRepo
import io.axiom.editor.ui.model.RemoteRepo

class GitHubViewModel : ViewModel() {

    var isLoggedIn by mutableStateOf(false)
        private set

    var userAvatarUrl by mutableStateOf<String?>(null)
        private set

    var expandedRepoName by mutableStateOf<String?>(null)
        private set

    var expandedTabIndex by mutableStateOf(0)
        private set

    var localRepos by mutableStateOf(
        listOf(
            LocalRepo("codemirror6", "main", uncommittedChanges = 2),
            LocalRepo("axiom-editor", "feature/git-integration", unpushedCommits = 1),
            LocalRepo("kotlin-compiler", "develop")
        )
    )
        private set

    var changedFiles by mutableStateOf(
        mapOf(
            "codemirror6" to listOf(
                ChangedFile("src/extensions/search.ts", FileChangeStatus.MODIFIED),
                ChangedFile("src/view/editorView.ts", FileChangeStatus.MODIFIED)
            ),
            "axiom-editor" to listOf(
                ChangedFile("app/src/main/java/io/axiom/editor/ui/screen/GitHubScreen.kt", FileChangeStatus.ADDED, isStaged = true),
                ChangedFile("app/src/main/java/io/axiom/editor/ui/screen/GitHubViewModel.kt", FileChangeStatus.ADDED, isStaged = true),
                ChangedFile("README.md", FileChangeStatus.MODIFIED, isStaged = false)
            ),
            "kotlin-compiler" to emptyList()
        )
    )
        private set

    var commitHistory by mutableStateOf(
        mapOf(
            "codemirror6" to listOf(
                CommitRecord("a3f12bc", "Fix selection rendering bug", "YangHuaYong", "2 小时前", isPushed = true),
                CommitRecord("9d2e4f1", "Add search extension API", "YangHuaYong", "昨天", isPushed = true),
                CommitRecord("5c81a30", "Refactor viewport management", "contributor", "3 天前", isPushed = true)
            ),
            "axiom-editor" to listOf(
                CommitRecord("f7b3c92", "feat: GitHub integration screen", "YangHuaYong", "刚刚", isPushed = false),
                CommitRecord("2a9d1e4", "refactor: theme system overhaul", "YangHuaYong", "1 天前", isPushed = true),
                CommitRecord("8f4b730", "fix: editor crash on large files", "YangHuaYong", "3 天前", isPushed = true)
            ),
            "kotlin-compiler" to listOf(
                CommitRecord("1b4d8e2", "Update to Kotlin 2.1.0", "JetBrains", "1 周前", isPushed = true),
                CommitRecord("c9f2a57", "Performance improvements", "JetBrains", "2 周前", isPushed = true)
            )
        )
    )
        private set

    var remoteRepos by mutableStateOf(
        listOf(
            RemoteRepo("Free-SS-NODES", 142, "Python"),
            RemoteRepo("awesome-kotlin", 12400, "Kotlin"),
            RemoteRepo("JetBrains/kotlin", 48000, "Kotlin"),
            RemoteRepo("flutter/flutter", 168000, "Dart")
        )
    )
        private set

    var searchQuery by mutableStateOf("")
        private set

    fun toggleRepoExpansion(repoName: String) {
        expandedRepoName = if (expandedRepoName == repoName) null else repoName
        expandedTabIndex = 0
    }

    fun switchExpandedTab(index: Int) {
        expandedTabIndex = index
    }

    fun toggleFileStaged(repoName: String, filePath: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { f ->
            if (f.path == filePath) f.copy(isStaged = !f.isStaged) else f
        })
    }

    fun stageAll(repoName: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { it.copy(isStaged = true) })
    }

    fun unstageAll(repoName: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { it.copy(isStaged = false) })
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun login(username: String, token: String) {
        isLoggedIn = true
        userAvatarUrl = "https://github.com/identicons/${username.lowercase()}.png"
    }

    fun logout() {
        isLoggedIn = false
        userAvatarUrl = null
    }
}
