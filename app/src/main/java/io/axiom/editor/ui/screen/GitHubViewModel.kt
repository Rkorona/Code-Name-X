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
import io.axiom.editor.data.GitHubOAuthBus
import io.axiom.editor.data.GitHubOAuthService
import io.axiom.editor.data.GitHubRepoScanner
import io.axiom.editor.data.GitHubStore
import io.axiom.editor.data.ProjectRepository
import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.CommitRecord
import io.axiom.editor.ui.model.FileChangeStatus
import io.axiom.editor.ui.model.LocalRepo
import io.axiom.editor.ui.model.Project
import io.axiom.editor.ui.model.ProjectType
import io.axiom.editor.ui.model.RemoteRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    init {
        val saved = store.load()
        isLoggedIn    = saved.isLoggedIn
        userName      = saved.username
        userAvatarUrl = saved.avatarUrl.ifEmpty { null }
        accessToken   = saved.accessToken

        viewModelScope.launch(Dispatchers.IO) {
            val scanned = GitHubRepoScanner.scan(application)
            withContext(Dispatchers.Main) { localRepos = scanned }
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

    // ── 展开卡片 ──────────────────────────────────────────────────────
    var expandedRepoName by mutableStateOf<String?>(null)
        private set

    var expandedTabIndex by mutableStateOf(0)
        private set

    // ── 本地仓库（从文件系统扫描）────────────────────────────────────
    var localRepos by mutableStateOf<List<LocalRepo>>(emptyList())
        private set

    var changedFiles by mutableStateOf(
        mapOf(
            "codemirror6" to listOf(
                ChangedFile("src/extensions/search.ts", FileChangeStatus.MODIFIED),
                ChangedFile("src/view/editorView.ts", FileChangeStatus.MODIFIED)
            ),
            "axiom-editor" to listOf(
                ChangedFile(
                    "app/src/main/java/io/axiom/editor/ui/screen/GitHubScreen.kt",
                    FileChangeStatus.ADDED, isStaged = true
                ),
                ChangedFile(
                    "app/src/main/java/io/axiom/editor/ui/screen/GitHubViewModel.kt",
                    FileChangeStatus.ADDED, isStaged = true
                ),
                ChangedFile("README.md", FileChangeStatus.MODIFIED, isStaged = false)
            ),
            "kotlin-compiler" to emptyList()
        )
    )
        private set

    var commitHistory by mutableStateOf(
        mapOf(
            "codemirror6" to listOf(
                CommitRecord("a3f12bc", "Fix selection rendering bug", "YangHuaYong", "2 小时前"),
                CommitRecord("9d2e4f1", "Add search extension API", "YangHuaYong", "昨天"),
                CommitRecord("5c81a30", "Refactor viewport management", "contributor", "3 天前")
            ),
            "axiom-editor" to listOf(
                CommitRecord("f7b3c92", "feat: GitHub integration screen", "YangHuaYong", "刚刚", isPushed = false),
                CommitRecord("2a9d1e4", "refactor: theme system overhaul", "YangHuaYong", "1 天前"),
                CommitRecord("8f4b730", "fix: editor crash on large files", "YangHuaYong", "3 天前")
            ),
            "kotlin-compiler" to listOf(
                CommitRecord("1b4d8e2", "Update to Kotlin 2.1.0", "JetBrains", "1 周前"),
                CommitRecord("c9f2a57", "Performance improvements", "JetBrains", "2 周前")
            )
        )
    )
        private set

    // ── 云端仓库 ──────────────────────────────────────────────────────
    var remoteRepos by mutableStateOf<List<RemoteRepo>>(emptyList())
        private set

    var searchQuery by mutableStateOf("")
        private set

    // ── OAuth 登录 ────────────────────────────────────────────────────

    fun startOAuthFlow(context: Context) {
        val authUrl = GitHubOAuthService.buildAuthUrl()
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, authUrl.toUri())
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

    // ── 克隆仓库 ──────────────────────────────────────────────────────

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
            val destDir = File(externalDir, "projects/${repo.name}")

            if (destDir.exists() && File(destDir, ".git").exists()) {
                cloneMessage = "'${repo.name}' 已存在于本地"
                cloneIsError = false
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
                    ) { progress ->
                        cloneProgress = progress
                    }
                    b to s
                }

                withContext(Dispatchers.IO) {
                    setupGitDir(destDir, repo, branch, sha)
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

    // ── 仓库操作 ──────────────────────────────────────────────────────

    fun toggleRepoExpansion(repoName: String) {
        expandedRepoName = if (expandedRepoName == repoName) null else repoName
        expandedTabIndex = 0
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

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }
}
