package io.axiom.editor.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY rowid DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("UPDATE projects SET iconColorLong = :languageOrdinal WHERE id = :id")
    suspend fun updateProjectLanguage(id: String, languageOrdinal: Long)

    @Query("UPDATE projects SET lastModified = :lastModified WHERE id = :id")
    suspend fun updateLastModified(id: String, lastModified: String)
}
