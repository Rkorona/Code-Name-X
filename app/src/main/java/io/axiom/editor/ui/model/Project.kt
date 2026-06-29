package io.axiom.editor.ui.model

enum class ProjectType {
    LOCAL, GITHUB
}

data class Project(
    val id: String,
    val name: String,
    val description: String,
    val type: ProjectType,
    val lastModified: Long = System.currentTimeMillis(),
    val isActive: Boolean,
    val localPath: String? = null,
    val language: ProjectLanguage = ProjectLanguage.UNKNOWN,
)
