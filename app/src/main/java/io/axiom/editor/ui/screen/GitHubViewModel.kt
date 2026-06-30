package io.axiom.editor.ui.screen

import android.app.Application
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.axiom.editor.data.GitHubFileChangeScanner
import io.axiom.editor.data.GitHubOAuthBus
import io.axiom.editor.data.GitHubOAuthService
import io.axiom.editor.data.GitHubRepoScanner
import io.axiom.editor.data.GitHubStore
import io.axiom.editor.data.ProjectRepository
import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.CommitRecord
import io.axiom.editor.ui.model.LocalRepo
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectType
import io.axiom.editor.ui.model.RemoteRepo
import android.os.FileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class GitHubLoginState { Idle, Loading, Error }

class GitHubViewModel(application: Application) : AndroidViewModel(application) {

    private val store = GitHubStore(application)
    private var accessToken: String = ""

    // ── 登录状态 ──────────────────────────────────────────────────────
    var loginState by mutableStateOf(GitHubLoginState.Idle)
        private set

    var loginError by mutableStateOf("")
        private set

    var isLoggedIn by mutableStateOf(false)
        private set

    var userName by mutableStateOf("")
        private set

    var userAvatarUrl by mutableStateOf<String?>(null)
        private set

    // ── 仓库加载状态 ──────────────────────────────────────────────────
    var reposLoading by mutableStateOf(false)
        private set

    // ── 克隆状态 ──────────────────────────────────────────────────────
    var cloningRepoName by mutableStateOf<String?>(null)
        private set

    var cloneProgress by mutableFloatStateOf(0f)
        private set

    var cloneMessage by mutableStateOf<String?>(null)
        private set

    var cloneIsError by mutableStateOf(false)
        private set

    // ── 展开卡片 ──────────────────────────────────────────────────────
    var expandedRepoName by mutableStateOf<String?>(null)
        private set

    var expandedTabIndex by mutableStateOf(0)
        private set

    // ── 实时文件监听（FileObserver + debounce）────────────────────────
    private var fileObserver: FileObserver? = null
    private var debounceJob: Job? = null

    // ── 本地仓库 ──────────────────────────────────────────────────────
    var localRepos by mutableStateOf<List<LocalRepo>>(emptyList())
        private set

    var changedFiles by mutableStateOf<Map<String, List<ChangedFile>>>(emptyMap())
        private set

    var commitHistory by mutableStateOf<Map<String, List<CommitRecord>>>(emptyMap())
        private set

    // ── 云端仓库 ──────────────────────────────────────────────────────
    var remoteRepos by mutableStateOf<List<RemoteRepo>>(emptyList())
        private set

    var searchQuery by mutableStateOf("")
        private set

    // ── 每个仓库的独立操作状态 ────────────────────────────────────────
    /** repoName → 当前操作类型 ("fetch"/"pull"/"commit"/"push") */
    var repoOperationState by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** repoName → (消息文本, isError) */
    var repoOperationMessage by mutableStateOf<Map<String, Pair<String, Boolean>>>(emptyMap())
        private set

    // ═══════════════════════════════════════════════════════════════════
    // Init
    // ═══════════════════════════════════════════════════════════════════

    init {
        val saved = store.load()
        isLoggedIn    = saved.isLoggedIn
        userName      = saved.username
        userAvatarUrl = saved.avatarUrl.ifEmpty { null }
        accessToken   = saved.accessToken

        viewModelScope.launch(Dispatchers.IO) {
            val scanned = GitHubRepoScanner.scan(getApplication())
            withContext(Dispatchers.Main) { localRepos = scanned }
            refreshAllChangedFiles(scanned)
        }

        if (isLoggedIn && accessToken.isNotEmpty()) {
            fetchUserRepos()
        }

        viewModelScope.launch {
            GitHubOAuthBus.codeFlow.collect { code ->
                exchangeCodeAndLogin(code)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 操作状态辅助
    // ═══════════════════════════════════════════════════════════════════

    private fun setRepoOp(repoName: String, op: String?) {
        repoOperationState = if (op == null)
            repoOperationState - repoName
        else
            repoOperationState + (repoName to op)
    }

    private fun setRepoMsg(repoName: String, msg: String, isError: Boolean) {
        repoOperationMessage = repoOperationMessage + (repoName to (msg to isError))
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            repoOperationMessage = repoOperationMessage - repoName
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OAuth 登录
    // ═══════════════════════════════════════════════════════════════════

    fun startOAuthFlow(context: Context) {
        val authUrl = GitHubOAuthService.buildAuthUrl()
        CustomTabsIntent.Builder().build().launchUrl(context, authUrl.toUri())
    }

    private fun exchangeCodeAndLogin(code: String) {
        viewModelScope.launch {
            loginState = GitHubLoginState.Loading
            loginError = ""
            try {
                val (token, userInfo) = withContext(Dispatchers.IO) {
                    val t = GitHubOAuthService.exchangeCodeForToken(code)
                    val u = GitHubOAuthService.getUserInfo(t)
                    t to u
                }
                accessToken   = token
                isLoggedIn    = true
                userName      = userInfo.login
                userAvatarUrl = userInfo.avatarUrl
                store.save(userInfo.login, userInfo.avatarUrl, token)
                loginState    = GitHubLoginState.Idle
                fetchUserRepos()
            } catch (e: Exception) {
                loginState = GitHubLoginState.Error
                loginError = e.message ?: "登录失败，请重试"
            }
        }
    }

    private fun fetchUserRepos() {
        val token = accessToken
        if (token.isEmpty()) return
        viewModelScope.launch {
            reposLoading = true
            try {
                val repos = withContext(Dispatchers.IO) {
                    GitHubOAuthService.getUserRepos(token)
                }
                remoteRepos = repos
            } catch (_: Exception) {
            } finally {
                reposLoading = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 文件变更扫描
    // ═══════════════════════════════════════════════════════════════════

    private fun findProjectDir(repoName: String): File? {
        val ctx: Application = getApplication()
        val ext = ctx.getExternalFilesDir(null)?.let { File(it, "projects/$repoName") }
        if (ext?.exists() == true) return ext
        val int = File(ctx.filesDir, "projects/$repoName")
        if (int.exists()) return int
        return null
    }

    private fun readRemoteFullName(repoDir: File): String? {
        val config = File(repoDir, ".git/config")
        if (!config.exists()) return null
        return config.readLines()
            .firstOrNull { it.trim().startsWith("url = https://github.com/") }
            ?.trim()
            ?.removePrefix("url = https://github.com/")
            ?.removeSuffix(".git")
    }

    private suspend fun refreshAllChangedFiles(repos: List<LocalRepo>) {
        val result = mutableMapOf<String, List<ChangedFile>>()
        for (repo in repos) {
            val dir = findProjectDir(repo.name) ?: continue
            result[repo.name] = GitHubFileChangeScanner.scanChanges(dir)
        }
        withContext(Dispatchers.Main) {
            changedFiles = result
            // 同步更新 uncommittedChanges 计数
            localRepos = localRepos.map { repo ->
                repo.copy(uncommittedChanges = result[repo.name]?.size ?: 0)
            }
        }
    }

    fun refreshChangedFilesForRepo(repoName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = findProjectDir(repoName) ?: return@launch
            val changes = GitHubFileChangeScanner.scanChanges(dir)
            withContext(Dispatchers.Main) {
                changedFiles = changedFiles + (repoName to changes)
                localRepos = localRepos.map { repo ->
                    if (repo.name == repoName)
                        repo.copy(uncommittedChanges = changes.size)
                    else repo
                }
            }
        }
    }

    private fun loadCommitHistoryForRepo(repoName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = findProjectDir(repoName) ?: return@launch
            val history = GitHubFileChangeScanner.readCommits(dir)
            withContext(Dispatchers.Main) {
                commitHistory = commitHistory + (repoName to history)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fetch
    // ═══════════════════════════════════════════════════════════════════

    fun fetchRepo(repoName: String) {
        val token = accessToken
        if (token.isEmpty()) return
        viewModelScope.launch {
            setRepoOp(repoName, "fetch")
            try {
                val dir = withContext(Dispatchers.IO) { findProjectDir(repoName) }
                if (dir == null) {
                    setRepoMsg(repoName, "找不到本地仓库目录", true)
                    return@launch
                }
                val fullName = withContext(Dispatchers.IO) { readRemoteFullName(dir) }
                if (fullName == null) {
                    setRepoMsg(repoName, "无法读取远端仓库地址", true)
                    return@launch
                }
                val branch = localRepos.find { it.name == repoName }?.branch ?: "main"

                val sha = withContext(Dispatchers.IO) {
                    GitHubOAuthService.getLatestCommitSha(token, fullName, branch)
                }
                withContext(Dispatchers.IO) {
                    val refFile = File(dir, ".git/refs/remotes/origin/$branch")
                    refFile.parentFile?.mkdirs()
                    refFile.writeText("$sha\n")
                }

                val scanned = withContext(Dispatchers.IO) {
                    GitHubRepoScanner.scan(getApplication())
                }
                localRepos = scanned
                setRepoMsg(repoName, "Fetch 完成，已获取最新远端状态", false)
            } catch (e: Exception) {
                setRepoMsg(repoName, "Fetch 失败：${e.message ?: "网络错误"}", true)
            } finally {
                setRepoOp(repoName, null)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pull
    // ═══════════════════════════════════════════════════════════════════

    fun pullRepo(repoName: String, context: Context) {
        val token = accessToken
        if (token.isEmpty()) return
        viewModelScope.launch {
            setRepoOp(repoName, "pull")
            try {
                val dir = withContext(Dispatchers.IO) { findProjectDir(repoName) }
                if (dir == null) {
                    setRepoMsg(repoName, "找不到本地仓库目录", true)
                    return@launch
                }
                val fullName = withContext(Dispatchers.IO) { readRemoteFullName(dir) }
                if (fullName == null) {
                    setRepoMsg(repoName, "无法读取远端仓库地址", true)
                    return@launch
                }
                val branch = localRepos.find { it.name == repoName }?.branch ?: "main"

                withContext(Dispatchers.IO) {
                    // 先下载到临时目录，成功后才替换 —— 防止下载失败导致工作区被清空
                    val tempDir = File(
                        dir.parentFile,
                        "${dir.name}__pull_${System.currentTimeMillis()}"
                    )
                    tempDir.mkdirs()
                    try {
                        val s = GitHubOAuthService.downloadAndExtractRepo(
                            token    = token,
                            fullName = fullName,
                            branch   = branch,
                            destDir  = tempDir
                        ) {}

                        // 下载+解压成功后，才删除原工作区文件并移入新文件
                        dir.listFiles()?.forEach { f ->
                            if (f.name != ".git") f.deleteRecursively()
                        }
                        tempDir.listFiles()?.forEach { f ->
                            f.renameTo(File(dir, f.name))
                        }

                        // 更新双侧引用
                        if (s.length == 40) {
                            val headRef   = File(dir, ".git/refs/heads/$branch")
                            val remoteRef = File(dir, ".git/refs/remotes/origin/$branch")
                            headRef.parentFile?.mkdirs()
                            remoteRef.parentFile?.mkdirs()
                            headRef.writeText("$s\n")
                            remoteRef.writeText("$s\n")
                        }
                        // 重置两侧快照（干净基线）
                        GitHubFileChangeScanner.writeIndex(dir)
                    } finally {
                        tempDir.deleteRecursively()
                    }
                }

                val scanned = withContext(Dispatchers.IO) { GitHubRepoScanner.scan(context) }
                localRepos  = scanned
                changedFiles = changedFiles + (repoName to emptyList())
                commitHistory = commitHistory - repoName
                setRepoMsg(repoName, "Pull 完成，代码已更新到最新版本", false)
            } catch (e: Exception) {
                setRepoMsg(repoName, "Pull 失败：${e.message ?: "网络错误"}", true)
            } finally {
                setRepoOp(repoName, null)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 本地 Commit
    // ═══════════════════════════════════════════════════════════════════

    fun commitChanges(repoName: String, message: String) {
        if (message.isBlank()) {
            setRepoMsg(repoName, "提交信息不能为空", true)
            return
        }
        val stagedFiles = changedFiles[repoName]?.filter { it.isStaged } ?: emptyList()
        if (stagedFiles.isEmpty()) {
            setRepoMsg(repoName, "请先选择要提交的文件", true)
            return
        }
        viewModelScope.launch {
            setRepoOp(repoName, "commit")
            try {
                val dir = withContext(Dispatchers.IO) { findProjectDir(repoName) }
                if (dir == null) {
                    setRepoMsg(repoName, "找不到本地仓库目录", true)
                    return@launch
                }
                val branch = localRepos.find { it.name == repoName }?.branch ?: "main"

                withContext(Dispatchers.IO) {
                    val sha = generateLocalSha(repoName, branch, userName, message)
                    // 更新本地引用（与远端不同 → 显示"未推送"）
                    val headRef = File(dir, ".git/refs/heads/$branch")
                    headRef.parentFile?.mkdirs()
                    headRef.writeText("$sha\n")
                    // 仅更新已暂存文件的快照
                    GitHubFileChangeScanner.updateIndexForStagedFiles(
                        dir, stagedFiles.map { it.path }.toSet()
                    )
                    // 写入提交日志
                    GitHubFileChangeScanner.appendCommit(dir, sha, userName, message)
                }

                // 刷新状态
                val scanned = withContext(Dispatchers.IO) {
                    GitHubRepoScanner.scan(getApplication())
                }
                localRepos = scanned
                refreshChangedFilesForRepo(repoName)
                val history = withContext(Dispatchers.IO) {
                    GitHubFileChangeScanner.readCommits(dir)
                }
                commitHistory = commitHistory + (repoName to history)
                setRepoMsg(repoName, "本地提交成功 ✓", false)
            } catch (e: Exception) {
                setRepoMsg(repoName, "提交失败：${e.message ?: "未知错误"}", true)
            } finally {
                setRepoOp(repoName, null)
            }
        }
    }

    private fun generateLocalSha(vararg parts: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("${parts.joinToString("")}${System.currentTimeMillis()}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Push
    // ═══════════════════════════════════════════════════════════════════

    fun pushRepo(repoName: String, pushMessage: String = "") {
        val token = accessToken
        if (token.isEmpty()) return
        viewModelScope.launch {
            setRepoOp(repoName, "push")
            try {
                val dir = withContext(Dispatchers.IO) { findProjectDir(repoName) }
                if (dir == null) {
                    setRepoMsg(repoName, "找不到本地仓库目录", true)
                    return@launch
                }
                val fullName = withContext(Dispatchers.IO) { readRemoteFullName(dir) }
                if (fullName == null) {
                    setRepoMsg(repoName, "无法读取远端仓库地址", true)
                    return@launch
                }
                val branch = localRepos.find { it.name == repoName }?.branch ?: "main"

                // 对比已提交快照 vs 远端快照，只推送通过 Commit 提交的内容
                val filesToPush = withContext(Dispatchers.IO) {
                    GitHubFileChangeScanner.scanCommittedVsRemote(dir)
                }
                if (filesToPush.isEmpty()) {
                    setRepoMsg(repoName, "没有需要推送的变更", false)
                    return@launch
                }

                // 确定提交信息
                val message = pushMessage.ifBlank {
                    val pending = withContext(Dispatchers.IO) {
                        GitHubFileChangeScanner.readCommits(dir)
                            .filter { !it.isPushed }
                            .map { it.shortMessage }
                    }
                    if (pending.isNotEmpty()) pending.joinToString("; ")
                    else "Update ${filesToPush.size} file(s)"
                }

                val newSha = withContext(Dispatchers.IO) {
                    GitHubOAuthService.pushToGitHub(
                        token       = token,
                        fullName    = fullName,
                        branch      = branch,
                        filesToPush = filesToPush,
                        message     = message,
                        projectDir  = dir
                    )
                }

                withContext(Dispatchers.IO) {
                    // 同步本地双侧引用（确保父目录存在，避免 ENOENT）
                    File(dir, ".git/refs/heads/$branch").apply { parentFile?.mkdirs() }.writeText("$newSha\n")
                    File(dir, ".git/refs/remotes/origin/$branch").apply { parentFile?.mkdirs() }.writeText("$newSha\n")
                    // 将已提交快照（AXIOM_INDEX）复制为新的远端基线（AXIOM_REMOTE_INDEX）
                    // 注意：不重建自工作区，这样未提交的工作区修改不会影响下次 Push 的基线
                    GitHubFileChangeScanner.syncRemoteIndexFromCommitted(dir)
                    // 标记所有本地提交为已推送
                    GitHubFileChangeScanner.markCommitsPushed(dir)
                }

                val scanned = withContext(Dispatchers.IO) {
                    GitHubRepoScanner.scan(getApplication())
                }
                localRepos = scanned
                val history = withContext(Dispatchers.IO) {
                    GitHubFileChangeScanner.readCommits(dir)
                }
                commitHistory = commitHistory + (repoName to history)
                // Push 后刷新变更文件（远端快照已更新，变更应清空）
                refreshChangedFilesForRepo(repoName)
                setRepoMsg(repoName, "Push 成功！${filesToPush.size} 个文件已上传 ✓", false)
            } catch (e: Exception) {
                setRepoMsg(repoName, "Push 失败：${e.message ?: "网络错误"}", true)
            } finally {
                setRepoOp(repoName, null)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 克隆仓库
    // ═══════════════════════════════════════════════════════════════════

    fun cloneRepo(repo: RemoteRepo, context: Context) {
        if (cloningRepoName != null) return
        val token = accessToken
        if (token.isEmpty()) return

        viewModelScope.launch {
            cloningRepoName = repo.fullName
            cloneProgress   = 0f
            cloneMessage    = null
            cloneIsError    = false

            val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
            val destDir     = File(externalDir, "projects/${repo.name}")

            if (destDir.exists() && File(destDir, ".git").exists()) {
                cloneMessage    = "'${repo.name}' 已存在于本地"
                cloneIsError    = false
                cloningRepoName = null
                return@launch
            }

            try {
                val (branch, sha) = withContext(Dispatchers.IO) {
                    val b = GitHubOAuthService.getDefaultBranch(token, repo.fullName)
                    destDir.mkdirs()
                    val s = GitHubOAuthService.downloadAndExtractRepo(
                        token    = token,
                        fullName = repo.fullName,
                        branch   = b,
                        destDir  = destDir
                    ) { progress -> cloneProgress = progress }
                    b to s
                }

                withContext(Dispatchers.IO) {
                    setupGitDir(destDir, repo, branch, sha)
                    GitHubFileChangeScanner.writeIndex(destDir)
                }

                ProjectRepository(context).addProject(
                    Project(
                        id           = System.currentTimeMillis().toString(),
                        name         = repo.name,
                        description  = repo.description,
                        type         = ProjectType.GITHUB,
                        lastModified = System.currentTimeMillis(),
                        isActive     = false,
                        localPath    = destDir.absolutePath
                    )
                )

                val scanned = withContext(Dispatchers.IO) { GitHubRepoScanner.scan(context) }
                localRepos   = scanned
                changedFiles = changedFiles + (repo.name to emptyList())
                cloneMessage = "✓ '${repo.name}' 克隆成功"
                cloneIsError = false
            } catch (e: Exception) {
                destDir.deleteRecursively()
                cloneMessage = "克隆失败：${e.message ?: "未知错误"}"
                cloneIsError = true
            } finally {
                cloningRepoName = null
                cloneProgress   = 0f
            }
        }
    }

    fun dismissCloneMessage() {
        cloneMessage = null
    }

    private fun setupGitDir(destDir: File, repo: RemoteRepo, branch: String, sha: String) {
        val gitDir = File(destDir, ".git")
        gitDir.mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/$branch\n")
        File(gitDir, "config").writeText("""
[core]
	repositoryformatversion = 0
	filemode = false
	bare = false
[remote "origin"]
	url = https://github.com/${repo.fullName}.git
	fetch = +refs/heads/*:refs/remotes/origin/*
[branch "$branch"]
	remote = origin
	merge = refs/heads/$branch
""".trimIndent())
        if (sha.length == 40) {
            File(gitDir, "refs/heads").mkdirs()
            File(gitDir, "refs/remotes/origin").mkdirs()
            File(gitDir, "refs/heads/$branch").writeText("$sha\n")
            File(gitDir, "refs/remotes/origin/$branch").writeText("$sha\n")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 登录辅助
    // ═══════════════════════════════════════════════════════════════════

    fun clearLoginError() {
        if (loginState == GitHubLoginState.Error) {
            loginState = GitHubLoginState.Idle
            loginError = ""
        }
    }

    fun logout() {
        isLoggedIn      = false
        userName        = ""
        userAvatarUrl   = null
        accessToken     = ""
        remoteRepos     = emptyList()
        cloningRepoName = null
        cloneMessage    = null
        loginState      = GitHubLoginState.Idle
        loginError      = ""
        store.clear()
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI 交互
    // ═══════════════════════════════════════════════════════════════════

    fun toggleRepoExpansion(repoName: String) {
        val wasExpanded  = expandedRepoName == repoName
        expandedRepoName = if (wasExpanded) null else repoName
        expandedTabIndex = 0
        if (!wasExpanded) {
            refreshChangedFilesForRepo(repoName)
            loadCommitHistoryForRepo(repoName)
            startWatching(repoName)
        } else {
            stopWatching()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 实时文件监听
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 启动对 [repoName] 本地目录的 inotify 监听。
     * minSdk 30 → 可直接使用 FileObserver(File, mask) 递归构造器。
     * 文件变化后 debounce 500ms 再触发扫描，避免编辑时每次按键都重扫。
     */
    private fun startWatching(repoName: String) {
        stopWatching()
        val dir = findProjectDir(repoName) ?: return
        val mask = FileObserver.CLOSE_WRITE or
                FileObserver.CREATE       or
                FileObserver.DELETE       or
                FileObserver.MOVED_FROM   or
                FileObserver.MOVED_TO
        fileObserver = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                // 忽略 .git 内部写入（commit/push 期间大量触发）
                if (path == null || path.startsWith(".git")) return
                debounceJob?.cancel()
                debounceJob = viewModelScope.launch {
                    delay(500)
                    refreshChangedFilesForRepo(repoName)
                }
            }
        }
        fileObserver?.startWatching()
    }

    private fun stopWatching() {
        fileObserver?.stopWatching()
        fileObserver = null
        debounceJob?.cancel()
        debounceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopWatching()
    }

    fun switchExpandedTab(index: Int) {
        expandedTabIndex = index
    }

    fun toggleFileStaged(repoName: String, filePath: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { f ->
            if (f.path == filePath) f.copy(isStaged = !f.isStaged) else f
        })
    }

    fun stageAll(repoName: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { it.copy(isStaged = true) })
    }

    fun unstageAll(repoName: String) {
        val files = changedFiles[repoName] ?: return
        changedFiles = changedFiles + (repoName to files.map { it.copy(isStaged = false) })
    }

    fun revertChanges(repoName: String) {
        val dir = findProjectDir(repoName) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            GitHubFileChangeScanner.resetIndex(dir)
            withContext(Dispatchers.Main) {
                refreshChangedFilesForRepo(repoName)
                setRepoMsg(repoName, "已还原到上次提交状态", false)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }
}
