package com.example.myapplication.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.example.myapplication.ui.model.Project
import com.example.myapplication.ui.model.ProjectType
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

    private fun ProjectEntity.toProject(): Project = Project(
        id = id,
        name = name,
        description = description,
        type = if (type == "GITHUB") ProjectType.GITHUB else ProjectType.LOCAL,
        lastModified = lastModified,
        iconColor = Color(iconColorLong),
        isActive = isActive,
        localPath = localPath
    )

    private fun Project.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        description = description,
        type = type.name,
        lastModified = lastModified,
        iconColorLong = iconColor.value.toLong(),
        isActive = isActive,
        localPath = localPath
    )
}
