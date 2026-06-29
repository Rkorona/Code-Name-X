package io.axiom.editor.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import io.axiom.editor.ui.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─────────────────────────────────────────────
// 内部数据模型
// ─────────────────────────────────────────────
private data class SheetFileNode(
    val name: String,
    val path: String,           // 绝对路径 或 SAF content document URI 字符串
    val isDirectory: Boolean,
    val extension: String,
    val children: List<SheetFileNode> = emptyList()
)

private data class SheetDisplayRow(
    val node: SheetFileNode,
    val depth: Int
)

private sealed class SheetLoadState {
    object Loading : SheetLoadState()
    data class Loaded(val root: SheetFileNode) : SheetLoadState()
    data class Error(val message: String) : SheetLoadState()
}

private data class SheetContextAction(
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false,
    val forFolder: Boolean? = null
)

private val SHEET_CONTEXT_ACTIONS = listOf(
    SheetContextAction("重命名",       Icons.Outlined.Edit),
    SheetContextAction("新建文件",     Icons.Outlined.NoteAdd,          forFolder = true),
    SheetContextAction("新建文件夹",   Icons.Outlined.CreateNewFolder,  forFolder = true),
    SheetContextAction("在此打开终端", Icons.Outlined.Terminal,         forFolder = true),
    SheetContextAction("复制路径",     Icons.Outlined.ContentCopy),
    SheetContextAction("删除",         Icons.Outlined.Delete,           isDestructive = true),
)

// ─────────────────────────────────────────────
// 文件系统工具函数
// ─────────────────────────────────────────────
private fun getMimeTypeForFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "application/octet-stream"
    val mimeFromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return mimeFromMap ?: when (extension) {
        "kt", "kts"          -> "text/x-kotlin"
        "gradle"             -> "text/x-groovy"
        "toml"               -> "text/toml"
        "yaml", "yml"        -> "text/yaml"
        "sh"                 -> "text/x-sh"
        "bat"                -> "text/x-bat"
        "c", "cpp", "cc"     -> "text/x-c"
        "h", "hpp"           -> "text/x-c-header"
        "rs"                 -> "text/x-rust"
        "go"                 -> "text/x-go"
        "swift"              -> "text/x-swift"
        "dart"               -> "text/x-dart"
        "rb"                 -> "text/x-ruby"
        "php"                -> "text/x-php"
        "vue"                -> "text/x-vue"
        "svelte"             -> "text/x-svelte"
        "lock"               -> "text/plain"
        "db"                 -> "application/x-sqlite3"
        else                 -> "application/octet-stream"
    }
}

private fun getDisplayNameFromUri(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
    } catch (e: Exception) {
        uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
    }
}

private fun safCreateFile(context: Context, dirPath: String, fileName: String): Pair<Boolean, String?> {
    return try {
        val dirUri = Uri.parse(dirPath)
        val mimeType = getMimeTypeForFileName(fileName)
        val newDocUri = DocumentsContract.createDocument(
            context.contentResolver,
            dirUri,
            mimeType,
            fileName
        )
        if (newDocUri != null) Pair(true, null)
        else Pair(false, "系统未返回新文件 URI，创建可能失败")
    } catch (e: Exception) {
        Pair(false, "创建文件失败：${e.localizedMessage}")
    }
}

private fun safCreateDirectory(context: Context, dirPath: String, dirName: String): Pair<Boolean, String?> {
    return try {
        val dirUri = Uri.parse(dirPath)
        val newDocUri = DocumentsContract.createDocument(
            context.contentResolver,
            dirUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        )
        if (newDocUri != null) Pair(true, null)
        else Pair(false, "系统未返回新目录 URI，创建可能失败")
    } catch (e: Exception) {
        Pair(false, "创建文件夹失败：${e.localizedMessage}")
    }
}

private fun safRename(context: Context, docPath: String, newName: String): Pair<Boolean, String?> {
    return try {
        val docUri = Uri.parse(docPath)
        @Suppress("DEPRECATION")
        val newUri = DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
        if (newUri != null) Pair(true, null)
        else Pair(false, "系统未返回重命名后 URI，操作可能失败")
    } catch (e: Exception) {
        Pair(false, "重命名失败：${e.localizedMessage}")
    }
}

private fun safDelete(context: Context, docPath: String): Pair<Boolean, String?> {
    return try {
        val docUri = Uri.parse(docPath)
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, docUri)
        if (deleted) Pair(true, null)
        else Pair(false, "系统返回删除失败（可能是只读或系统文件）")
    } catch (e: Exception) {
        Pair(false, "删除失败：${e.localizedMessage}")
    }
}

private fun fileCreateFile(dirPath: String, fileName: String): Pair<Boolean, String?> {
    return try {
        val newFile = File(dirPath, fileName)
        if (newFile.exists()) return Pair(false, "同名文件已存在")
        val created = newFile.createNewFile()
        if (created) Pair(true, null) else Pair(false, "文件创建失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "创建文件失败：${e.localizedMessage}")
    }
}

private fun fileCreateDirectory(dirPath: String, dirName: String): Pair<Boolean, String?> {
    return try {
        val newDir = File(dirPath, dirName)
        if (newDir.exists()) return Pair(false, "同名文件夹已存在")
        val created = newDir.mkdir()
        if (created) Pair(true, null) else Pair(false, "文件夹创建失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "创建文件夹失败：${e.localizedMessage}")
    }
}

private fun fileRename(filePath: String, newName: String): Pair<Boolean, String?> {
    return try {
        val file = File(filePath)
        val parent = file.parentFile ?: return Pair(false, "无法确定父目录")
        val newFile = File(parent, newName)
        if (newFile.exists()) return Pair(false, "同名文件已存在")
        val renamed = file.renameTo(newFile)
        if (renamed) Pair(true, null) else Pair(false, "重命名失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "重命名失败：${e.localizedMessage}")
    }
}

private fun fileDelete(filePath: String): Pair<Boolean, String?> {
    return try {
        val file = File(filePath)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) Pair(true, null) else Pair(false, "删除失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "删除失败：${e.localizedMessage}")
    }
}

private fun copyUriToDirectory(context: Context, sourceUri: Uri, targetDirPath: String, fileName: String): Pair<Boolean, String?> {
    return try {
        val mimeType = getMimeTypeForFileName(fileName)
        if (targetDirPath.startsWith("content://")) {
            val targetDirUri = Uri.parse(targetDirPath)
            val newDocUri = DocumentsContract.createDocument(
                context.contentResolver,
                targetDirUri,
                mimeType,
                fileName
            ) ?: return Pair(false, "无法在目标目录中创建文件")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(newDocUri)?.use { output ->
                    input.copyTo(output)
                } ?: return Pair(false, "无法打开目标文件输出流")
            } ?: return Pair(false, "无法打开源文件输入流")
            Pair(true, null)
        } else {
            val targetFile = File(targetDirPath, fileName)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return Pair(false, "无法打开源文件输入流")
            Pair(true, null)
        }
    } catch (e: Exception) {
        Pair(false, "复制文件失败：${e.localizedMessage}")
    }
}

private fun createProjectZipToDownloads(context: Context, projectLocalPath: String, projectName: String): Pair<Boolean, String?> {
    return try {
        val zipFileName = "${projectName}_${System.currentTimeMillis()}.zip"
        val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val insertUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Pair(false, "无法在下载目录创建文件")
            context.contentResolver.openOutputStream(insertUri) ?: return Pair(false, "无法打开输出流")
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            File(downloadsDir, zipFileName).outputStream()
        }

        ZipOutputStream(outputStream.buffered()).use { zip ->
            if (projectLocalPath.startsWith("content://")) {
                val treeUri = Uri.parse(projectLocalPath)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return Pair(false, "无法访问项目目录")
                addSafDocToZip(context, rootDoc, zip, projectName)
            } else {
                val rootFile = File(projectLocalPath)
                if (!rootFile.exists()) return Pair(false, "项目目录不存在")
                addFileToZip(rootFile, zip, projectName)
            }
        }
        Pair(true, null)
    } catch (e: Exception) {
        Pair(false, "压缩失败：${e.localizedMessage}")
    }
}

private fun addSafDocToZip(context: Context, doc: DocumentFile, zip: ZipOutputStream, pathInZip: String) {
    val isDir = doc.isDirectory || doc.type == DocumentsContract.Document.MIME_TYPE_DIR
    if (isDir) {
        val dirEntry = ZipEntry("$pathInZip/")
        zip.putNextEntry(dirEntry)
        zip.closeEntry()
        doc.listFiles().filter { it.name != null }.forEach { child ->
            val childName = child.name ?: return@forEach
            addSafDocToZip(context, child, zip, "$pathInZip/$childName")
        }
    } else {
        val entry = ZipEntry(pathInZip)
        zip.putNextEntry(entry)
        try {
            context.contentResolver.openInputStream(doc.uri)?.use { input -> input.copyTo(zip) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        zip.closeEntry()
    }
}

private fun addFileToZip(file: File, zip: ZipOutputStream, pathInZip: String) {
    if (file.isDirectory) {
        val dirEntry = ZipEntry("$pathInZip/")
        zip.putNextEntry(dirEntry)
        zip.closeEntry()
        val children = file.listFiles() ?: emptyArray()
        for (child in children) {
            addFileToZip(child, zip, "$pathInZip/${child.name}")
        }
    } else {
        val entry = ZipEntry(pathInZip)
        zip.putNextEntry(entry)
        try {
            file.inputStream().use { input -> input.copyTo(zip) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        zip.closeEntry()
    }
}

private fun openTerminalAt(context: Context, dirPath: String): Pair<Boolean, String> {
    val fsPath: String = if (dirPath.startsWith("content://")) {
        val uri = try { Uri.parse(dirPath) } catch (e: Exception) { null }
        val lastSeg = uri?.lastPathSegment
        if (lastSeg != null) {
            val relative = lastSeg.substringAfter(':')
            if (relative.isNotBlank()) "/storage/emulated/0/$relative" else ""
        } else ""
    } else {
        dirPath
    }

    if (fsPath.isBlank()) {
        return Pair(false, "SAF 路径无法直接传递给终端，请使用本地路径导入的项目")
    }

    val termuxPackage = "com.termux"
    val pm = context.packageManager
    val termuxInstalled = try { pm.getPackageInfo(termuxPackage, 0); true } catch (e: Exception) { false }

    if (termuxInstalled) {
        val runCmdSuccess = try {
            val runCmdIntent = Intent().apply {
                setClassName(termuxPackage, "$termuxPackage.app.RunCommandService")
                action = "$termuxPackage.RUN_COMMAND"
                putExtra("$termuxPackage.RUN_COMMAND_PATH", "/data/data/$termuxPackage/files/usr/bin/bash")
                putExtra("$termuxPackage.RUN_COMMAND_WORKDIR", fsPath)
                putExtra("$termuxPackage.RUN_COMMAND_TERMINAL", true)
            }
            context.startService(runCmdIntent)
            true
        } catch (e: Exception) {
            false
        }

        if (runCmdSuccess) return Pair(true, "已在 Termux 中打开目录：$fsPath")

        return try {
            val cdCommand = "cd \"$fsPath\""
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal_cd", cdCommand))
            val launchIntent = pm.getLaunchIntentForPackage(termuxPackage)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(launchIntent)
            Pair(true, "cd 命令已复制，打开 Termux 后请长按粘贴执行")
        } catch (e: Exception) {
            Pair(false, "启动 Termux 失败：${e.localizedMessage}")
        }
    }

    val androidTerminalPkg = "com.android.terminal"
    val androidTerminalInstalled = try { pm.getPackageInfo(androidTerminalPkg, 0); true } catch (e: Exception) { false }

    if (androidTerminalInstalled) {
        return try {
            val intent = pm.getLaunchIntentForPackage(androidTerminalPkg)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (intent != null) {
                context.startActivity(intent)
                Pair(true, "已打开系统终端（请手动 cd）")
            } else Pair(false, "无法启动系统终端")
        } catch (e: Exception) {
            Pair(false, "启动系统终端失败")
        }
    }
    return Pair(false, "未找到终端 App，请先从 F-Droid 或商店安装 Termux")
}

private fun validateFileName(name: String): String? {
    if (name.isBlank()) return "名称不能为空"
    if (name.length > 255) return "名称不能超过 255 字符"
    if (name.contains('/') || name.contains('\\')) return "名称中不能包含路径分隔符"
    if (name == "." || name == "..") return "名称不能为 . 或 .."
    val invalidChars = listOf(':', '*', '?', '"', '<', '>', '|')
    for (ch in invalidChars) {
        if (name.contains(ch)) return "名称中不能包含非法字符 $ch"
    }
    return null
}

// ─────────────────────────────────────────────
// 主入口：底部弹出文件树 Sheet
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerSheet(
    project: Project,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var reloadTrigger by remember { mutableIntStateOf(0) }
    var loadState by remember(project.localPath) { mutableStateOf<SheetLoadState>(SheetLoadState.Loading) }
    var expandedPaths by remember(project.localPath) { mutableStateOf(setOf(project.localPath ?: "")) }
    var rootDocPath by remember(project.localPath) { mutableStateOf("") }
    var openingFile by remember { mutableStateOf(false) }
    // 懒加载状态
    var childrenCache by remember(project.localPath) { mutableStateOf<Map<String, List<SheetFileNode>>>(emptyMap()) }
    var loadingDirs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var safTreeUri by remember(project.localPath) { mutableStateOf<Uri?>(null) }

    var contextNode by remember { mutableStateOf<SheetFileNode?>(null) }
    var showContextSheet by remember { mutableStateOf(false) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showRenameDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var dialogTargetNode by remember { mutableStateOf<SheetFileNode?>(null) }
    var newItemTargetPath by remember { mutableStateOf("") }

    var isOperationInProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showActionsMenu by remember { mutableStateOf(false) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
        if (targetDir.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("无法确定目标目录，上传取消") }
            return@rememberLauncherForActivityResult
        }
        isOperationInProgress = true
        scope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            for (uri in uris) {
                val fileName = getDisplayNameFromUri(context, uri)
                val (ok, _) = copyUriToDirectory(context, uri, targetDir, fileName)
                if (ok) successCount++ else failCount++
            }
            withContext(Dispatchers.Main) {
                isOperationInProgress = false
                reloadTrigger++
                snackbarHostState.showSnackbar("已成功上传 $successCount 个文件，失败 $failCount 个")
            }
        }
    }

    LaunchedEffect(project.localPath, reloadTrigger) {
        loadState = SheetLoadState.Loading
        childrenCache = emptyMap()
        val path = project.localPath
        if (path.isNullOrBlank()) {
            loadState = SheetLoadState.Error("该项目没有关联本地路径")
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root: SheetFileNode = if (path.startsWith("content://")) {
                    val treeUri = Uri.parse(path)
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@runCatching SheetLoadState.Error("无法访问该目录，权限可能已失效")
                    buildSheetNodeFromSaf(context, rootDoc)
                } else {
                    val file = File(path)
                    if (!file.exists()) return@runCatching SheetLoadState.Error("目录不存在")
                    buildSheetNodeFromFile(file)
                }
                SheetLoadState.Loaded(root)
            }.getOrElse { e ->
                SheetLoadState.Error("加载失败：${e.localizedMessage}")
            }
        }
        if (result is SheetLoadState.Loaded) {
            rootDocPath = result.root.path
            expandedPaths = setOf(result.root.path)
            if (path.startsWith("content://")) safTreeUri = Uri.parse(path)
        }
        loadState = result
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (project.description.isNotBlank()) {
                            Text(
                                text = project.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 4.dp,
                            tonalElevation = 0.dp
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建文件") },
                                leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                                onClick = {
                                    showActionsMenu = false
                                    val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                                    if (targetDir.isNotBlank()) { newItemTargetPath = targetDir; showNewFileDialog = true }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("新建文件夹") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                                onClick = {
                                    showActionsMenu = false
                                    val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                                    if (targetDir.isNotBlank()) { newItemTargetPath = targetDir; showNewFolderDialog = true }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("上传文件") },
                                leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                                onClick = {
                                    showActionsMenu = false
                                    val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                                    if (targetDir.isNotBlank()) uploadLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("下载项目") },
                                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                onClick = {
                                    showActionsMenu = false
                                    val srcPath = project.localPath ?: ""
                                    if (srcPath.isNotBlank()) {
                                        isOperationInProgress = true
                                        scope.launch(Dispatchers.IO) {
                                            val (success, error) = createProjectZipToDownloads(context, srcPath, project.name)
                                            withContext(Dispatchers.Main) {
                                                isOperationInProgress = false
                                                snackbarHostState.showSnackbar(if (success) "已打包并保存到下载目录" else "下载失败：$error")
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val state = loadState) {
                        is SheetLoadState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is SheetLoadState.Error -> {
                            Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.SentimentDissatisfied, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { reloadTrigger++ }) { Text("重试") }
                            }
                        }
                        is SheetLoadState.Loaded -> {
                            val displayRows = remember(state.root, expandedPaths, childrenCache) {
                                flattenSheetVisible(state.root, depth = 0, expanded = expandedPaths, childrenCache = childrenCache)
                            }

                            if (displayRows.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("项目为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                                    items(displayRows, key = { it.node.path }) { row ->
                                        val isExpanded = row.node.path in expandedPaths
                                        SheetFileTreeRow(
                                            row = row,
                                            isExpanded = isExpanded,
                                            isLoading = row.node.path in loadingDirs,
                                            onClick = {
                                                if (row.node.isDirectory) {
                                                    if (isExpanded) {
                                                        expandedPaths = expandedPaths - row.node.path
                                                    } else {
                                                        expandedPaths = expandedPaths + row.node.path
                                                        val alreadyLoaded = row.node.path in childrenCache || row.node.children.isNotEmpty()
                                                        if (!alreadyLoaded && row.node.path !in loadingDirs) {
                                                            loadingDirs = loadingDirs + row.node.path
                                                            scope.launch {
                                                                val children = withContext(Dispatchers.IO) {
                                                                    val tUri = safTreeUri
                                                                    if (tUri != null) loadSheetSafChildren(context, tUri, row.node.path)
                                                                    else loadSheetFileChildren(row.node.path)
                                                                }
                                                                childrenCache = childrenCache + (row.node.path to children)
                                                                loadingDirs = loadingDirs - row.node.path
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    if (!openingFile) {
                                                        openingFile = true
                                                        onOpenFile(row.node.path)
                                                        openingFile = false
                                                    }
                                                }
                                            },
                                            onContextMenu = {
                                                contextNode = row.node
                                                showContextSheet = true
                                            }
                                        )
                                    }
                                }
                            }

                            if (openingFile || isOperationInProgress) {
                                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }

            }
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }

    if (showContextSheet) {
        val node = contextNode ?: run { showContextSheet = false; return }
        ModalBottomSheet(onDismissRequest = { showContextSheet = false }, sheetState = contextSheetState) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                ListItem(
                    headlineContent = { Text(node.name, fontWeight = FontWeight.SemiBold) },
                    leadingContent = {
                        if (node.isDirectory) Icon(Icons.Outlined.Folder, null, tint = Color(0xFFFFB74D))
                        else SheetFileExtBadge(node.extension)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SHEET_CONTEXT_ACTIONS.filter { action ->
                    when (action.forFolder) {
                        true -> node.isDirectory
                        false -> !node.isDirectory
                        null -> true
                    }
                }.forEach { action ->
                    ListItem(
                        headlineContent = { Text(action.label, color = if (action.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                        leadingContent = { Icon(action.icon, null, tint = if (action.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable {
                            scope.launch { contextSheetState.hide() }
                            showContextSheet = false
                            when (action.label) {
                                "重命名" -> { dialogTargetNode = node; showRenameDialog = true }
                                "新建文件" -> { newItemTargetPath = node.path; showNewFileDialog = true }
                                "新建文件夹" -> { newItemTargetPath = node.path; showNewFolderDialog = true }
                                "在此打开终端" -> {
                                    val (_, msg) = openTerminalAt(context, node.path)
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                                "复制路径" -> {
                                    val pathStr = if (node.path.startsWith("content://")) {
                                        val uri = Uri.parse(node.path)
                                        val lastSeg = uri.lastPathSegment ?: ""
                                        val relativePart = lastSeg.substringAfter(':')
                                        if (relativePart.isNotBlank()) "/storage/emulated/0/$relativePart" else node.path
                                    } else node.path
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("path", pathStr))
                                    scope.launch { snackbarHostState.showSnackbar("路径已复制") }
                                }
                                "删除" -> { dialogTargetNode = node; showDeleteDialog = true }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        val target = dialogTargetNode ?: run { showRenameDialog = false; return }
        SheetInputDialog(title = "重命名", label = "新名称", initialText = target.name, confirmLabel = "重命名", onConfirm = { newName ->
            showRenameDialog = false
            isOperationInProgress = true
            scope.launch(Dispatchers.IO) {
                val (success, error) = if (target.path.startsWith("content://")) safRename(context, target.path, newName) else fileRename(target.path, newName)
                withContext(Dispatchers.Main) {
                    isOperationInProgress = false
                    if (success) { reloadTrigger++; snackbarHostState.showSnackbar("重命名成功") } else snackbarHostState.showSnackbar(error ?: "重命名失败")
                }
            }
        }, onDismiss = { showRenameDialog = false })
    }

    if (showNewFileDialog) {
        SheetInputDialog(title = "新建文件", label = "文件名", placeholder = "main.js", onConfirm = { name ->
            showNewFileDialog = false
            isOperationInProgress = true
            val targetDir = newItemTargetPath.ifBlank { rootDocPath }
            scope.launch(Dispatchers.IO) {
                val (success, error) = if (targetDir.startsWith("content://")) safCreateFile(context, targetDir, name) else fileCreateFile(targetDir, name)
                withContext(Dispatchers.Main) {
                    isOperationInProgress = false
                    if (success) { reloadTrigger++; snackbarHostState.showSnackbar("文件创建成功") } else snackbarHostState.showSnackbar(error ?: "文件创建失败")
                }
            }
        }, onDismiss = { showNewFileDialog = false })
    }

    if (showNewFolderDialog) {
        SheetInputDialog(title = "新建文件夹", label = "文件夹名", placeholder = "src", onConfirm = { name ->
            showNewFolderDialog = false
            isOperationInProgress = true
            val targetDir = newItemTargetPath.ifBlank { rootDocPath }
            scope.launch(Dispatchers.IO) {
                val (success, error) = if (targetDir.startsWith("content://")) safCreateDirectory(context, targetDir, name) else fileCreateDirectory(targetDir, name)
                withContext(Dispatchers.Main) {
                    isOperationInProgress = false
                    if (success) { reloadTrigger++; snackbarHostState.showSnackbar("文件夹创建成功") } else snackbarHostState.showSnackbar(error ?: "文件夹创建失败")
                }
            }
        }, onDismiss = { showNewFolderDialog = false })
    }

    if (showDeleteDialog) {
        val target = dialogTargetNode ?: run { showDeleteDialog = false; return }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除") },
            text = { Text("将永久删除「${target.name}」，此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        isOperationInProgress = true
                        scope.launch(Dispatchers.IO) {
                            val (success, error) = if (target.path.startsWith("content://")) safDelete(context, target.path) else fileDelete(target.path)
                            withContext(Dispatchers.Main) {
                                isOperationInProgress = false
                                if (success) { reloadTrigger++; snackbarHostState.showSnackbar("已成功删除") } else snackbarHostState.showSnackbar(error ?: "删除失败")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SheetFileTreeRow(row: SheetDisplayRow, isExpanded: Boolean, isLoading: Boolean, onClick: () -> Unit, onContextMenu: () -> Unit) {
    val node = row.node
    val indentDp = (row.depth * 18 + 8).dp
    val chevronDeg by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f, label = "chevron_${node.path}")

    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = indentDp, end = 4.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (node.isDirectory) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.width(4.dp))
            } else {
                Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp).rotate(chevronDeg), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                Spacer(Modifier.width(4.dp))
            }
            Icon(imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color(0xFF7C9CBF))
        } else {
            Spacer(Modifier.width(22.dp))
            SheetFileExtBadge(node.extension)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = node.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontFamily = if (!node.isDirectory) FontFamily.Monospace else FontFamily.Default)
        IconButton(onClick = onContextMenu, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun SheetFileExtBadge(extension: String) {
    val bg = sheetFileIconColor(extension)
    Box(modifier = Modifier.size(width = 28.dp, height = 20.dp).clip(RoundedCornerShape(4.dp)).background(bg.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(text = extension.take(3).uppercase().ifBlank { "?" }, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = bg, maxLines = 1)
    }
}

@Composable
private fun SheetInputDialog(title: String, label: String, initialText: String = "", placeholder: String = "", confirmLabel: String = "确定", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = text.isNotEmpty() && validateFileName(text) != null,
                supportingText = {
                    val err = if (text.isNotEmpty()) validateFileName(text) else null
                    if (err != null) Text(err, color = MaterialTheme.colorScheme.error)
                }
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank() && validateFileName(text) == null) onConfirm(text.trim()) }, enabled = text.isNotBlank() && validateFileName(text) == null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun buildSheetNodeFromFile(file: File): SheetFileNode {
    if (!file.isDirectory) return SheetFileNode(file.name, file.absolutePath, false, file.extension.lowercase())
    val children = (file.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { child ->
            SheetFileNode(child.name, child.absolutePath, child.isDirectory, if (child.isDirectory) "" else child.extension.lowercase())
        }
    return SheetFileNode(name = file.name, path = file.absolutePath, isDirectory = true, extension = "", children = children)
}

private fun buildSheetNodeFromSaf(context: Context, doc: DocumentFile): SheetFileNode {
    val name = doc.name ?: "未知"
    val isDir = doc.isDirectory || doc.type == DocumentsContract.Document.MIME_TYPE_DIR
    if (!isDir) return SheetFileNode(name, doc.uri.toString(), false, name.substringAfterLast('.', "").lowercase())
    val children = doc.listFiles()
        .filter { it.name != null }
        .sortedWith(compareBy({ !(it.isDirectory || it.type == DocumentsContract.Document.MIME_TYPE_DIR) }, { it.name?.lowercase() ?: "" }))
        .map { child ->
            val cName = child.name ?: "未知"
            val cIsDir = child.isDirectory || child.type == DocumentsContract.Document.MIME_TYPE_DIR
            SheetFileNode(cName, child.uri.toString(), cIsDir, if (cIsDir) "" else cName.substringAfterLast('.', "").lowercase())
        }
    return SheetFileNode(name = name, path = doc.uri.toString(), isDirectory = true, extension = "", children = children)
}

private fun loadSheetFileChildren(path: String): List<SheetFileNode> {
    val file = File(path)
    return (file.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { child ->
            SheetFileNode(child.name, child.absolutePath, child.isDirectory, if (child.isDirectory) "" else child.extension.lowercase())
        }
}

private fun loadSheetSafChildren(context: Context, treeUri: Uri, docPath: String): List<SheetFileNode> {
    return try {
        val docUri = Uri.parse(docPath)
        val docId = DocumentsContract.getDocumentId(docUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val docFile = DocumentFile.fromTreeUri(context, treeDocUri) ?: return emptyList()
        docFile.listFiles()
            .filter { it.name != null }
            .sortedWith(compareBy({ !(it.isDirectory || it.type == DocumentsContract.Document.MIME_TYPE_DIR) }, { it.name?.lowercase() ?: "" }))
            .map { child ->
                val cName = child.name ?: "未知"
                val cIsDir = child.isDirectory || child.type == DocumentsContract.Document.MIME_TYPE_DIR
                SheetFileNode(cName, child.uri.toString(), cIsDir, if (cIsDir) "" else cName.substringAfterLast('.', "").lowercase())
            }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun flattenSheetVisible(node: SheetFileNode, depth: Int, expanded: Set<String>, childrenCache: Map<String, List<SheetFileNode>>): List<SheetDisplayRow> {
    val children = childrenCache[node.path] ?: node.children
    if (depth == 0) {
        if (node.path !in expanded) return emptyList()
        return children.flatMap { flattenSheetVisible(it, 1, expanded, childrenCache) }
    }
    val selfRow = SheetDisplayRow(node, depth)
    return if (node.isDirectory && node.path in expanded) {
        listOf(selfRow) + children.flatMap { flattenSheetVisible(it, depth + 1, expanded, childrenCache) }
    } else listOf(selfRow)
}

private fun sheetFileIconColor(extension: String): Color = when (extension) {
    "js", "jsx", "mjs"            -> Color(0xFFF5C518)
    "ts", "tsx"                    -> Color(0xFF3178C6)
    "py"                           -> Color(0xFF3572A5)
    "kt", "kts"                    -> Color(0xFF7F52FF)
    "java"                         -> Color(0xFFB07219)
    "dart"                         -> Color(0xFF00B4AB)
    "html", "htm"                  -> Color(0xFFE34C26)
    "css", "scss", "sass", "less"  -> Color(0xFF563D7C)
    "json", "jsonc"                -> Color(0xFF40BF40)
    "md", "markdown"               -> Color(0xFF888888)
    "xml", "svg"                   -> Color(0xFFE07020)
    "sh", "bash", "zsh"            -> Color(0xFF89E051)
    "go"                           -> Color(0xFF00ADD8)
    "rs"                           -> Color(0xFFDEA584)
    "cpp", "cc", "cxx", "c", "h"  -> Color(0xFF555599)
    "rb"                           -> Color(0xFFCC342D)
    "php"                          -> Color(0xFF4F5D95)
    "swift"                        -> Color(0xFFFF6940)
    else                           -> Color(0xFF9E9E9E)
}