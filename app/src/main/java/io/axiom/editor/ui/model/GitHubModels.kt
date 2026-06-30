package io.axiom.editor.ui.model

data class LocalRepo(
    val name: String,
    val branch: String,
    val uncommittedChanges: Int = 0,
    val unpushedCommits: Int = 0,
    val isRemoteAhead: Boolean = false,
    /** 远端比本地多的提交数（0 = 未知 / 未查询） */
    val commitsAhead: Int = 0
)

data class RemoteRepo(
    val name: String,
    val fullName: String,
    val stars: Int,
    val language: String,
    val description: String = "",
    val isPrivate: Boolean = false
)

enum class FileChangeStatus(val label: String) {
    MODIFIED("M"),
    ADDED("A"),
    DELETED("D"),
    RENAMED("R"),
    UNTRACKED("?")
}

data class ChangedFile(
    val path: String,
    val status: FileChangeStatus,
    val isStaged: Boolean = false
)

data class CommitRecord(
    val hash: String,
    val shortMessage: String,
    val author: String,
    val timeAgo: String,
    val isPushed: Boolean = true
)
