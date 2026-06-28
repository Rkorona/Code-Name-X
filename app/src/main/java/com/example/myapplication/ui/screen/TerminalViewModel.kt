package com.example.myapplication.ui.screen

import android.app.Application
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getBy
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.utils.PtyProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption

// ── 新增定义 EnvironmentState 枚举，解决 Unresolved reference 问题 ──
enum class EnvironmentState {
    Checking,
    NotInstalled,
    Downloading,
    Extracting,
    Initializing,
    Ready
}

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    // ── 安装阶段日志（NotInstalled / Downloading / Extracting / Initializing）──
    val terminalLines = mutableStateListOf<String>()

    // ── PTY 原始字节输出流（Ready 状态 → WebView / xterm.js 消费）──
    private val _ptyOutput = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val ptyOutput: SharedFlow<ByteArray> = _ptyOutput.asSharedFlow()

    // ── 环境状态 ──
    var envState by mutableStateOf(EnvironmentState.Checking)
        private set

    var downloadProgress by mutableFloatStateOf(0f)
        private set

    var currentStatusMessage by mutableStateOf("正在等待指令…")
        private set

    // ── PTY 文件描述符 ──
    private var masterPfd: ParcelFileDescriptor? = null
    private var childPid: Int = -1

    // ── 防止重复启动 ──
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
    // 环境检测
    // ─────────────────────────────────────────────
    private fun checkEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = isDebianInstalled(rootfsDir)
            withContext(Dispatchers.Main) {
                envState = if (installed) {
                    EnvironmentState.Ready
                } else {
                    EnvironmentState.NotInstalled
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 启动 PTY Shell（只启动一次）
    // ─────────────────────────────────────────────
    fun startShellIfNeeded() {
        if (shellStarted) return
        shellStarted = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = File(context.applicationInfo.nativeLibraryDir)

                // ── 找 PRoot 主程序 ──
                val possibleNames = listOf("libproot.so", "libproot-userland.so", "libproot_real.so")
                var prootFile = possibleNames.map { File(nativeDir, it) }.firstOrNull { it.exists() }
                if (prootFile == null) {
                    prootFile = nativeDir.listFiles()?.firstOrNull {
                        it.name.contains("proot", ignoreCase = true) && !it.name.contains("loader")
                    }
                }
                if (prootFile != null && !prootFile.canExecute()) {
                    try { prootFile.setExecutable(true, false) } catch (_: Exception) {}
                }

                // ── 选择 Shell 路径 ──
                val shellPath = listOf("/usr/bin/bash", "/bin/bash", "/usr/bin/sh", "/bin/sh")
                    .firstOrNull { path ->
                        val f = File(rootfsDir, path.removePrefix("/"))
                        f.exists() && !f.isDirectory
                    } ?: "/bin/sh"

                // ── 构建命令行 ──
                val cmd: List<String>
                if (prootFile != null && prootFile.exists()) {
                    val usrBin  = File(rootfsDir, "usr/bin")
                    val usrLib  = File(rootfsDir, "usr/lib")
                    val usrSbin = File(rootfsDir, "usr/sbin")
                    cmd = buildList {
                        add(prootFile.absolutePath)
                        add("--link2symlink")
                        add("-r"); add(rootfsDir.absolutePath)
                        add("-0")
                        add("-w"); add("/root")
                        add("-b"); add("/dev")
                        add("-b"); add("/proc")
                        add("-b"); add("/sys")
                        add("-b"); add("/dev/urandom")
                        if (usrBin.isDirectory)  { add("-b"); add("${usrBin.absolutePath}:/bin") }
                        if (usrLib.isDirectory)  { add("-b"); add("${usrLib.absolutePath}:/lib") }
                        if (usrLib.isDirectory)  { add("-b"); add("${usrLib.absolutePath}:/lib64") }
                        if (usrSbin.isDirectory) { add("-b"); add("${usrSbin.absolutePath}:/sbin") }
                        val internalProjects = File(context.filesDir, "projects")
                        val externalProjects = context.getExternalFilesDir(null)?.let { File(it, "projects") }
                        if (internalProjects.isDirectory) {
                            add("-b"); add("${internalProjects.absolutePath}:/projects")
                        } else if (externalProjects?.isDirectory == true) {
                            add("-b"); add("${externalProjects.absolutePath}:/projects")
                        }
                        add(shellPath)
                    }
                } else {
                    cmd = listOf("/system/bin/sh")
                }

                // ── 构建环境变量数组 ──
                val nativeDirPath = context.applicationInfo.nativeLibraryDir
                val prootTmpDir = File(rootfsDir, "tmp").also { it.mkdirs() }

                val libDir = File(context.filesDir, "lib").also { it.mkdirs() }
                val tallocDest = File(libDir, "libtalloc.so.2")
                val tallocSrc  = File(nativeDirPath, "libtalloc.so")
                if (!tallocDest.exists() && tallocSrc.exists()) {
                    try { tallocSrc.copyTo(tallocDest, overwrite = true); tallocDest.setReadable(true, false) } catch (_: Exception) {}
                }

                val ldParts = buildList {
                    if (libDir.exists()) add(libDir.absolutePath)
                    add(nativeDirPath)
                }
                val loaderFile = File(nativeDirPath, "libproot-loader.so")
                if (loaderFile.exists() && !loaderFile.canExecute()) {
                    try { loaderFile.setExecutable(true, false) } catch (_: Exception) {}
                }

                val envList = mutableListOf(
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
                    "HOME=/root",
                    "TERM=xterm-256color",
                    "LANG=C.UTF-8",
                    "LC_ALL=C.UTF-8",
                    "USER=root",
                    "LOGNAME=root",
                    "SHELL=/bin/bash",
                    "DEBIAN_FRONTEND=noninteractive",
                    "PROOT_TMP_DIR=${prootTmpDir.absolutePath}",
                    "LD_LIBRARY_PATH=${ldParts.joinToString(":")}",
                )
                if (loaderFile.exists()) {
                    envList.add("PROOT_LOADER=${loaderFile.absolutePath}")
                }

                // ── fork PTY 子进程 ──
                val result = PtyProcess.forkExecPty(
                    cmd.toTypedArray(),
                    envList.toTypedArray(),
                    "/root"
                )

                if (result == null || result.size < 2) {
                    Log.e("TerminalViewModel", "forkExecPty returned null or invalid result")
                    withContext(Dispatchers.Main) {
                        terminalLines.add("❌ PTY fork 失败，回退到 pipe 模式不可用")
                        shellStarted = false
                    }
                    return@launch
                }

                val masterFdInt = result[0]
                childPid = result[1]
                val pfd = ParcelFileDescriptor.adoptFd(masterFdInt)
                masterPfd = pfd
                val fdObj = pfd.fileDescriptor

                // ── 持续从 PTY master 读取原始字节并发射 ──
                val buf = ByteArray(4096)
                while (true) {
                    val n = try {
                        Os.read(fdObj, buf, 0, buf.size)
                    } catch (e: Exception) {
                        break
                    }
                    if (n <= 0) break
                    _ptyOutput.emit(buf.copyOf(n))
                }

                withContext(Dispatchers.Main) { shellStarted = false }

            } catch (e: Exception) {
                Log.e("TerminalViewModel", "startShellIfNeeded failed", e)
                withContext(Dispatchers.Main) {
                    terminalLines.add("❌ 启动终端进程失败: ${e.localizedMessage}")
                    shellStarted = false
                }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 向 PTY 写入原始字节（用户输入 / 工具栏按键）
    // ─────────────────────────────────────────────
    fun sendInput(data: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                masterPfd?.fileDescriptor?.let { fd ->
                    Os.write(fd, data, 0, data.size)
                }
            } catch (_: Exception) { }
        }
    }

    // ─────────────────────────────────────────────
    // 调整 PTY 窗口大小（xterm.js resize 触发）
    // ─────────────────────────────────────────────
    fun resizePty(rows: Int, cols: Int) {
        val fd = masterPfd?.fd ?: return
        PtyProcess.resizePty(fd, rows, cols)
    }

    // ─────────────────────────────────────────────
    // 强制重启 Shell（Ctrl+C / 手动重置）
    // ─────────────────────────────────────────────
    fun restartShell() {
        try {
            if (childPid > 0) PtyProcess.killPid(childPid, 9)
            masterPfd?.close()
        } catch (_: Exception) { }
        masterPfd = null
        childPid = -1
        shellStarted = false
        startShellIfNeeded()
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
                    envState = EnvironmentState.Initializing
                    currentStatusMessage = "正在初始化 Debian 环境…"
                    performInitDebian(onStatusChanged = { currentStatusMessage = it })
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
    // ViewModel 销毁时清理
    // ─────────────────────────────────────────────
    override fun onCleared() {
        super.onCleared()
        try {
            if (childPid > 0) PtyProcess.killPid(childPid, 9)
            masterPfd?.close()
        } catch (_: Exception) { }
        masterPfd = null
    }

    // ─────────────────────────────────────────────
    // Debian 环境初始化
    // ─────────────────────────────────────────────
    private suspend fun performInitDebian(
        onStatusChanged: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {

        fun safeWrite(relPath: String, content: String, mode: Int = 0x1A4) {
            val f = File(rootfsDir, relPath)
            f.parentFile?.let { parent ->
                parent.mkdirs()
                try { Os.chmod(parent.absolutePath, 0x1ED) } catch (_: Exception) {}
            }
            f.delete()
            f.writeText(content)
            try { Os.chmod(f.absolutePath, mode) } catch (_: Exception) {}
        }

        onStatusChanged("正在配置 APT 软件源（USTC 镜像）…")
        safeWrite(
            "etc/apt/sources.list",
            "# Debian trixie — USTC Mirror\n" +
            "deb https://mirrors.ustc.edu.cn/debian/ trixie main contrib non-free non-free-firmware\n" +
            "deb https://mirrors.ustc.edu.cn/debian/ trixie-updates main contrib non-free non-free-firmware\n" +
            "deb https://mirrors.ustc.edu.cn/debian-security/ trixie-security main contrib non-free non-free-firmware\n"
        )

        onStatusChanged("正在配置 DNS 解析…")
        safeWrite("etc/resolv.conf",
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")

        onStatusChanged("正在配置主机名解析…")
        safeWrite("etc/hosts",
            "127.0.0.1   localhost\n::1         localhost ip6-localhost ip6-loopback\n")

        safeWrite("etc/hostname", "debian\n")

        listOf("proc", "sys", "dev", "dev/pts", "tmp", "run", "run/systemd/resolve").forEach { dir ->
            File(rootfsDir, dir).mkdirs()
        }
        safeWrite("run/systemd/resolve/stub-resolv.conf",
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\n")

        onStatusChanged("正在配置 Shell 环境…")
        safeWrite(
            "root/.bashrc",
            "# ~/.bashrc — PRoot Debian\n" +
            "export TERM=xterm-256color\n" +
            "export LANG=C.UTF-8\n" +
            "export LC_ALL=C.UTF-8\n" +
            "export PS1='\\[\\e[1;32m\\]root@debian\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
            "alias ll='ls -alF --color=auto'\n" +
            "alias la='ls -A --color=auto'\n" +
            "alias l='ls -CF --color=auto'\n" +
            "alias apt='apt -o APT::Sandbox::User=root'\n" +
            "export DEBIAN_FRONTEND=noninteractive\n"
        )
        File(rootfsDir, "root/.profile").let { f ->
            if (!f.exists()) safeWrite("root/.profile", "[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }
        safeWrite("etc/apt/apt.conf.d/99norecommends",
            "APT::Install-Recommends \"false\";\nAPT::Install-Suggests \"false\";\n")
        safeWrite("etc/apt/apt.conf.d/99timeout",
            "Acquire::http::Timeout \"15\";\nAcquire::https::Timeout \"15\";\n")

        onStatusChanged("环境初始化完成！")
    }

    // ─────────────────────────────────────────────
    // 工具函数
    // ─────────────────────────────────────────────
    private fun isDebianInstalled(rootfsDir: File): Boolean {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false
        val hasEtcPasswd = File(rootfsDir, "etc/passwd").let { it.exists() && it.length() > 0 }
        if (!hasEtcPasswd) return false
        val shellCandidates = listOf(
            "usr/bin/bash", "usr/bin/sh", "usr/bin/dash", "bin/bash", "bin/sh", "bin/dash"
        )
        val hasShell = shellCandidates.any { rel ->
            val f = File(rootfsDir, rel)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) && !f.isDirectory
                else f.exists() && !f.isDirectory
            } catch (_: Exception) { false }
        }
        if (!hasShell) return false
        val usrLib = File(rootfsDir, "usr/lib")
        return usrLib.isDirectory && (usrLib.list()?.size ?: 0) > 2
    }

    private fun safeCreateParentDirs(file: File, rootfsDir: File) {
        val parent = file.parentFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { Files.createDirectories(parent.toPath()); return } catch (_: Exception) {}
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
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val newUrl = connection.getHeaderField("Location")
                    if (!newUrl.isNullOrBlank()) {
                        currentUrl = newUrl; redirectCount++; connection.disconnect(); continue
                    }
                }
                if (status == HttpURLConnection.HTTP_OK) { downloadSuccess = true; break }
                else {
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
                    val readMb = "%.1f".format(totalBytesRead.toFloat() / (1024 * 1024))
                    val totalMb = "%.1f".format(fileLength.toFloat() / (1024 * 1024))
                    withContext(Dispatchers.Main) { onStatusChanged("已下载 $readMb MB / $totalMb MB"); onProgress(progress) }
                } else {
                    val readMb = "%.1f".format(totalBytesRead.toFloat() / (1024 * 1024))
                    withContext(Dispatchers.Main) { onStatusChanged("已下载 $readMb MB…"); onProgress(0f) }
                }
            }
            outputStream.flush()
            return@withContext true
        } catch (e: SecurityException) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 安全限制: 请检查 INTERNET 权限") }
            return@withContext false
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 网络异常: ${e.localizedMessage ?: "数据握手失败"}") }
            return@withContext false
        } finally {
            outputStream?.close(); inputStream?.close(); connection?.disconnect()
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
            val xzIn = XZInputStream(BufferedInputStream(fileIn, 16384))
            val tarIn = TarArchiveInputStream(xzIn)
            var entry = tarIn.nextEntry
            var fileCount = 0
            while (entry != null) {
                val targetFile = File(destinationDir, entry.name)
                if (!targetFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                    entry = tarIn.nextEntry; continue
                }
                when {
                    entry.isDirectory -> {
                        targetFile.mkdirs()
                        val dirMode = (entry.mode and 0x1FF).let { if (it == 0) 0x1C0 else it }
                        try { Os.chmod(targetFile.absolutePath, dirMode) } catch (_: Exception) {}
                    }
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
                        val fileMode = entry.mode and 0x1FF
                        val finalMode = when {
                            fileMode != 0 -> fileMode
                            entry.name.contains("bin/") || entry.name.contains("sbin/") -> 0x1ED
                            else -> 0x1A4
                        }
                        try { Os.chmod(targetFile.absolutePath, finalMode) } catch (_: Exception) {
                            if (finalMode and 0x49 != 0) targetFile.setExecutable(true, false)
                            targetFile.setReadable(true, false)
                        }
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