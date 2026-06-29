package io.axiom.editor.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.axiom.editor.ui.model.LocalRepo
import io.axiom.editor.ui.model.RemoteRepo

class GitHubViewModel : ViewModel() {

    var isLoggedIn by mutableStateOf(false)
        private set

    var userAvatarUrl by mutableStateOf<String?>(null)
        private set

    var expandedRepoName by mutableStateOf<String?>(null)
        private set

    var localRepos by mutableStateOf(
        listOf(
            LocalRepo("codemirror6", "main", uncommittedChanges = 2),
            LocalRepo("axiom-editor", "feature/git-integration", unpushedCommits = 1),
            LocalRepo("kotlin-compiler", "develop")
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
