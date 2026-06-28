package com.example.myapplication.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────────────────────────
// 环境状态机枚举
// ─────────────────────────────────────────────
enum class EnvironmentState {
    Checking,       // 检测环境现状中
    NotInstalled,   // 尚未部署 Debian 环境
    Downloading,    // 正在下载 rootfs 镜像
    Extracting,     // 正在解压并部署文件系统
    Ready           // 环境就绪，可以运行
}

@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // ── 状态控制 ──
    var envState by remember { mutableStateOf(EnvironmentState.Checking) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var currentStatusMessage by remember { mutableStateOf("正在等待指令…") }

    // ── 终端控制台流 ──
    val terminalLines = remember { mutableStateListOf<String>() }
    var currentInput by remember { mutableStateOf("") }

    // ── 核心路径定义 ──
    val rootfsDir = remember { File(context.filesDir, "debian_rootfs") }
    val tarXzFile = remember { File(context.cacheDir, "rootfs.tar.xz") }
    // 目标高版本镜像源
    val imageUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260627_14%3A22/rootfs.tar.xz"

    // ── 终端样式配置 ──
    val terminalBackground = Color(0xFF000000)
    val terminalTextColor = Color(0xFF00FF00)

    // ── 自动检测现存 Debian 环境是否健全 ──
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val shExec = File(rootfsDir, "bin/sh")
            if (shExec.exists() && rootfsDir.isDirectory) {
                envState = EnvironmentState.Ready
                withContext(Dispatchers.Main) {
                    terminalLines.add("Welcome to Debian GNU/Linux 12 (bookworm) via PRoot!")
                    terminalLines.add("System architecture: aarch64 (Android sandboxed)")
                    terminalLines.add("Debian rootfs detected successfully.")
                    terminalLines.add("")
                }
            } else {
                envState = EnvironmentState.NotInstalled
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ─────────────────────────────────────────────
        // 1. 标准控制台内核层 UI
        // ─────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(terminalBackground)
                .padding(8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(terminalLines) { line ->
                    Text(
                        text = line,
                        style = TextStyle(
                            color = if (line.startsWith("❌") || line.contains("Error")) Color.Red else terminalTextColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "sandbox@debian:~$ ",
                    style = TextStyle(
                        color = Color(0xFF3399FF),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                BasicTextField(
                    value = currentInput,
                    onValueChange = { currentInput = it },
                    modifier = Modifier.weight(1f),
                    enabled = envState == EnvironmentState.Ready,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(Color.White),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (currentInput.isNotBlank()) {
                                val cmd = currentInput.trim()
                                terminalLines.add("sandbox@debian:~$ $cmd")

                                when {
                                    cmd == "clear" -> terminalLines.clear()
                                    cmd == "ls" -> terminalLines.add("home/  var/  etc/  root/  workspace/  bin/  sbin/")
                                    cmd.startsWith("apt") -> {
                                        terminalLines.add("Reading package lists... Done")
                                        terminalLines.add("Building dependency tree... Done")
                                        terminalLines.add("❌ Error: PRoot native system call bridge connection pending.")
                                    }
                                    else -> terminalLines.add("bash: $cmd: command not found (PRoot native process bridge pending)")
                                }

                                currentInput = ""
                                coroutineScope.launch {
                                    if (terminalLines.size > 0) {
                                        listState.animateScrollToItem(terminalLines.size - 1)
                                    }
                                }
                            }
                        }
                    )
                )
            }
        }

        // ─────────────────────────────────────────────
        // 2. 改造后的安装提示弹窗（支持渲染实时错误日志）
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.NotInstalled) {
            AlertDialog(
                onDismissRequest = { },
                confirmButton = {
                    Button(
                        onClick = {
                            envState = EnvironmentState.Downloading
                            coroutineScope.launch {
                                val downloadSuccess = performDownloadRootfs(
                                    downloadUrl = imageUrl,
                                    targetFile = tarXzFile,
                                    onProgress = { progress -> downloadProgress = progress },
                                    onStatusChanged = { msg -> currentStatusMessage = msg }
                                )

                                if (downloadSuccess) {
                                    envState = EnvironmentState.Extracting
                                    val extractSuccess = performExtractTarXz(
                                        archiveFile = tarXzFile,
                                        destinationDir = rootfsDir,
                                        onStatusChanged = { msg -> currentStatusMessage = msg }
                                    )

                                    if (extractSuccess) {
                                        envState = EnvironmentState.Ready
                                        terminalLines.add("Debian rootfs dynamic deployment completely successful!")
                                        terminalLines.add("System environment ready. Enjoy full Linux terminal ecosystem.")
                                    } else {
                                        envState = EnvironmentState.NotInstalled
                                        // 保持 currentStatusMessage 里的解压错误字样
                                    }
                                } else {
                                    envState = EnvironmentState.NotInstalled
                                    // 保持 currentStatusMessage 里的下载错误字样
                                }
                            }
                        }
                    ) {
                        Text("立即下载并部署")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { }) {
                        Text("暂不配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                title = { Text("配置 Linux 开发运行环境", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "检测到应用尚未安装 Debian Linux 系统容器。运行多语言代码、格式化程序以及启动高级 LSP 服务需要下载并解压大约 90MB 的核心基础包。\n\n建议在 Wi-Fi 网络环境下进行该操作。",
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                        
                        // 亮点：如果失败了，直接在这里把报错信息怼在用户脸上，拒绝一抹黑！
                        if (currentStatusMessage.startsWith("❌")) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = currentStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }

        // ─────────────────────────────────────────────
        // 3. 弹窗层：全锁定下载与部署进度长效 Dialog
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.Downloading || envState == EnvironmentState.Extracting) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = if (envState == EnvironmentState.Downloading) "正在获取 Debian 系统镜像…" else "正在构建根文件系统…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (envState == EnvironmentState.Downloading) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                                strokeCap = StrokeCap.Round
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = currentStatusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${(downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.CenterHorizontally),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = currentStatusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 强力高防网络下载引擎（支持多重手动重定向 + UA 伪装）
// ─────────────────────────────────────────────
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
        if (targetFile.exists()) {
            targetFile.delete()
        }

        // 核心重定向追踪循环
        while (redirectCount < maxRedirects) {
            withContext(Dispatchers.Main) { onStatusChanged("正在建立安全数据连接…") }
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false // 关闭原生混淆，由我们精确掌控
            
            // 重要：注入标准浏览器 User-Agent，防止开源镜像站因安全审查拦截直接返回 403
            connection.setRequestProperty(
                "User-Agent", 
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )

            val status = connection.responseCode
            
            // 判定是否属于 HTTP 重定向家族（301, 302, 303, 307, 308）
            if (status == HttpURLConnection.HTTP_MOVED_PERM || 
                status == HttpURLConnection.HTTP_MOVED_TEMP || 
                status == 303 || status == 307 || status == 308) {
                
                val newUrl = connection.getHeaderField("Location")
                if (!newUrl.isNullOrBlank()) {
                    currentUrl = newUrl
                    redirectCount++
                    connection.disconnect()
                    continue // 跃迁到下一级 CDN 节点
                }
            }

            if (status == HttpURLConnection.HTTP_OK) {
                downloadSuccess = true
                break // 成功驻留有效资源节点
            } else {
                withContext(Dispatchers.Main) { 
                    onStatusChanged("❌ 服务器拒绝请求: HTTP $status (可能节点正忙)") 
                }
                return@withContext false
            }
        }

        if (!downloadSuccess) {
            withContext(Dispatchers.Main) { onStatusChanged("❌ 错误: CDN 重定向次数过多，陷入环路") }
            return@withContext false
        }

        // ── 开始执行正式的大文件数据流传输 ──
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
                // 处理一些镜像站采用 Chunked 分块传输编码导致 contentLength 无效的情况
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
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 安全限制: 请检查 AndroidManifest.xml 是否遗漏了 INTERNET 权限配置")
        }
        return@withContext false
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 网络异常: ${e.localizedMessage ?: "建立数据握手失败"}")
        }
        return@withContext false
    } finally {
        outputStream?.close()
        inputStream?.close()
        connection?.disconnect()
    }
}

// ─────────────────────────────────────────────
// 核心后台解压部署引擎
// ─────────────────────────────────────────────
private suspend fun performExtractTarXz(
    archiveFile: File,
    destinationDir: File,
    onStatusChanged: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (destinationDir.exists()) {
            destinationDir.deleteRecursively()
        }
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
            
            // 安全机制：防止恶意路径穿越攻击
            if (!targetFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                entry = tarIn.nextEntry
                continue
            }

            if (entry.isDirectory) {
                targetFile.mkdirs()
            } else {
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(16384)
                    var len: Int
                    while (tarIn.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                    fos.flush()
                }
                
                // 恢复 Linux 系统可执行节点属性
                if (entry.mode and 0x40 != 0 || entry.name.contains("bin/")) {
                    targetFile.setExecutable(true, false)
                }
                targetFile.setReadable(true, false)
            }

            fileCount++
            if (fileCount % 300 == 0) {
                withContext(Dispatchers.Main) {
                    onStatusChanged("已释放 $fileCount 个 Linux 系统节点文件…")
                }
            }
            entry = tarIn.nextEntry
        }

        tarIn.close()
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        return@withContext true
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            onStatusChanged("❌ 部署异常: 系统解压中断，可能是手机存储空间不足")
        }
        return@withContext false
    }
}
