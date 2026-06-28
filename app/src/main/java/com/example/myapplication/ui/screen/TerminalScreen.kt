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
    var currentStatusMessage by remember { mutableStateOf("正在初始化环境…") }

    // ── 终端控制台流 ──
    val terminalLines = remember { mutableStateListOf<String>() }
    var currentInput by remember { mutableStateOf("") }

    // ── 核心路径定义 ──
    val rootfsDir = remember { File(context.filesDir, "debian_rootfs") }
    val tarXzFile = remember { File(context.cacheDir, "rootfs.tar.xz") }
    val imageUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260627_14%3A22/rootfs.tar.xz"

    // ── 终端样式配置 ──
    val terminalBackground = Color(0xFF000000)
    val terminalTextColor = Color(0xFF00FF00)

    // ── 启动时自动检测 Debian 基础目录是否健全 ──
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 通过检测 /bin/sh 是否存在，来判断基础系统是否成功解压部署过
            val shExec = File(rootfsDir, "bin/sh")
            if (shExec.exists() && rootfsDir.isDirectory) {
                envState = EnvironmentState.Ready
                terminalLines.add("Welcome to Debian GNU/Linux 12 (bookworm) via PRoot!")
                terminalLines.add("System architecture: aarch64 (Android sandboxed)")
                terminalLines.add("Debian rootfs detected successfully.")
                terminalLines.add("")
            } else {
                envState = EnvironmentState.NotInstalled
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ─────────────────────────────────────────────
        // 1. 标准控制台 UI 渲染
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
        // 2. 弹窗层：未安装时的提示引导弹窗
        // ─────────────────────────────────────────────
        if (envState == EnvironmentState.NotInstalled) {
            AlertDialog(
                onDismissRequest = { /* 强力禁止点击外部关闭 */ },
                confirmButton = {
                    Button(
                        onClick = {
                            envState = EnvironmentState.Downloading
                            coroutineScope.launch {
                                val downloadSuccess = performDownloadRootfs(
                                    context = context,
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
                                        currentStatusMessage = "❌ 解压失败，请检查存储空间后重试"
                                    }
                                } else {
                                    envState = EnvironmentState.NotInstalled
                                    currentStatusMessage = "❌ 下载失败，请检查网络连接后重试"
                                }
                            }
                        }
                    ) {
                        Text("立即下载并部署")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { /* 可在此处引导退回至 Projects Tab 等逻辑 */ }) {
                        Text("暂不配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                title = { Text("配置 Linux 开发运行环境", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = "检测到应用尚未安装 Debian Linux 系统容器。运行多语言代码、格式化程序以及启动高级 LSP 服务需要下载并解压大约 90MB 的核心基础包。\n\n建议在 Wi-Fi 网络环境下进行该操作。",
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                },
                shape = RoundedCornerShape(28.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        }

        // ─────────────────────────────────────────────
        // 3. 弹窗层：下载或解压过程中的不可取消进度弹窗
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
                                strokeCap = ProgressIndicatorDefaults.StrokeCap
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
// 核心后台业务层：全线程安全的网络下载器
// ─────────────────────────────────────────────
private suspend fun performDownloadRootfs(
    context: Context,
    downloadUrl: String,
    targetFile: File,
    onProgress: (Float) -> Unit,
    onStatusChanged: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    var inputStream: BufferedInputStream? = null
    var outputStream: FileOutputStream? = null

    try {
        if (targetFile.exists()) {
            targetFile.delete() // 确保清理旧的残余下载文件
        }
        
        onStatusChanged("正在建立数据网络连接…")
        val url = URL(downloadUrl)
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            return@withContext false
        }

        val fileLength = connection.contentLengthLong
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
                // 实时换算为 MB 单位显示给用户
                val readMb = String.format("%.1f", totalBytesRead.toFloat() / (1024 * 1024))
                val totalMb = String.format("%.1f", fileLength.toFloat() / (1024 * 1024))
                
                withContext(Dispatchers.Main) {
                    onStatusChanged("已下载 $readMb MB / $totalMb MB")
                    onProgress(progress)
                }
            }
        }
        outputStream.flush()
        return@withContext true
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    } finally {
        outputStream?.close()
        inputStream?.close()
        connection?.disconnect()
    }
}

// ─────────────────────────────────────────────
// 核心后台业务层：.tar.xz 强力流解压部署引擎
// ─────────────────────────────────────────────
private suspend fun performExtractTarXz(
    archiveFile: File,
    destinationDir: File,
    onStatusChanged: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    try {
        if (destinationDir.exists()) {
            destinationDir.deleteRecursively() // 重新干净地进行根文件目录生成
        }
        destinationDir.mkdirs()

        onStatusChanged("正在解密 XZ 压缩矩阵…")
        // 使用引入的 XZInputStream 解码外层的 .xz 数据
        val fileIn = archiveFile.inputStream()
        val bufferedIn = BufferedInputStream(fileIn, 16384)
        val xzIn = XZInputStream(bufferedIn)

        // 串联 TarArchiveInputStream 拆解内层的 .tar 归档结构
        val tarIn = TarArchiveInputStream(xzIn)
        var entry = tarIn.nextEntry
        var fileCount = 0

        while (entry != null) {
            val targetFile = File(destinationDir, entry.name)
            
            // 安全防御防护：防止 Zip Slip 漏洞（恶意路径穿越攻击）
            if (!targetFile.canonicalPath.startsWith(destinationDir.canonicalPath)) {
                entry = tarIn.nextEntry
                continue
            }

            if (entry.isDirectory) {
                targetFile.mkdirs()
            } else {
                // 确保父目录一定存在
                targetFile.parentFile?.mkdirs()
                
                // 输出文件数据
                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(16384)
                    var len: Int
                    while (tarIn.read(buffer).also { len = it } != -1) {
                        fos.write(buffer, 0, len)
                    }
                    fos.flush()
                }
                
                // 恢复 Linux 二进制基础的可执行权限属性（极其重要！）
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
        // 系统部署成功后，彻底销毁高占用的本地临时 tar.xz 压缩包，释放宝贵的手机存储空间
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        return@withContext true
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext false
    }
}
