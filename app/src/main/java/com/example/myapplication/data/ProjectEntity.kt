package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val type: String,
    val lastModified: String,
    val iconColorLong: Long,
    val isActive: Boolean,
    val localPath: String?
)
