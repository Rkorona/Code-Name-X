package io.axiom.editor.data

import android.util.Base64
import io.axiom.editor.BuildConfig
import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.FileChangeStatus
import io.axiom.editor.ui.model.RemoteRepo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GitHubOAuthService {

    data class UserInfo(
        val login: String,
        val avatarUrl: String,
        val name: String?,
        val email: String?
    )

    data class RemoteCommitInfo(
        val sha: String,
        val message: String,
        val authorName: String,
        val authorDate: String
    )

    // ═══════════════════════════════════════════════════════════════════
    // OAuth
    // ═══════════════════════════════════════════════════════════════════

    fun buildAuthUrl(): String {
        val clientId    = BuildConfig.GITHUB_CLIENT_ID
        val callbackUrl = BuildConfig.GITHUB_CALLBACK_URL
        val scope       = "repo,user,read:org"
        return "https://github.com/login/oauth/authorize" +
            "?client_id=$clientId" +
            "&redirect_uri=${encode(callbackUrl)}" +
            "&scope=${encode(scope)}"
    }

    fun exchangeCodeForToken(code: String): String {
        val url  = URL("https://github.com/login/oauth/access_token")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput        = true
            connectTimeout  = 15000
            readTimeout     = 15000
        }
        val body = "client_id=${BuildConfig.GITHUB_CLIENT_ID}" +
            "&client_secret=${BuildConfig.GITHUB_CLIENT_SECRET}" +
            "&code=$code"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        val responseCode = conn.responseCode
        val stream       = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response     = stream.bufferedReader().readText()
        val json         = JSONObject(response)

        if (json.has("error")) {
            throw Exception("OAuth error: ${json.optString("error_description", json.getString("error"))}")
        }
        return json.getString("access_token")
    }

    fun getUserInfo(token: String): UserInfo {
        val conn = apiGet(token, "https://api.github.com/user")
        if (conn.responseCode != 200) throw Exception("GitHub API returned ${conn.responseCode}")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        return UserInfo(
            login     = json.getString("login"),
            avatarUrl = json.getString("avatar_url"),
            name      = if (json.isNull("name")) null else json.optString("name"),
            email     = if (json.isNull("email")) null else json.optString("email")
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 仓库
    // ═══════════════════════════════════════════════════════════════════

    fun getUserRepos(token: String): List<RemoteRepo> {
        val conn = apiGet(
            token,
            "https://api.github.com/user/repos?sort=updated&per_page=100&visibility=all"
        )
        if (conn.responseCode != 200) throw Exception("GitHub API returned ${conn.responseCode}")
        val jsonArray = JSONArray(conn.inputStream.bufferedReader().readText())
        return (0 until jsonArray.length()).map { i ->
            val repo = jsonArray.getJSONObject(i)
            RemoteRepo(
                name        = repo.getString("name"),
                fullName    = repo.getString("full_name"),
                stars       = repo.getInt("stargazers_count"),
                language    = repo.optString("language", ""),
                description = repo.optString("description", ""),
                isPrivate   = repo.getBoolean("private")
            )
        }
    }

    fun getDefaultBranch(token: String, fullName: String): String {
        val conn = apiGet(token, "https://api.github.com/repos/$fullName")
        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response).optString("default_branch", "main")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fetch：获取远端最新 commit SHA
    // ═══════════════════════════════════════════════════════════════════

    fun getLatestCommitSha(token: String, fullName: String, branch: String): String {
        val conn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/git/refs/heads/${encode(branch)}"
        )
        val code = conn.responseCode
        if (code != 200) throw Exception("无法获取远端引用 ($code)")
        val raw = conn.inputStream.bufferedReader().readText()
        // GitHub 有时返回数组（多 ref 匹配），有时返回单个对象
        return try {
            JSONObject(raw).getJSONObject("object").getString("sha")
        } catch (_: Exception) {
            JSONArray(raw).getJSONObject(0).getJSONObject("object").getString("sha")
        }
    }

    /**
     * 用 GitHub Compare API 查询远端比本地多几个提交（ahead_by）。
     * 若 SHA 相同或请求失败返回 0。
     */
    fun getCommitsAheadCount(
        token: String,
        fullName: String,
        localSha: String,
        remoteSha: String
    ): Int {
        if (localSha == remoteSha || localSha.isEmpty()) return 0
        val conn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/compare/$localSha...$remoteSha"
        )
        if (conn.responseCode != 200) return 0
        return try {
            JSONObject(conn.inputStream.bufferedReader().readText()).optInt("ahead_by", 0)
        } catch (_: Exception) { 0 }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 提交历史（远端）
    // ═══════════════════════════════════════════════════════════════════

    fun getRecentCommits(
        token: String,
        fullName: String,
        branch: String,
        perPage: Int = 20
    ): List<RemoteCommitInfo> {
        val conn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/commits?sha=${encode(branch)}&per_page=$perPage"
        )
        if (conn.responseCode != 200) return emptyList()
        val array = JSONArray(conn.inputStream.bufferedReader().readText())
        return (0 until array.length()).mapNotNull { i ->
            try {
                val obj    = array.getJSONObject(i)
                val commit = obj.getJSONObject("commit")
                RemoteCommitInfo(
                    sha        = obj.getString("sha"),
                    message    = commit.getString("message").lines().firstOrNull() ?: "",
                    authorName = commit.getJSONObject("author").optString("name", "Unknown"),
                    authorDate = commit.getJSONObject("author").optString("date", "")
                )
            } catch (_: Exception) { null }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Clone
    // ═══════════════════════════════════════════════════════════════════

    fun downloadAndExtractRepo(
        token: String,
        fullName: String,
        branch: String,
        destDir: File,
        onProgress: (Float) -> Unit
    ): String {
        val conn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/zipball/$branch",
            readTimeout = 120000
        )
        conn.instanceFollowRedirects = true

        var sha = ""
        val contentDisposition = conn.getHeaderField("Content-Disposition") ?: ""
        val shaMatch = Regex("[a-f0-9]{40}").find(contentDisposition)
        if (shaMatch != null) sha = shaMatch.value

        val contentLength = conn.contentLengthLong
        val tempZip = File.createTempFile("repo_", ".zip")
        try {
            val inputStream  = conn.inputStream
            val outputStream = tempZip.outputStream()
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
                bytesRead += len
                if (contentLength > 0)
                    onProgress((bytesRead.toFloat() / contentLength * 0.8f).coerceIn(0f, 0.8f))
            }
            outputStream.close(); inputStream.close()
            onProgress(0.85f)
            extractZip(tempZip, destDir)
            onProgress(1f)
        } finally {
            tempZip.delete()
        }
        return sha
    }

    private fun extractZip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var firstPrefix: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (firstPrefix == null && name.contains('/'))
                    firstPrefix = name.substringBefore('/') + "/"
                val stripped = if (firstPrefix != null && name.startsWith(firstPrefix))
                    name.removePrefix(firstPrefix) else name
                if (stripped.isNotEmpty()) {
                    val outFile = File(destDir, stripped)
                    if (entry.isDirectory) outFile.mkdirs()
                    else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 远端文件内容（冲突检测用）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 获取远端单个文件的原始字节（通过 Contents API，最大约 1 MB）。
     * 文件不存在或请求失败返回 null。
     */
    fun downloadRemoteFileBytes(
        token: String,
        fullName: String,
        filePath: String,
        branch: String
    ): ByteArray? {
        // path 中的每段单独编码，保留 '/' 分隔符
        val encodedPath = filePath.split("/").joinToString("/") { encode(it) }
        val conn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/contents/$encodedPath?ref=${encode(branch)}"
        )
        if (conn.responseCode != 200) return null
        return try {
            val json     = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
            val content  = json.optString("content", "").replace("\n", "")
            val encoding = json.optString("encoding", "base64")
            if (encoding == "base64") Base64.decode(content, Base64.DEFAULT)
            else content.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Push（GitHub Trees API 原子推送）
    // ═══════════════════════════════════════════════════════════════════

    fun pushToGitHub(
        token: String,
        fullName: String,
        branch: String,
        filesToPush: List<ChangedFile>,
        message: String,
        projectDir: File
    ): String {
        if (filesToPush.isEmpty()) throw Exception("没有需要推送的文件变更")

        // 1. 获取当前远端 commit SHA
        val currentSha = getLatestCommitSha(token, fullName, branch)

        // 2. 获取当前树 SHA
        val commitConn = apiGet(
            token,
            "https://api.github.com/repos/$fullName/git/commits/$currentSha"
        )
        if (commitConn.responseCode != 200) throw Exception("无法获取提交信息")
        val treeSha = JSONObject(commitConn.inputStream.bufferedReader().readText())
            .getJSONObject("tree").getString("sha")

        // 3. 为每个文件创建 blob（删除的文件 sha=null）
        val treeEntries = JSONArray()
        for (file in filesToPush) {
            val entry = JSONObject()
            entry.put("path", file.path)
            entry.put("mode", "100644")
            entry.put("type", "blob")

            if (file.status == FileChangeStatus.DELETED) {
                entry.put("sha", JSONObject.NULL)
            } else {
                val localFile = File(projectDir, file.path)
                if (!localFile.exists()) continue

                val blobConn = apiPost(
                    token,
                    "https://api.github.com/repos/$fullName/git/blobs"
                )
                val blobBody = JSONObject()
                if (isLikelyText(localFile)) {
                    blobBody.put("content", localFile.readText())
                    blobBody.put("encoding", "utf-8")
                } else {
                    blobBody.put("content",
                        Base64.encodeToString(localFile.readBytes(), Base64.NO_WRAP))
                    blobBody.put("encoding", "base64")
                }
                OutputStreamWriter(blobConn.outputStream).use { it.write(blobBody.toString()) }
                val blobCode = blobConn.responseCode
                if (blobCode !in 200..201) throw Exception("创建 blob 失败 ($blobCode)")
                entry.put("sha",
                    JSONObject(blobConn.inputStream.bufferedReader().readText()).getString("sha"))
            }
            treeEntries.put(entry)
        }

        // 4. 创建新树
        val newTreeConn = apiPost(token, "https://api.github.com/repos/$fullName/git/trees")
        OutputStreamWriter(newTreeConn.outputStream).use {
            it.write(JSONObject().apply {
                put("base_tree", treeSha)
                put("tree", treeEntries)
            }.toString())
        }
        if (newTreeConn.responseCode !in 200..201)
            throw Exception("创建树失败 (${newTreeConn.responseCode})")
        val newTreeSha = JSONObject(
            newTreeConn.inputStream.bufferedReader().readText()
        ).getString("sha")

        // 5. 创建提交
        val newCommitConn = apiPost(token, "https://api.github.com/repos/$fullName/git/commits")
        OutputStreamWriter(newCommitConn.outputStream).use {
            it.write(JSONObject().apply {
                put("message", message)
                put("tree", newTreeSha)
                put("parents", JSONArray().put(currentSha))
            }.toString())
        }
        if (newCommitConn.responseCode !in 200..201)
            throw Exception("创建提交失败 (${newCommitConn.responseCode})")
        val newCommitSha = JSONObject(
            newCommitConn.inputStream.bufferedReader().readText()
        ).getString("sha")

        // 6. 更新分支 ref
        val refUrl  = URL("https://api.github.com/repos/$fullName/git/refs/heads/$branch")
        val refConn = refUrl.openConnection() as HttpURLConnection
        refConn.apply {
            requestMethod = "PATCH"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
            doOutput       = true
            connectTimeout = 15000
            readTimeout    = 30000
        }
        OutputStreamWriter(refConn.outputStream).use {
            it.write(JSONObject().apply { put("sha", newCommitSha) }.toString())
        }
        if (refConn.responseCode !in 200..201)
            throw Exception("更新分支引用失败 (${refConn.responseCode})")

        return newCommitSha
    }

    // ═══════════════════════════════════════════════════════════════════
    // 分支列表
    // ═══════════════════════════════════════════════════════════════════

    fun getBranches(token: String, fullName: String): List<String> {
        val conn = apiGet(token, "https://api.github.com/repos/$fullName/branches?per_page=100")
        if (conn.responseCode != 200) return emptyList()
        return try {
            val array = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until array.length()).map { i -> array.getJSONObject(i).getString("name") }
        } catch (_: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HTTP 工具
    // ═══════════════════════════════════════════════════════════════════

    private fun apiGet(
        token: String,
        urlStr: String,
        connectTimeout: Int = 15000,
        readTimeout: Int = 30000
    ): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Cache-Control", "no-cache")
            this.connectTimeout = connectTimeout
            this.readTimeout    = readTimeout
            instanceFollowRedirects = true
            useCaches = false
        }
        return conn
    }

    private fun apiPost(
        token: String,
        urlStr: String
    ): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json")
            doOutput       = true
            connectTimeout = 15000
            readTimeout    = 60000
        }
        return conn
    }

    private fun isLikelyText(file: File): Boolean {
        val binary = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp",
            "pdf", "zip", "jar", "apk", "so", "dylib", "dll", "exe",
            "mp3", "mp4", "wav", "ogg", "ttf", "otf", "woff", "woff2",
            "class", "dex", "bin"
        )
        return file.extension.lowercase() !in binary
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
