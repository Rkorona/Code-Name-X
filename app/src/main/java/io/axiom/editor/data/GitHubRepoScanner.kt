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
        val unpushed = detectUnpushed(projectDir)
        val isRemoteAhead = detectRemoteAhead(gitDir, branch)
        return LocalRepo(
            name = projectDir.name,
            branch = branch,
            unpushedCommits = unpushed,
            isRemoteAhead = isRemoteAhead,
            commitsAhead = 0  // 由 ViewModel 通过 Compare API 异步填充
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

    /**
     * 统计本地真正未推送的提交数，通过读 AXIOM_COMMITS（状态列 = "pending"）实现。
     * 避免了 SHA 比较误报（SHA 不同可能是远端 ahead，不是本地 ahead）。
     */
    private fun detectUnpushed(projectDir: File): Int {
        val commitsFile = File(projectDir, ".git/AXIOM_COMMITS")
        if (commitsFile.exists()) {
            return commitsFile.readLines()
                .filter { it.isNotBlank() }
                .count { line ->
                    val parts = line.split('\t')
                    parts.size > 2 && parts[2] == "pending"
                }
        }
        // AXIOM_COMMITS 不存在时返回 0（未知 → 不误报）
        return 0
    }

    private fun detectRemoteAhead(gitDir: File, branch: String): Boolean {
        val remoteHash = readRefFile(gitDir, "refs/remotes/origin/$branch")
            ?: readPackedRef(gitDir, "refs/remotes/origin/$branch")
            ?: return false
        val localHash = readRefFile(gitDir, "refs/heads/$branch")
        // 如果本地 ref 文件不存在（旧版克隆未写入），只要远端 ref 存在就视为远端有更新
            ?: return true
        return remoteHash != localHash
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
