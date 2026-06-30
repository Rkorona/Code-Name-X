package io.axiom.editor.data

import android.content.Context
import io.axiom.editor.ui.model.LocalRepo
import java.io.File

object GitHubRepoScanner {

    fun scan(context: Context): List<LocalRepo> {
        val roots = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "projects") },
            File(context.filesDir, "projects")
        )
        return roots
            .filter { it.exists() && it.isDirectory }
            .flatMap { root ->
                (root.listFiles() ?: emptyArray())
                    .filter { it.isDirectory && File(it, ".git").exists() }
                    .map { dir -> toLocalRepo(dir) }
            }
            .distinctBy { it.name }
    }

    fun isCloned(context: Context, repoName: String): Boolean {
        val roots = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "projects") },
            File(context.filesDir, "projects")
        )
        return roots.any { root ->
            val dir = File(root, repoName)
            dir.exists() && File(dir, ".git").exists()
        }
    }

    private fun toLocalRepo(projectDir: File): LocalRepo {
        val gitDir = File(projectDir, ".git")
        val branch = readBranch(gitDir)
        val unpushed = detectUnpushed(gitDir, branch)
        return LocalRepo(
            name = projectDir.name,
            branch = branch,
            unpushedCommits = unpushed
        )
    }

    private fun readBranch(gitDir: File): String {
        return try {
            val head = File(gitDir, "HEAD").readText().trim()
            if (head.startsWith("ref: refs/heads/"))
                head.removePrefix("ref: refs/heads/")
            else
                head.take(7).ifEmpty { "unknown" }
        } catch (_: Exception) { "unknown" }
    }

    private fun detectUnpushed(gitDir: File, branch: String): Int {
        val localHash = readRefFile(gitDir, "refs/heads/$branch") ?: return 0
        val remoteHash = readRefFile(gitDir, "refs/remotes/origin/$branch")
            ?: readPackedRef(gitDir, "refs/remotes/origin/$branch")
            ?: return 1
        return if (localHash == remoteHash) 0 else 1
    }

    private fun readRefFile(gitDir: File, ref: String): String? {
        val file = File(gitDir, ref)
        return if (file.exists()) file.readText().trim().ifEmpty { null } else null
    }

    private fun readPackedRef(gitDir: File, ref: String): String? {
        val packedRefs = File(gitDir, "packed-refs")
        if (!packedRefs.exists()) return null
        return packedRefs.readLines()
            .firstOrNull { !it.startsWith('#') && it.contains(ref) }
            ?.split(' ')?.firstOrNull()?.trim()
    }
}
