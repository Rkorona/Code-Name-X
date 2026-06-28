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
                        // ── 项目目录绑定：让终端里能直接访问 App 内的项目文件 ──
                        val internalProjects = File(context.filesDir, "projects")
                        val externalProjects = context.getExternalFilesDir(null)
                            ?.let { File(it, "projects") }
                        if (internalProjects.isDirectory) {
                            add("-b"); add("${internalProjects.absolutePath}:/projects")
                        } else if (externalProjects?.isDirectory == true) {
                            add("-b"); add("${externalProjects.absolutePath}:/projects")
                        }
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
                // PRoot tmp 目录（用于 glue rootfs 临时文件）
                val prootTmpDir = File(rootfsDir, "tmp").also { it.mkdirs() }
                val env = pb.environment()
                env["PROOT_TMP_DIR"] = prootTmpDir.absolutePath

                // ── Samsung Knox / Android noexec 关键修复 ──────────────────────────────
                // Samsung 等设备将 /data/data/ 挂载为 noexec，导致 PRoot 无法通过
                // 内核 execve() 执行 rootfs 内的任何 ELF 二进制文件（Permission denied）。
                // Termux 版 PRoot 支持 PROOT_LOADER：将真正的 execve 代理到一个位于
                // nativeLibraryDir（/data/app/…，可执行分区）的 loader 二进制，
                // 由 loader 在用户空间完成 ELF 加载，彻底绕过内核 noexec 限制。
                val nativeDirPath = context.applicationInfo.nativeLibraryDir
                val loaderFile = File(nativeDirPath, "libproot-loader.so")
                if (loaderFile.exists()) {
                    if (!loaderFile.canExecute()) {
                        try { loaderFile.setExecutable(true, false) } catch (_: Exception) {}
                    }
                    env["PROOT_LOADER"] = loaderFile.absolutePath
                }
                // ────────────────────────────────────────────────────────────────────────

                // ── libtalloc.so.2 动态库修复 ─────────────────────────────────────────
                // Android jniLibs 只接受 lib*.so 格式的文件名，libtalloc.so.2 带版本后缀
                // 无法直接打包进 APK 的 nativeLibraryDir。
                // 变通方案：jniLibs 中命名为 libtalloc.so，运行时复制并重命名为
                // libtalloc.so.2 放到 filesDir/lib，再将该目录加入 LD_LIBRARY_PATH。
                val libDir = File(context.filesDir, "lib").also { it.mkdirs() }
                val tallocDest = File(libDir, "libtalloc.so.2")
                val tallocSrc  = File(nativeDirPath, "libtalloc.so")
                if (!tallocDest.exists() && tallocSrc.exists()) {
                    try {
                        tallocSrc.copyTo(tallocDest, overwrite = true)
                        tallocDest.setReadable(true, false)
                    } catch (_: Exception) {}
                }
                // LD_LIBRARY_PATH 同时包含：
                // 1. filesDir/lib —— 存放版本后缀库（如 libtalloc.so.2）
                // 2. nativeLibraryDir —— 存放标准命名库（如 libandroid-shmem.so）
                val existingLdPath = env["LD_LIBRARY_PATH"]
                val ldParts = buildList {
                    if (libDir.exists()) add(libDir.absolutePath)
                    add(nativeDirPath)
                    if (!existingLdPath.isNullOrEmpty()) add(existingLdPath)
                }
                env["LD_LIBRARY_PATH"] = ldParts.joinToString(":")
                // ────────────────────────────────────────────────────────────────────────

                env["PATH"] =
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
                env["HOME"] = "/root"
                env["TERM"] = "xterm-256color"
                env["LANG"] = "C.UTF-8"
                env["LC_ALL"] = "C.UTF-8"
                env["USER"] = "root"
                env["SHELL"] = "/bin/bash"
                env["DEBIAN_FRONTEND"] = "noninteractive"

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
                    envState = EnvironmentState.Initializing
                    currentStatusMessage = "正在初始化 Debian 环境…"
                    performInitDebian(onStatusChanged = { currentStatusMessage = it })
                    terminalLines.add("✅ Debian rootfs 部署完成，环境初始化成功！")
                    terminalLines.add("已配置国内 APT 镜像源，运行 apt update 开始使用。")
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
    // Debian 环境初始化：解压完成后自动配置必备文件
    // ─────────────────────────────────────────────
    private suspend fun performInitDebian(
        onStatusChanged: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {

        // 辅助函数：先删除（处理悬空符号链接），再写文件，再 chmod
        fun safeWrite(relPath: String, content: String, mode: Int = 0x1A4 /* 0644 */) {
            val f = File(rootfsDir, relPath)
            // 确保父目录存在且可写
            f.parentFile?.let { parent ->
                parent.mkdirs()
                try { Os.chmod(parent.absolutePath, 0x1ED) } catch (_: Exception) {}
            }
            // 删除原文件或悬空符号链接（Debian 的 /etc/resolv.conf 是指向 /run/... 的软链接）
            // File.delete() 在底层调用 unlink()，能正确移除符号链接本身（不跟随链接）
            f.delete()
            f.writeText(content)
            try { Os.chmod(f.absolutePath, mode) } catch (_: Exception) {}
        }

        // 1. /etc/apt/sources.list — 中科大 USTC 镜像（国内最快之一）
        onStatusChanged("正在配置 APT 软件源（USTC 镜像）…")
        safeWrite(
            "etc/apt/sources.list",
            "# Debian trixie — USTC Mirror\n" +
            "deb https://mirrors.ustc.edu.cn/debian/ trixie main contrib non-free non-free-firmware\n" +
            "deb https://mirrors.ustc.edu.cn/debian/ trixie-updates main contrib non-free non-free-firmware\n" +
            "deb https://mirrors.ustc.edu.cn/debian-security/ trixie-security main contrib non-free non-free-firmware\n"
        )

        // 2. /etc/resolv.conf — DNS（阿里 + 腾讯公共 DNS）
        // 注意：Debian 中此文件常为指向 /run/systemd/resolve/stub-resolv.conf 的悬空符号链接，
        //       必须先 unlink 再写普通文件，否则 FileNotFoundException: ENOENT。
        onStatusChanged("正在配置 DNS 解析…")
        safeWrite(
            "etc/resolv.conf",
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
        )

        // 3. /etc/hosts — 基础主机名解析
        onStatusChanged("正在配置主机名解析…")
        safeWrite(
            "etc/hosts",
            "127.0.0.1   localhost\n::1         localhost ip6-localhost ip6-loopback\n"
        )

        // 4. /etc/hostname
        safeWrite("etc/hostname", "debian\n")

        // 5. /proc /sys /dev 占位目录（防止 PRoot 绑定时报 "not a directory"）
        listOf("proc", "sys", "dev", "dev/pts", "tmp", "run", "run/systemd/resolve").forEach { dir ->
            File(rootfsDir, dir).mkdirs()
        }
        // 在 /run/systemd/resolve/ 放一个空的 stub-resolv.conf 占位，
        // 防止其他程序通过原来的符号链接路径找不到文件
        safeWrite("run/systemd/resolve/stub-resolv.conf",
            "nameserver 223.5.5.5\nnameserver 119.29.29.29\n")

        // 6. /root/.bashrc — 友好的交互提示符 + 常用别名
        onStatusChanged("正在配置 Shell 环境…")
        safeWrite(
            "root/.bashrc",
            "# ~/.bashrc — PRoot Debian (auto-generated by app)\n" +
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

        // 7. /root/.profile
        File(rootfsDir, "root/.profile").let { f ->
            if (!f.exists()) safeWrite("root/.profile", "[ -f ~/.bashrc ] && . ~/.bashrc\n")
        }

        // 8. /etc/apt/apt.conf.d/99norecommends — 不安装推荐包，节省空间
        safeWrite(
            "etc/apt/apt.conf.d/99norecommends",
            "APT::Install-Recommends \"false\";\nAPT::Install-Suggests \"false\";\n"
        )

        // 9. /etc/apt/apt.conf.d/99timeout — 防止 apt 在无网络时长时间卡住
        safeWrite(
            "etc/apt/apt.conf.d/99timeout",
            "Acquire::http::Timeout \"15\";\nAcquire::https::Timeout \"15\";\n"
        )

        onStatusChanged("环境初始化完成！")
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

        // 1. 必须有 /etc/passwd（说明基础系统包已解压）
        val hasEtcPasswd = File(rootfsDir, "etc/passwd").let {
            it.exists() && it.length() > 0
        }
        if (!hasEtcPasswd) return false

        // 2. 核心 shell 二进制必须实际存在（非目录），防止解压中断后误判为已安装
        val shellCandidates = listOf(
            "usr/bin/bash", "usr/bin/sh", "usr/bin/dash",
            "bin/bash", "bin/sh", "bin/dash"
        )
        val hasShell = shellCandidates.any { rel ->
            val f = File(rootfsDir, rel)
            try {
                // 用 NOFOLLOW_LINKS 检测路径存在（包括符号链接指向）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) && !f.isDirectory
                } else {
                    f.exists() && !f.isDirectory
                }
            } catch (e: Exception) { false }
        }
        if (!hasShell) return false

        // 3. /usr/lib 必须存在且不为空（ELF 动态链接器所在），
        //    这是区分「下载完未解压」和「解压完整」的关键检测
        val usrLib = File(rootfsDir, "usr/lib")
        val hasLibs = usrLib.isDirectory && (usrLib.list()?.size ?: 0) > 2

        return hasLibs
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
                    entry.isDirectory -> {
                        targetFile.mkdirs()
                        // 保留 tar 中目录的原始权限（至少保证 rwx------）
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
                        // 用 POSIX Os.chmod() 直接设置 tar 原始权限，比 setExecutable() 更可靠。
                        // 对 bin/sbin 目录下的文件以及 owner-execute 位已置位的文件确保可执行。
                        val fileMode = entry.mode and 0x1FF
                        val finalMode = when {
                            fileMode != 0 -> fileMode
                            entry.name.contains("bin/") || entry.name.contains("sbin/") -> 0x1ED // 0755
                            else -> 0x1A4 // 0644
                        }
                        try { Os.chmod(targetFile.absolutePath, finalMode) } catch (_: Exception) {
                            // fallback: Java API
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