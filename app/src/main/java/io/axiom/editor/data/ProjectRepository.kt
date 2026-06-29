package io.axiom.editor.data

import android.content.Context
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectLanguage
import io.axiom.editor.ui.model.ProjectType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepository(context: Context) {

    private val dao = ProjectDatabase.getInstance(context).projectDao()

    val projects: Flow<List<Project>> = dao.getAllProjects().map { entities ->
        entities.map { it.toProject() }
    }

    suspend fun addProject(project: Project) {
        dao.insertProject(project.toEntity())
    }

    suspend fun deleteProject(id: String) {
        dao.deleteProjectById(id)
    }

    suspend fun updateProjectLanguage(id: String, language: io.axiom.editor.ui.model.ProjectLanguage) {
        dao.updateProjectLanguage(id, language.ordinal.toLong())
    }

    suspend fun updateLastModified(id: String) {
        dao.updateLastModified(id, System.currentTimeMillis().toString())
    }

    private fun ProjectEntity.toProject(): Project = Project(
        id = id,
        name = name,
        description = description,
        type = if (type == "GITHUB") ProjectType.GITHUB else ProjectType.LOCAL,
        // 旧数据库存的是字符串（如"刚刚"），无法解析时退回当前时间
        lastModified = lastModified.toLongOrNull() ?: System.currentTimeMillis(),
        isActive = isActive,
        localPath = localPath,
        // iconColorLong 列已复用：存语言枚举的 ordinal，超出范围则退回 UNKNOWN
        language = ProjectLanguage.entries.getOrElse(iconColorLong.toInt()) {
            ProjectLanguage.UNKNOWN
        },
    )

    private fun Project.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        description = description,
        type = type.name,
        lastModified = lastModified.toString(),
        iconColorLong = language.ordinal.toLong(),   // 复用列存语言类型
        isActive = isActive,
        localPath = localPath
    )
}
