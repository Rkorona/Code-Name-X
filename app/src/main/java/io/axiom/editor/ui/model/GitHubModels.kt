package io.axiom.editor.ui.model

data class LocalRepo(
    val name: String,
    val branch: String,
    val uncommittedChanges: Int = 0,
    val unpushedCommits: Int = 0
)

data class RemoteRepo(
    val name: String,
    val stars: Int,
    val language: String,
    val description: String = ""
)
