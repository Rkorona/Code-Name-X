package io.axiom.editor.data

import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.CommitRecord
import io.axiom.editor.ui.model.FileChangeStatus
import java.io.File
import java.security.MessageDigest

// ═══════════════════════════════════════════════════════════════════
// Diff 数据结构（冲突查看器使用）
// ═══════════════════════════════════════════════════════════════════

enum class DiffType { ADDED, REMOVED, CONTEXT, SEPARATOR }

/**
 * 单行 diff 记录。
 * @param type      行类型
 * @param lineNum   行号（ADDED=本地行号，REMOVED=远端行号，CONTEXT/SEPARATOR=null）
 * @param text      行文本内容
 */
data class DiffLine(val type: DiffType, val lineNum: Int?, val text: String)

object GitHubFileChangeScanner {

    private const val INDEX_FILE        = "AXIOM_INDEX"
    private const val REMOTE_INDEX_FILE = "AXIOM_REMOTE_INDEX"
    private const val COMMITS_FILE      = "AXIOM_COMMITS"

    // ═══════════════════════════════════════════════════════════════════
    // 快照写入
    // ═══════════════════════════════════════════════════════════════════

    /** 克隆完成后调用：同时初始化本地快照与远端快照 */
    fun writeIndex(projectDir: File) {
        val gitDir = File(projectDir, ".git")
        if (!gitDir.exists()) return
        val text = buildIndexText(projectDir)
        File(gitDir, INDEX_FILE).writeText(text)
        File(gitDir, REMOTE_INDEX_FILE).writeText(text)
    }

    /** Push 成功后调用：更新远端快照（作为下次 Push 的基线） */
    fun writeRemoteIndex(projectDir: File) {
        val gitDir = File(projectDir, ".git")
        if (!gitDir.exists()) return
        File(gitDir, REMOTE_INDEX_FILE).writeText(buildIndexText(projectDir))
    }

    /** 本地 Commit 后调用：仅更新已暂存文件的哈希，非暂存文件保持"脏"状态 */
    fun updateIndexForStagedFiles(projectDir: File, stagedPaths: Set<String>) {
        val gitDir = File(projectDir, ".git")
        if (!gitDir.exists()) return
        val indexFile = File(gitDir, INDEX_FILE)

        val existing: MutableMap<String, String> = if (indexFile.exists()) {
            readIndexMap(indexFile).toMutableMap()
        } else mutableMapOf()

        for (path in stagedPaths) {
            val file = File(projectDir, path)
            if (file.exists()) existing[path] = md5(file)
            else existing.remove(path)
        }

        indexFile.writeText(indexMapToText(existing))
    }

    /** 还原后重置快照 */
    fun resetIndex(projectDir: File) = writeIndex(projectDir)

    /** 放弃单个文件时从 AXIOM_INDEX 中移除该路径（用于 ADDED 文件被删除后） */
    fun removeFromIndex(projectDir: File, filePath: String) {
        val indexFile = File(projectDir, ".git/$INDEX_FILE")
        if (!indexFile.exists()) return
        val existing = readIndexMap(indexFile).toMutableMap()
        existing.remove(filePath)
        indexFile.writeText(indexMapToText(existing))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 变更扫描
    // ═══════════════════════════════════════════════════════════════════

    /** 与本地快照（AXIOM_INDEX）对比 → 展示未提交变更 */
    fun scanChanges(projectDir: File): List<ChangedFile> {
        val gitDir    = File(projectDir, ".git")
        val indexFile = File(gitDir, INDEX_FILE)

        if (!indexFile.exists()) {
            return walkFiles(projectDir)
                .take(200)
                .map { ChangedFile(it.relativeTo(projectDir).path, FileChangeStatus.UNTRACKED) }
                .toList()
        }

        return diffIndexVsCurrent(readIndexMap(indexFile), projectDir)
    }

    /**
     * 对比已提交快照（AXIOM_INDEX）与远端快照（AXIOM_REMOTE_INDEX）。
     * 仅返回通过 [commitChanges] 提交过的变更，不包含未暂存 / 未提交的工作区修改。
     * Push 时应使用此函数，确保只推送用户明确提交的内容。
     */
    fun scanCommittedVsRemote(projectDir: File): List<ChangedFile> {
        val gitDir       = File(projectDir, ".git")
        val remoteFile   = File(gitDir, REMOTE_INDEX_FILE)
        val indexFile    = File(gitDir, INDEX_FILE)

        val remoteIndex    = if (remoteFile.exists()) readIndexMap(remoteFile) else emptyMap()
        val committedIndex = if (indexFile.exists()) readIndexMap(indexFile) else emptyMap()

        val changes = mutableListOf<ChangedFile>()
        for ((path, _) in remoteIndex) {
            if (!committedIndex.containsKey(path))
                changes.add(ChangedFile(path, FileChangeStatus.DELETED))
        }
        for ((path, hash) in committedIndex) {
            when (remoteIndex[path]) {
                null -> changes.add(ChangedFile(path, FileChangeStatus.ADDED))
                hash -> { /* 无变化 */ }
                else -> changes.add(ChangedFile(path, FileChangeStatus.MODIFIED))
            }
        }
        return changes
    }

    /** Push 成功后：将 AXIOM_INDEX（已提交状态）同步为新的远端基线 */
    fun syncRemoteIndexFromCommitted(projectDir: File) {
        val gitDir    = File(projectDir, ".git")
        val indexFile = File(gitDir, INDEX_FILE)
        val remoteFile = File(gitDir, REMOTE_INDEX_FILE)
        if (indexFile.exists()) indexFile.copyTo(remoteFile, overwrite = true)
    }

    /** @deprecated 使用 [scanCommittedVsRemote]（Push）或 [scanChanges]（变更列表显示） */
    fun scanChangesVsRemote(projectDir: File): List<ChangedFile> {
        val remoteFile = File(projectDir, ".git/$REMOTE_INDEX_FILE")
        val baseline   = if (remoteFile.exists()) readIndexMap(remoteFile) else emptyMap()
        return diffIndexVsCurrent(baseline, projectDir)
    }

    private fun diffIndexVsCurrent(
        indexed: Map<String, String>,
        projectDir: File
    ): List<ChangedFile> {
        val current = walkFiles(projectDir)
            .associate { it.relativeTo(projectDir).path to md5(it) }

        val changes = mutableListOf<ChangedFile>()
        for ((path, _) in indexed) {
            if (!current.containsKey(path))
                changes.add(ChangedFile(path, FileChangeStatus.DELETED))
        }
        for ((path, hash) in current) {
            when (val old = indexed[path]) {
                null  -> changes.add(ChangedFile(path, FileChangeStatus.ADDED))
                hash  -> { /* 无变化 */ }
                else  -> changes.add(ChangedFile(path, FileChangeStatus.MODIFIED))
            }
        }
        return changes.sortedWith(compareBy({ it.status.ordinal }, { it.path }))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 提交日志
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 向本地提交日志追加一条记录。
     * 格式（\t 分隔）：sha \t timestamp \t isPushed \t author \t message
     */
    fun appendCommit(projectDir: File, sha: String, author: String, message: String) {
        val gitDir = File(projectDir, ".git")
        if (!gitDir.exists()) return
        val timestamp = System.currentTimeMillis()
        val safeMsgLine = message.replace("\t", " ").replace("\n", "↵")
        File(gitDir, COMMITS_FILE).appendText(
            "$sha\t$timestamp\tpending\t$author\t$safeMsgLine\n"
        )
    }

    /** Push 成功后：将全部 pending 记录标为 pushed */
    fun markCommitsPushed(projectDir: File) {
        val file = File(projectDir, ".git/$COMMITS_FILE")
        if (!file.exists()) return
        val updated = file.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("\t", limit = 5).toMutableList()
            while (parts.size < 5) parts.add("")
            parts[2] = "pushed"
            parts.joinToString("\t")
        }
        file.writeText(updated.joinToString("\n") + "\n")
    }

    /** 读取提交日志，最新在前
     * @param limit 最多返回多少条（默认全部）
     * @param skip  跳过前多少条（用于分页）
     */
    fun readCommits(projectDir: File, limit: Int = Int.MAX_VALUE, skip: Int = 0): List<CommitRecord> {
        val file = File(projectDir, ".git/$COMMITS_FILE")
        if (!file.exists()) return emptyList()
        val now = System.currentTimeMillis()
        return file.readLines()
            .filter { it.isNotBlank() }
            .reversed()
            .drop(skip)
            .take(limit)
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 5)
                if (parts.size < 5) return@mapNotNull null
                val sha       = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                val isPushed  = parts[2] == "pushed"
                val author    = parts[3]
                val message   = parts[4].replace("↵", "\n")
                CommitRecord(
                    hash         = sha,
                    shortMessage = message.lines().firstOrNull()?.take(80) ?: message,
                    author       = author,
                    timeAgo      = formatTimeAgo(now - timestamp),
                    isPushed     = isPushed
                )
            }
    }

    /**
     * Pull / Clone 后：将远端提交历史覆盖写入 AXIOM_COMMITS。
     * 每条格式：sha\ttimestampMs\tpushed\tauthor\tmessage
     * rawLines 中的每个元素已按此格式组装好，顺序为 oldest-first。
     */
    fun writeRawCommitLines(projectDir: File, rawLines: List<String>) {
        val gitDir = File(projectDir, ".git")
        if (!gitDir.exists()) return
        val content = rawLines.filter { it.isNotBlank() }.joinToString("\n")
        File(gitDir, COMMITS_FILE).writeText(if (content.isNotEmpty()) "$content\n" else "")
    }

    fun hasPendingCommits(projectDir: File): Boolean {
        val file = File(projectDir, ".git/$COMMITS_FILE")
        if (!file.exists()) return false
        return file.readLines().filter { it.isNotBlank() }
            .any { it.split("\t").getOrNull(2) != "pushed" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具函数
    // ═══════════════════════════════════════════════════════════════════

    private fun buildIndexText(projectDir: File): String {
        val sb = StringBuilder()
        walkFiles(projectDir).forEach { f ->
            sb.append("${md5(f)} ${f.relativeTo(projectDir).path}\n")
        }
        return sb.toString()
    }

    private fun readIndexMap(file: File): Map<String, String> =
        file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val idx = line.indexOf(' ')
            if (idx < 0) null else line.substring(idx + 1) to line.substring(0, idx)
        }.toMap()

    private fun indexMapToText(map: Map<String, String>): String {
        val sb = StringBuilder()
        map.forEach { (path, hash) -> sb.append("$hash $path\n") }
        return sb.toString()
    }

    private fun walkFiles(projectDir: File): Sequence<File> {
        val gitDir = File(projectDir, ".git")
        return projectDir.walkTopDown()
            .onEnter { it != gitDir }
            .filter { it.isFile }
    }

    /** 读取远端基线快照（AXIOM_REMOTE_INDEX）的路径→MD5映射 */
    fun readRemoteIndexMap(projectDir: File): Map<String, String> {
        val file = File(projectDir, ".git/$REMOTE_INDEX_FILE")
        return if (file.exists()) readIndexMap(file) else emptyMap()
    }

    /** 计算字节数组的 MD5（供冲突检测对比远端内容） */
    fun md5OfBytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 逐行 Diff 计算（LCS 算法，用于冲突查看器）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 计算从 [remoteLines]（远端）到 [localLines]（本地）的 diff。
     * - ADDED   = 仅本地有（我们新增 / 远端没有）→绿色
     * - REMOVED = 仅远端有（强制推送后会消失）→红色
     * - CONTEXT = 双方相同
     * 文件行数超过 600 时跳过计算（避免卡顿），返回空列表。
     */
    fun computeDiff(
        remoteLines: List<String>,
        localLines: List<String>,
        contextSize: Int = 3
    ): List<DiffLine> {
        val n = remoteLines.size
        val m = localLines.size
        if (n > 2000 || m > 2000) return emptyList()

        // LCS DP
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (remoteLines[i - 1] == localLines[j - 1]) dp[i - 1][j - 1] + 1
                            else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }

        // 回溯构建原始 diff 序列（逆序 → addFirst）
        val raw = ArrayDeque<DiffLine>()
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && remoteLines[i - 1] == localLines[j - 1] -> {
                    raw.addFirst(DiffLine(DiffType.CONTEXT, i, remoteLines[i - 1]))
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    raw.addFirst(DiffLine(DiffType.ADDED, j, localLines[j - 1]))
                    j--
                }
                else -> {
                    raw.addFirst(DiffLine(DiffType.REMOVED, i, remoteLines[i - 1]))
                    i--
                }
            }
        }

        return collapseContext(raw.toList(), contextSize)
    }

    /** 将连续的 CONTEXT 行折叠，只保留变更前后各 [ctx] 行，其余替换为 SEPARATOR。 */
    private fun collapseContext(lines: List<DiffLine>, ctx: Int): List<DiffLine> {
        val size = lines.size
        val keep = BooleanArray(size)
        lines.forEachIndexed { idx, line ->
            if (line.type != DiffType.CONTEXT) {
                for (k in maxOf(0, idx - ctx)..minOf(size - 1, idx + ctx)) keep[k] = true
            }
        }
        val result  = mutableListOf<DiffLine>()
        var skipping = false
        for (idx in 0 until size) {
            if (keep[idx]) {
                skipping = false
                result.add(lines[idx])
            } else if (!skipping) {
                skipping = true
                result.add(DiffLine(DiffType.SEPARATOR, null, "···"))
            }
        }
        return result
    }

    private fun md5(file: File): String = try {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "error" }

    fun formatTimeAgo(diffMs: Long): String {
        val s = diffMs / 1000; val m = s / 60; val h = m / 60; val d = h / 24
        return when {
            s < 60   -> "刚刚"
            m < 60   -> "${m}分钟前"
            h < 24   -> "${h}小时前"
            d < 30   -> "${d}天前"
            else     -> "${d / 30}个月前"
        }
    }
}
