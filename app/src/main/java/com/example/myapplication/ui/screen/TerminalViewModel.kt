package com.example.myapplication.ui.screen

import android.app.Application
import android.os.Build
import android.system.Os
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    // ── 持久化终端输出行（Tab 切换后保留）──
    val terminalLines = mutableStateListOf<String>()

    // ── 持久化命令历史（Tab 切换后保留）──
    val commandHistory = mutableStateListOf<String>()

    // ── 环境状态 ──
    var envState by mutableStateOf(EnvironmentState.Checking)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    var currentStatusMessage by mutableStateOf("正在等待指令…")
        private set

    // ── Shell 进程（私有，外部通过方法交互）──
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null

    // ── 防止重复启动 Shell ──
    private var shellStarted = false

    // ── 路径 ──
    private val context get() = getApplication<Application>()
    val rootfsDir = File(context.filesDir, "debian_rootfs")
    private val tarXzFile = File(context.cacheDir, "rootfs.tar.xz")
    val imageUrl =
        "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260627_14%3A22/rootfs.tar.xz"

    init {
        checkEnvironment()
    }

    // ─────────────────────────────────────────────
    // 环境检测：仅在 ViewModel 初始化时运行一次
    // ─────────────────────────────────────────────
    private fun checkEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = isDebianInstalled(rootfsDir)
            withContext(Dispatchers.Main) {
                if (installed) {
                    if (terminalLines.isEmpty()) {
                        terminalLines.add("Welcome to Debian GNU/Linux 13 (trixie) via PRoot!")
                        terminalLines.add("Type 'help' or explore directories using real Shell.")
                        terminalLines.add("Debian rootfs detected successfully.")
                        terminalLines.add("")
                    }
                    envState = EnvironmentState.Ready
                    startShellIfNeeded()
                } else {
                    envState = EnvironmentState.NotInstalled
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 仅在首次（或手动重置后）启动 Shell，Tab 切换不会触发
    // ─────────────────────────────────────────────
    fun startShellIfNeeded() {
        if (shellStarted && shellProcess?.isAlive == true) return
        shellStarted = true
        shellProcess?.destroy()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = File(context.applicationInfo.nativeLibraryDir)
                val possibleNames =
                    listOf("libproot.so", "libproot-userland.so", "libproot_real.so")
                var prootFile =
                    possibleNames.map { File(nativeDir, it) }.firstOrNull { it.exists() }

                if (prootFile == null) {
                    prootFile = nativeDir.listFiles()?.firstOrNull {
                        it.name.contains("proot", ignoreCase = true) &&
                                !it.name.contains("loader")
                    }
                }

                // 确保有执行权限
                if (prootFile != null && prootFile.exists() && !prootFile.canExecute()) {
                    try {
                        prootFile.setExecutable(true, false)
                    } catch (e: Exception) {
                        Runtime.getRuntime()
                            .exec(arrayOf("chmod", "755", prootFile.absolutePath)).waitFor()
                    }
                }

                val pb = if (prootFile != null && prootFile.exists()) {
                    // ─────────────────────────────────────────────
                    // Debian trixie UsrMerge：/bin /lib /sbin 都是指向 usr/* 的符号链接。
                    // 用 PRoot 的 -b host_path:guest_path 直接将真实目录挂载到 guest 的
                    // 传统路径，完全绕过符号链接解析，保证 ELF 动态链接器可以被找到。
                    // ─────────────────────────────────────────────
                    val shellPath = listOf("/usr/bin/bash", "/bin/bash", "/usr/bin/sh", "/bin/sh")
                        .firstOrNull { path ->
                            val f = File(rootfsDir, path.removePrefix("/"))
                            f.exists() && !f.isDirectory
                        } ?: "/bin/sh"

                    val cmd = buildList {
                        add(prootFile.absolutePath)
                        add("--link2symlink")
                        add("-r"); add(rootfsDir.absolutePath)
                        add("-0")
                        add("-w"); add("/root")
                        // 标准设备节点绑定
                        add("-b"); add("/dev")
                        add("-b"); add("/proc")
                        add("-b"); add("/sys")
                        add("-b"); add("/dev/urandom")
                        // ── UsrMerge 关键修复：将 rootfs 内的真实 usr/* 目录
                        //    直接绑定到 guest 的 /bin /lib /sbin /lib64，
                        //    确保 ELF interpreter (ld-linux-aarch64.so.1) 可被找到 ──
                        val usrBin  = File(rootfsDir, "usr/bin")
                        val usrLib  = File(rootfsDir, "usr/lib")
                        val usrSbin = File(rootfsDir, "usr/sbin")
                        if (usrBin.isDirectory)  { add("-b"); add("${usrBin.absolutePath}:/bin") }
                        if (usrLib.isDirectory)  { add("-b"); add("${usrLib.absolutePath}:/lib") }
                        if (usrLib.isDirectory)  { add("-b"); add("${usrLib.absolutePath}:/lib64") }
                        if (usrSbin.isDirectory) { add("-b"); add("${usrSbin.absolutePath}:/sbin") }
                        add(shellPath)
                    }
                    ProcessBuilder(cmd)
                } else {
                    withContext(Dispatchers.Main) {
                        terminalLines.add(
                            "⚠️ 警告: 未在应用原生路径中找到 PRoot 翻译桥接程序 (libproot.so)，" +
                                    "已自动安全降级运行 Android 宿主 sh 外壳。" +
                                    "在此降级环境下，apt 等 Debian glibc 二进制文件将无法被执行。"
                        )
                    }
                    ProcessBuilder("/system/bin/sh").directory(rootfsDir)
                }

                pb.redirectErrorStream(true)
                val env = pb.environment()
                env["PATH"] =
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
                env["HOME"] = "/root"
                env["TERM"] = "xterm-256color"
                env["LANG"] = "C.UTF-8"
                env["USER"] = "root"

                val process = pb.start()
                shellProcess = process
                shellWriter = process.outputStream.bufferedWriter()

                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    val l = line
                    withContext(Dispatchers.Main) { terminalLines.add(l) }
                    line = reader.readLine()
                }

                // 进程退出后重置标志，允许下次手动重启
                withContext(Dispatchers.Main) { shellStarted = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    terminalLines.add("❌ 启动终端进程失败: ${e.localizedMessage}")
                    shellStarted = false
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 强制重新启动一个全新的 Shell（Ctrl+C 使用）
    // ─────────────────────────────────────────────
    fun restartShell() {
        shellStarted = false
        startShellIfNeeded()
    }

    // ─────────────────────────────────────────────
    // 向 Shell 发送命令
    // ─────────────────────────────────────────────
    fun sendCommand(cmd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellWriter?.let { w ->
                    w.write(cmd + "\n")
                    w.flush()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    terminalLines.add("❌ 命令发送失败: ${e.localizedMessage}")
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 开始下载 + 解压 Debian rootfs
    // ─────────────────────────────────────────────
    fun startInstallDebian() {
        viewModelScope.launch {
            envState = EnvironmentState.Downloading
            val downloadOk = performDownloadRootfs(
                downloadUrl = imageUrl,
                targetFile = tarXzFile,
                onProgress = { downloadProgress = it },
                onStatusChanged = { currentStatusMessage = it }
            )
            if (downloadOk) {
                envState = EnvironmentState.Extracting
                val extractOk = performExtractTarXz(
                    archiveFile = tarXzFile,
                    destinationDir = rootfsDir,
                    onStatusChanged = { currentStatusMessage = it }
                )
                if (extractOk) {
                    terminalLines.add("Debian rootfs dynamic deployment completely successful!")
                    terminalLines.add("System environment ready. Enjoy full Linux terminal ecosystem.")
                    envState = EnvironmentState.Ready
                    startShellIfNeeded()
                } else {
                    envState = EnvironmentState.NotInstalled
                }
            } else {
                envState = EnvironmentState.NotInstalled
            }
        }
    }

    // ─────────────────────────────────────────────
    // ViewModel 销毁时（Activity 销毁）彻底清理进程
    // ─────────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        shellProcess?.destroy()
        shellWriter = null
    }

    // ─────────────────────────────────────────────
    // 以下为从 TerminalScreen 迁移过来的工具函数
    // ─────────────────────────────────────────────

    private fun isDebianInstalled(rootfsDir: File): Boolean {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false
        val etcDir = File(rootfsDir, "etc")
        val usrDir = File(rootfsDir, "usr")
        val hasEtcPasswd = File(etcDir, "passwd").exists()
        val hasUsrBin =
            File(usrDir, "bin").exists() && File(usrDir, "bin").isDirectory
        val shFile = File(rootfsDir, "bin/sh")
        val hasShSymlink = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.exists(shFile.toPath(), LinkOption.NOFOLLOW_LINKS)
            } else {
                shFile.exists() || shFile.length() > 0 ||
                        shFile.parentFile?.exists() == true
            }
        } catch (e: Exception) {
            false
        }
        return (hasUsrBin && hasEtcPasswd) || (hasShSymlink && hasUsrBin)
    }

    private fun safeCreateParentDirs(file: File, rootfsDir: File) {
        val parent = file.parentFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.createDirectories(parent.toPath())
                return
            } catch (e: Exception) { /* fallback */ }
        }
        parent.mkdirs()
    }

    private suspend fun performDownloadRootfs(
        downloadUrl: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
        onStatusChanged: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: BufferedInputStream? = null
        var outputStream: FileOutputStream? = null
        var currentUrl = downloadUrl
        var redirectCount = 0
        val maxRedirects = 5
        var downloadSuccess = false
        try {
            if (targetFile.exists()) targetFile.delete()
            while (redirectCount < maxRedirects) {
                withContext(Dispatchers.Main) { onStatusChanged("正在建立安全数据连接…") }
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == 303 || status == 307 || status == 308
                ) {
                    val newUrl = connection.getHeaderField("Location")
                    if (!newUrl.isNullOrBlank()) {
                        currentUrl = newUrl
                        redirectCount++
                        connection.disconnect()
                        continue
                    }
                }
                if (status == HttpURLConnection.HTTP_OK) {
                    downloadSuccess = true; break
                } else {
                    withContext(Dispatchers.Main) { onStatusChanged("❌ 服务器拒绝请求: HTTP $status") }
                    return@withContext false
                }
            }
            if (!downloadSuccess) {
                withContext(Dispatchers.Main) { onStatusChanged("❌ 错误: CDN 重定向次数过多") }
                return@withContext false
            }
            val fileLength = connection!!.contentLengthLong
            inputStream = BufferedInputStream(connection.inputStream, 16384)
            outputStream = FileOutputStream(targetFile)
            val data = ByteArray(16384)
            var totalBytesRead: Long = 0
            var bytesRead: Int
            while (inputStream.read(data).also { bytesRead = it } != -1) {
                totalBytesRead += bytesRead
                outputStream.write(data, 0, bytesRead)
                if (fileLength > 0) {
                    val progress = totalBytesRead.toFloat() / fileLength.toFloat()
                    val readMb = String.format("%.1f", totalBytesRead.toFloat() / (1024 * 1024))
                    val totalMb = String.format("%.1f", fileLength.toFloat() / (1024 * 1024))
                    withContext(Dispatchers.Main) {
                        onStatusChanged("已下载 $readMb MB / $totalMb MB")
                        onProgress(progress)
                    }
                } else {
                    val readMb = String.format("%.1f", totalBytesRead.toFloat() / (1024 * 1024))
                    withContext(Dispatchers.Main) {
                        onStatusChanged("已下载 $readMb MB (流式无界传输中…)")
                        onProgress(0f)
                    }
                }
            }
            outputStream.flush()
            return@withContext true
        } catch (e: SecurityException) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 安全限制: 请检查 AndroidManifest.xml 是否配置了 INTERNET 权限") }
            return@withContext false
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 网络异常: ${e.localizedMessage ?: "数据握手失败"}") }
            return@withContext false
        } finally {
            outputStream?.close()
            inputStream?.close()
            connection?.disconnect()
        }
    }

    private suspend fun performExtractTarXz(
        archiveFile: File,
        destinationDir: File,
        onStatusChanged: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (destinationDir.exists()) destinationDir.deleteRecursively()
            destinationDir.mkdirs()
            onStatusChanged("正在解密 XZ 压缩矩阵…")
            val fileIn = archiveFile.inputStream()
            val bufferedIn = BufferedInputStream(fileIn, 16384)
            val xzIn = XZInputStream(bufferedIn)
            val tarIn = TarArchiveInputStream(xzIn)
            var entry = tarIn.nextEntry
            var fileCount = 0
            while (entry != null) {
                val targetFile = File(destinationDir, entry.name)
                if (!targetFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                    entry = tarIn.nextEntry; continue
                }
                when {
                    entry.isDirectory -> targetFile.mkdirs()
                    entry.isSymbolicLink -> {
                        safeCreateParentDirs(targetFile, destinationDir)
                        targetFile.delete()
                        try { Os.symlink(entry.linkName, targetFile.absolutePath) } catch (e: Exception) { e.printStackTrace() }
                    }
                    entry.isLink -> {
                        safeCreateParentDirs(targetFile, destinationDir)
                        targetFile.delete()
                        try {
                            val existingFile = File(destinationDir, entry.linkName.removePrefix("/"))
                            if (existingFile.exists()) Os.link(existingFile.absolutePath, targetFile.absolutePath)
                            else Os.symlink(entry.linkName, targetFile.absolutePath)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    else -> {
                        safeCreateParentDirs(targetFile, destinationDir)
                        targetFile.delete()
                        FileOutputStream(targetFile).use { fos ->
                            val buffer = ByteArray(16384)
                            var len: Int
                            while (tarIn.read(buffer).also { len = it } != -1) fos.write(buffer, 0, len)
                            fos.flush()
                        }
                        if (entry.mode and 0x40 != 0 || entry.name.contains("bin/")) {
                            targetFile.setExecutable(true, false)
                        }
                        targetFile.setReadable(true, false)
                    }
                }
                fileCount++
                if (fileCount % 300 == 0) {
                    withContext(Dispatchers.Main) { onStatusChanged("已释放 $fileCount 个 Linux 系统节点文件…") }
                }
                entry = tarIn.nextEntry
            }
            tarIn.close()
            if (archiveFile.exists()) archiveFile.delete()
            return@withContext true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 部署异常: ${e.localizedMessage ?: "系统解压中断"}") }
            return@withContext false
        }
    }
}
