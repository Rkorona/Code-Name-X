package io.axiom.editor.data

import io.axiom.editor.BuildConfig
import org.json.JSONObject
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

    fun buildAuthUrl(): String {
        val clientId = BuildConfig.GITHUB_CLIENT_ID
        val callbackUrl = BuildConfig.GITHUB_CALLBACK_URL
        val scope = "repo,user,read:org"
        return "https://github.com/login/oauth/authorize" +
            "?client_id=$clientId" +
            "&redirect_uri=${encode(callbackUrl)}" +
            "&scope=${encode(scope)}"
    }

    fun exchangeCodeForToken(code: String): String {
        val url = URL("https://github.com/login/oauth/access_token")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        val body = "client_id=${BuildConfig.GITHUB_CLIENT_ID}" +
            "&client_secret=${BuildConfig.GITHUB_CLIENT_SECRET}" +
            "&code=$code"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream.bufferedReader().readText()
        val json = JSONObject(response)

        if (json.has("error")) {
            throw Exception("OAuth error: ${json.optString("error_description", json.getString("error"))}")
        }
        return json.getString("access_token")
    }

    fun getUserInfo(token: String): UserInfo {
        val url = URL("https://api.github.com/user")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15000
            readTimeout = 15000
        }
        val responseCode = conn.responseCode
        if (responseCode != 200) {
            throw Exception("GitHub API returned $responseCode")
        }
        val response = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        return UserInfo(
            login = json.getString("login"),
            avatarUrl = json.getString("avatar_url"),
            name = if (json.isNull("name")) null else json.optString("name"),
            email = if (json.isNull("email")) null else json.optString("email")
        )
    }

    fun getDefaultBranch(token: String, fullName: String): String {
        val url = java.net.URL("https://api.github.com/repos/$fullName")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 10000
            readTimeout = 10000
        }
        val response = conn.inputStream.bufferedReader().readText()
        return org.json.JSONObject(response).optString("default_branch", "main")
    }

    fun downloadAndExtractRepo(
        token: String,
        fullName: String,
        branch: String,
        destDir: java.io.File,
        onProgress: (Float) -> Unit
    ): String {
        val url = java.net.URL("https://api.github.com/repos/$fullName/zipball/$branch")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
        }

        var sha = ""
        val contentDisposition = conn.getHeaderField("Content-Disposition") ?: ""
        val shaMatch = Regex("[a-f0-9]{40}").find(contentDisposition)
        if (shaMatch != null) sha = shaMatch.value

        val contentLength = conn.contentLengthLong
        val tempZip = java.io.File.createTempFile("repo_", ".zip")
        try {
            val inputStream = conn.inputStream
            val outputStream = tempZip.outputStream()
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                outputStream.write(buffer, 0, len)
                bytesRead += len
                if (contentLength > 0) {
                    onProgress((bytesRead.toFloat() / contentLength * 0.8f).coerceIn(0f, 0.8f))
                }
            }
            outputStream.close()
            inputStream.close()

            onProgress(0.85f)
            extractZip(tempZip, destDir)
            onProgress(1f)
        } finally {
            tempZip.delete()
        }
        return sha
    }

    private fun extractZip(zipFile: java.io.File, destDir: java.io.File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var firstPrefix: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (firstPrefix == null && name.contains('/')) {
                    firstPrefix = name.substringBefore('/') + "/"
                }
                val stripped = if (firstPrefix != null && name.startsWith(firstPrefix))
                    name.removePrefix(firstPrefix) else name
                if (stripped.isNotEmpty()) {
                    val outFile = java.io.File(destDir, stripped)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun getUserRepos(token: String): List<io.axiom.editor.ui.model.RemoteRepo> {
        val url = java.net.URL("https://api.github.com/user/repos?sort=updated&per_page=100&visibility=all")
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15000
            readTimeout = 15000
        }
        val responseCode = conn.responseCode
        if (responseCode != 200) throw Exception("GitHub API returned $responseCode")
        val response = conn.inputStream.bufferedReader().readText()
        val jsonArray = org.json.JSONArray(response)
        return (0 until jsonArray.length()).map { i ->
            val repo = jsonArray.getJSONObject(i)
            io.axiom.editor.ui.model.RemoteRepo(
                name        = repo.getString("name"),
                fullName    = repo.getString("full_name"),
                stars       = repo.getInt("stargazers_count"),
                language    = repo.optString("language", ""),
                description = repo.optString("description", ""),
                isPrivate   = repo.getBoolean("private")
            )
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
