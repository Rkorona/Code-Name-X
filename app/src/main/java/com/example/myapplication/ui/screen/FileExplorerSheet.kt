package com.example.myapplication.ui.screen

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.myapplication.ui.model.Project
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

// ─────────────────────────────────────────────
// 上下文菜单 Action 定义
// ─────────────────────────────────────────────

private data class SheetContextAction(
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false,
    val forFolder: Boolean? = null   // null=通用  true=仅文件夹  false=仅文件
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

/** 根据文件名推断 MIME 类型，未知类型返回 application/octet-stream */
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

/**
 * 从 content:// URI 查询原始文件名（OpenableColumns.DISPLAY_NAME）
 * 查询失败则从 URI 最后一段提取
 */
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

/**
 * 在 SAF 目录中创建文件（通过 DocumentsContract.createDocument）。
 * dirPath: 目标目录的 document URI 字符串
 * fileName: 新文件名
 * 返回：(成功, 错误信息)
 */
private fun safCreateFile(
    context: Context,
    dirPath: String,
    fileName: String
): Pair<Boolean, String?> {
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

/**
 * 在 SAF 目录中创建子文件夹。
 * dirPath: 目标目录的 document URI 字符串
 * dirName: 新文件夹名
 */
private fun safCreateDirectory(
    context: Context,
    dirPath: String,
    dirName: String
): Pair<Boolean, String?> {
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

/**
 * 通过 SAF 重命名文件或文件夹（DocumentsContract.renameDocument）。
 * docPath: 目标 document URI 字符串
 */
private fun safRename(
    context: Context,
    docPath: String,
    newName: String
): Pair<Boolean, String?> {
    return try {
        val docUri = Uri.parse(docPath)
        // renameDocument 在 API 21+ 可用；返回新 URI 或 null
        @Suppress("DEPRECATION")
        val newUri = DocumentsContract.renameDocument(
            context.contentResolver,
            docUri,
            newName
        )
        if (newUri != null) Pair(true, null)
        else Pair(false, "系统未返回重命名后 URI，操作可能失败")
    } catch (e: Exception) {
        Pair(false, "重命名失败：${e.localizedMessage}")
    }
}

/**
 * 通过 SAF 删除文件或文件夹（DocumentsContract.deleteDocument）。
 * ExternalStorage provider 会递归删除非空目录。
 */
private fun safDelete(
    context: Context,
    docPath: String
): Pair<Boolean, String?> {
    return try {
        val docUri = Uri.parse(docPath)
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, docUri)
        if (deleted) Pair(true, null)
        else Pair(false, "系统返回删除失败（可能是只读或系统文件）")
    } catch (e: Exception) {
        Pair(false, "删除失败：${e.localizedMessage}")
    }
}

/**
 * 在绝对路径目录中创建新文件。
 */
private fun fileCreateFile(dirPath: String, fileName: String): Pair<Boolean, String?> {
    return try {
        val newFile = File(dirPath, fileName)
        if (newFile.exists()) {
            return Pair(false, "同名文件已存在")
        }
        val created = newFile.createNewFile()
        if (created) Pair(true, null)
        else Pair(false, "文件创建失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "创建文件失败：${e.localizedMessage}")
    }
}

/**
 * 在绝对路径目录中创建新文件夹。
 */
private fun fileCreateDirectory(dirPath: String, dirName: String): Pair<Boolean, String?> {
    return try {
        val newDir = File(dirPath, dirName)
        if (newDir.exists()) {
            return Pair(false, "同名文件夹已存在")
        }
        val created = newDir.mkdir()
        if (created) Pair(true, null)
        else Pair(false, "文件夹创建失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "创建文件夹失败：${e.localizedMessage}")
    }
}

/**
 * 在绝对路径下重命名文件或文件夹。
 */
private fun fileRename(filePath: String, newName: String): Pair<Boolean, String?> {
    return try {
        val file = File(filePath)
        val parent = file.parentFile ?: return Pair(false, "无法确定父目录")
        val newFile = File(parent, newName)
        if (newFile.exists()) {
            return Pair(false, "同名文件已存在")
        }
        val renamed = file.renameTo(newFile)
        if (renamed) Pair(true, null)
        else Pair(false, "重命名失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "重命名失败：${e.localizedMessage}")
    }
}

/**
 * 在绝对路径下删除文件或（递归删除）目录。
 */
private fun fileDelete(filePath: String): Pair<Boolean, String?> {
    return try {
        val file = File(filePath)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) Pair(true, null)
        else Pair(false, "删除失败，请检查权限")
    } catch (e: Exception) {
        Pair(false, "删除失败：${e.localizedMessage}")
    }
}

/**
 * 统一入口：将一个 content:// 源文件复制到目标目录。
 * targetDirPath：目标目录的 document URI（SAF）或绝对路径。
 * 返回：(成功数, 失败数)
 */
private fun copyUriToDirectory(
    context: Context,
    sourceUri: Uri,
    targetDirPath: String,
    fileName: String
): Pair<Boolean, String?> {
    return try {
        val mimeType = getMimeTypeForFileName(fileName)
        if (targetDirPath.startsWith("content://")) {
            // SAF 目标：先在目标目录中创建文件，再写入内容
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
            // 绝对路径目标：直接写文件
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

/**
 * 将整个项目打包为 ZIP 文件并保存到系统下载目录。
 * 支持 SAF 路径（content://）和绝对路径。
 * 返回：(成功, 错误信息)
 */
private fun createProjectZipToDownloads(
    context: Context,
    projectLocalPath: String,
    projectName: String
): Pair<Boolean, String?> {
    return try {
        val zipFileName = "${projectName}_${System.currentTimeMillis()}.zip"

        // 根据 Android 版本选择写入 Downloads 的方式
        val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：通过 MediaStore 写入 Downloads，无需存储权限
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            val insertUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return Pair(false, "无法在下载目录创建文件")
            context.contentResolver.openOutputStream(insertUri)
                ?: return Pair(false, "无法打开下载文件输出流")
        } else {
            // Android 9 以下：直接写入 Downloads 目录
            @Suppress("DEPRECATION")
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            downloadsDir.mkdirs()
            File(downloadsDir, zipFileName).outputStream()
        }

        // 写入 ZIP
        ZipOutputStream(outputStream.buffered()).use { zip ->
            if (projectLocalPath.startsWith("content://")) {
                // SAF 路径：通过 DocumentFile 遍历
                val treeUri = Uri.parse(projectLocalPath)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return Pair(false, "无法访问项目目录")
                addSafDocToZip(context, rootDoc, zip, projectName)
            } else {
                // 绝对路径：通过 File 遍历
                val rootFile = File(projectLocalPath)
                if (!rootFile.exists()) return Pair(false, "项目目录不存在")
                addFileToZip(rootFile, zip, projectName)
            }
        }
        Pair(true, null)
    } catch (e: IOException) {
        Pair(false, "压缩失败（IO 错误）：${e.localizedMessage}")
    } catch (e: Exception) {
        Pair(false, "压缩失败：${e.localizedMessage}")
    }
}

/** 递归将 SAF DocumentFile 添加到 ZipOutputStream */
private fun addSafDocToZip(
    context: Context,
    doc: DocumentFile,
    zip: ZipOutputStream,
    pathInZip: String
) {
    val isDir = doc.isDirectory || doc.type == DocumentsContract.Document.MIME_TYPE_DIR
    if (isDir) {
        // 在 ZIP 中创建目录条目
        val dirEntry = ZipEntry("$pathInZip/")
        zip.putNextEntry(dirEntry)
        zip.closeEntry()
        // 递归处理子节点
        doc.listFiles()
            .filter { it.name != null }
            .forEach { child ->
                val childName = child.name ?: return@forEach
                addSafDocToZip(context, child, zip, "$pathInZip/$childName")
            }
    } else {
        // 文件：读取内容并写入 ZIP
        val entry = ZipEntry(pathInZip)
        zip.putNextEntry(entry)
        try {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.copyTo(zip)
            }
        } catch (e: Exception) {
            // 单个文件读取失败时继续处理其他文件，不中断整个 ZIP 过程
        }
        zip.closeEntry()
    }
}

/** 递归将本地 File 添加到 ZipOutputStream */
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
            file.inputStream().use { input ->
                input.copyTo(zip)
            }
        } catch (e: Exception) {
            // 单个文件读取失败时继续处理其他文件
        }
        zip.closeEntry()
    }
}

/**
 * 尝试在指定目录打开终端 App（优先 Termux）。
 *
 * 实现策略：
 * - SAF 路径（content://）无法直接传给终端进程，尝试从 URI 解析出可读路径并继续处理。
 * - 绝对路径：通过 Termux 的 RUN_COMMAND 服务在指定目录执行 `bash`；
 *   若权限不足，则将 `cd "<dir>"` 写入剪贴板后打开 Termux，
 *   并提示用户粘贴执行。
 * - 若 Termux 未安装，尝试其他终端（com.android.terminal），最终给出友好提示。
 *
 * 返回：(成功启动, 用户提示信息)
 */
private fun openTerminalAt(context: Context, dirPath: String): Pair<Boolean, String> {
    // ── 1. 解析可用的文件系统路径 ──────────────────────────────
    val fsPath: String = if (dirPath.startsWith("content://")) {
        // 尝试从 ExternalStorage URI 提取可读路径
        // 典型格式：content://com.android.externalstorage.documents/tree/primary%3Afoo/document/primary%3Afoo
        val uri = try { Uri.parse(dirPath) } catch (e: Exception) { null }
        val lastSeg = uri?.lastPathSegment
        if (lastSeg != null) {
            val relative = lastSeg.substringAfter(':')
            if (relative.isNotBlank()) "/storage/emulated/0/$relative"
            else ""
        } else ""
    } else {
        dirPath
    }

    if (fsPath.isBlank()) {
        return Pair(false, "SAF 路径无法直接传递给终端，请使用本地路径导入的项目后再试")
    }

    val termuxPackage = "com.termux"
    val pm = context.packageManager

    // ── 2. 检查 Termux 是否已安装 ───────────────────────────────
    val termuxInstalled = try {
        pm.getPackageInfo(termuxPackage, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    if (termuxInstalled) {
        // ── 2a. 尝试通过 Termux RUN_COMMAND 服务在目标目录执行 bash ──
        // 这需要在 Termux 中手动授权（Settings → Allow External Apps）
        val runCmdSuccess = try {
            val runCmdIntent = Intent().apply {
                setClassName(termuxPackage, "$termuxPackage.app.RunCommandService")
                action = "$termuxPackage.RUN_COMMAND"
                putExtra("$termuxPackage.RUN_COMMAND_PATH", "/data/data/$termuxPackage/files/usr/bin/bash")
                putExtra("$termuxPackage.RUN_COMMAND_ARGUMENTS", arrayOf<String>())
                putExtra("$termuxPackage.RUN_COMMAND_WORKDIR", fsPath)
                putExtra("$termuxPackage.RUN_COMMAND_TERMINAL", true)
            }
            context.startService(runCmdIntent)
            true
        } catch (e: Exception) {
            false
        }

        if (runCmdSuccess) {
            return Pair(true, "已在 Termux 中打开目录：$fsPath")
        }

        // ── 2b. RUN_COMMAND 不可用时的 fallback：
        //       将 cd 命令写入剪贴板，打开 Termux，提示用户粘贴 ──
        return try {
            val cdCommand = "cd \"$fsPath\""
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal_cd", cdCommand))

            val launchIntent = Intent().apply {
                setClassName(termuxPackage, "$termuxPackage.app.TermuxActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(launchIntent)
            Pair(true, "已打开 Termux，cd 命令已复制到剪贴板，请在 Termux 中长按粘贴执行")
        } catch (e: Exception) {
            Pair(false, "打开 Termux 失败：${e.localizedMessage}")
        }
    }

    // ── 3. Termux 未安装：尝试系统内置终端 ────────────────────────
    val androidTerminalPkg = "com.android.terminal"
    val androidTerminalInstalled = try {
        pm.getPackageInfo(androidTerminalPkg, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    if (androidTerminalInstalled) {
        return try {
            val intent = pm.getLaunchIntentForPackage(androidTerminalPkg)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            if (intent != null) {
                context.startActivity(intent)
                Pair(true, "已打开系统终端（无法自动切换目录，请手动 cd）")
            } else {
                Pair(false, "无法启动系统终端")
            }
        } catch (e: Exception) {
            Pair(false, "启动系统终端失败：${e.localizedMessage}")
        }
    }

    // ── 4. 没有可用终端：提示安装 ─────────────────────────────────
    return Pair(false, "未找到终端 App，请先从 F-Droid 或应用商店安装 Termux")
}

/**
 * 验证文件/文件夹名称的合法性。
 * 返回：null = 合法，非 null = 错误说明
 */
private fun validateFileName(name: String): String? {
    if (name.isBlank()) return "名称不能为空"
    if (name.length > 255) return "名称不能超过 255 个字符"
    if (name.contains('/') || name.contains('\\')) return "名称中不能包含路径分隔符"
    if (name == "." || name == "..") return "名称不能为 . 或 .."
    // Android FAT32/exFAT 不支持这些字符
    val invalidChars = listOf(':', '*', '?', '"', '<', '>', '|')
    for (ch in invalidChars) {
        if (name.contains(ch)) return "名称中不能包含字符 $ch"
    }
    return null
}

// ─────────────────────────────────────────────
// 主入口：底部弹出文件树
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerSheet(
    project: Project,
    onDismiss: () -> Unit,
    onOpenFile: (EditorFile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 文件树加载状态 ──
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var loadState by remember(project.localPath) {
        mutableStateOf<SheetLoadState>(SheetLoadState.Loading)
    }
    var expandedPaths by remember(project.localPath) {
        mutableStateOf(setOf(project.localPath ?: ""))
    }
    // 加载完成后存储根节点的真实路径（SAF 的 doc.uri 与 tree URI 不同）
    var rootDocPath by remember(project.localPath) { mutableStateOf("") }

    var openingFile by remember { mutableStateOf(false) }

    // ── 上下文菜单状态 ──
    var contextNode by remember { mutableStateOf<SheetFileNode?>(null) }
    var showContextSheet by remember { mutableStateOf(false) }
    val contextSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 对话框状态 ──
    var showRenameDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 操作目标节点（重命名/删除）
    var dialogTargetNode by remember { mutableStateOf<SheetFileNode?>(null) }
    // 新建操作目标目录路径（底部栏 = 根目录；上下文菜单 = 所选文件夹路径）
    var newItemTargetPath by remember { mutableStateOf("") }

    // ── 进度/操作状态 ──
    var isOperationInProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 上传文件 Launcher（在 Composable 顶层声明，不能放在 onClick 中）──
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
            val errors = mutableListOf<String>()
            for (uri in uris) {
                val fileName = getDisplayNameFromUri(context, uri)
                val (ok, err) = copyUriToDirectory(context, uri, targetDir, fileName)
                if (ok) {
                    successCount++
                } else {
                    failCount++
                    if (err != null) errors.add("$fileName: $err")
                }
            }
            withContext(Dispatchers.Main) {
                isOperationInProgress = false
                reloadTrigger++
                val msg = buildString {
                    append("已上传 $successCount 个文件")
                    if (failCount > 0) {
                        append("，$failCount 个失败")
                        if (errors.isNotEmpty()) append("（${errors.first()}）")
                    }
                }
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    // ── 加载文件树（reloadTrigger 变化时重新加载）──
    LaunchedEffect(project.localPath, reloadTrigger) {
        loadState = SheetLoadState.Loading
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
                    if (!file.exists()) {
                        return@runCatching SheetLoadState.Error("目录不存在：$path")
                    }
                    buildSheetNodeFromFile(file)
                }
                SheetLoadState.Loaded(root)
            }.getOrElse { e ->
                SheetLoadState.Error("加载失败：${e.localizedMessage ?: e.message}")
            }
        }
        // SAF 的 doc.uri 与原始 tree URI 不同，用实际根节点路径初始化
        if (result is SheetLoadState.Loaded) {
            rootDocPath = result.root.path
            expandedPaths = setOf(result.root.path)
        }
        loadState = result
    }

    // ─────────────────────────────────────────
    // 主 BottomSheet
    // ─────────────────────────────────────────
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── 标题栏 ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
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
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "文件树",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                // ── 文件树内容区 ─────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = loadState) {
                        is SheetLoadState.Loading -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "读取文件列表…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is SheetLoadState.Error -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SentimentDissatisfied,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                )
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                OutlinedButton(onClick = { reloadTrigger++ }) {
                                    Text("重试")
                                }
                            }
                        }

                        is SheetLoadState.Loaded -> {
                            val displayRows = remember(state.root, expandedPaths) {
                                flattenSheetVisible(state.root, depth = 0, expanded = expandedPaths)
                            }

                            if (displayRows.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                                    Text(
                                        text = "项目目录为空",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(displayRows, key = { it.node.path }) { row ->
                                        val isExpanded = row.node.path in expandedPaths
                                        SheetFileTreeRow(
                                            row = row,
                                            isExpanded = isExpanded,
                                            onClick = {
                                                if (row.node.isDirectory) {
                                                    expandedPaths = if (isExpanded) {
                                                        expandedPaths - row.node.path
                                                    } else {
                                                        expandedPaths + row.node.path
                                                    }
                                                } else {
                                                    if (!openingFile) {
                                                        openingFile = true
                                                        scope.launch(Dispatchers.IO) {
                                                            val content = readSheetFileContent(context, row.node.path)
                                                            withContext(Dispatchers.Main) {
                                                                openingFile = false
                                                                onOpenFile(
                                                                    EditorFile(
                                                                        name = row.node.name,
                                                                        code = content,
                                                                        lang = row.node.extension.ifBlank { "txt" }
                                                                    )
                                                                )
                                                            }
                                                        }
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

                            // 打开/操作中遮罩
                            if (openingFile || isOperationInProgress) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator()
                                        if (isOperationInProgress) {
                                            Text(
                                                text = "操作进行中…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 底部操作栏 ──────────────────────────────────
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 新建文件 —— 在项目根目录新建
                    SheetBottomAction(label = "新建文件") {
                        val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                        if (targetDir.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("项目路径无效，无法新建文件") }
                        } else {
                            newItemTargetPath = targetDir
                            showNewFileDialog = true
                        }
                    }

                    // 新建文件夹 —— 在项目根目录新建
                    SheetBottomAction(label = "新建文件夹") {
                        val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                        if (targetDir.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("项目路径无效，无法新建文件夹") }
                        } else {
                            newItemTargetPath = targetDir
                            showNewFolderDialog = true
                        }
                    }

                    // 上传文件 —— 打开系统文件选择器，将选中文件复制到项目根目录
                    SheetBottomAction(label = "上传文件") {
                        val targetDir = rootDocPath.ifBlank { project.localPath ?: "" }
                        if (targetDir.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("项目路径无效，无法上传") }
                        } else {
                            uploadLauncher.launch(arrayOf("*/*"))
                        }
                    }

                    // 下载 —— 将项目打包为 ZIP 保存到系统下载目录
                    SheetBottomAction(label = "下载") {
                        val srcPath = project.localPath ?: ""
                        if (srcPath.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar("项目路径无效，无法下载") }
                            return@SheetBottomAction
                        }
                        isOperationInProgress = true
                        scope.launch(Dispatchers.IO) {
                            val (success, error) = createProjectZipToDownloads(
                                context = context,
                                projectLocalPath = srcPath,
                                projectName = project.name
                            )
                            withContext(Dispatchers.Main) {
                                isOperationInProgress = false
                                val msg = if (success) {
                                    "已打包并保存到下载目录：${project.name}.zip"
                                } else {
                                    "下载失败：$error"
                                }
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    }
                }
            } // end Column

            // Snackbar 悬浮在内容上方
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 72.dp)
            )
        } // end Box
    } // end ModalBottomSheet

    // ─────────────────────────────────────────────
    // 上下文菜单 BottomSheet
    // ─────────────────────────────────────────────
    if (showContextSheet) {
        val node = contextNode ?: run { showContextSheet = false; return }
        val isFolder = node.isDirectory

        ModalBottomSheet(
            onDismissRequest = { showContextSheet = false },
            sheetState = contextSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // 节点信息头
                ListItem(
                    headlineContent = {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingContent = {
                        if (isFolder) {
                            Icon(
                                Icons.Outlined.Folder, null,
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            SheetFileExtBadge(node.extension)
                        }
                    },
                    supportingContent = {
                        Text(
                            node.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(4.dp))

                SHEET_CONTEXT_ACTIONS
                    .filter { action ->
                        when (action.forFolder) {
                            true  -> isFolder
                            false -> !isFolder
                            null  -> true
                        }
                    }
                    .forEach { action ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    action.label,
                                    color = if (action.isDestructive)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingContent = {
                                Icon(
                                    action.icon, null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (action.isDestructive)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable {
                                // 先关闭上下文菜单
                                scope.launch { contextSheetState.hide() }
                                showContextSheet = false

                                when (action.label) {
                                    "重命名" -> {
                                        dialogTargetNode = node
                                        showRenameDialog = true
                                    }
                                    "新建文件" -> {
                                        // 只有文件夹才显示此选项，node 是目标文件夹
                                        newItemTargetPath = node.path
                                        showNewFileDialog = true
                                    }
                                    "新建文件夹" -> {
                                        newItemTargetPath = node.path
                                        showNewFolderDialog = true
                                    }
                                    "在此打开终端" -> {
                                        val (launched, msg) = openTerminalAt(context, node.path)
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                    "复制路径" -> {
                                        // 对 SAF 路径，尝试转换成可读路径；否则直接使用
                                        val displayPath = if (node.path.startsWith("content://")) {
                                            // 尽力从 URI 中提取可读段
                                            try {
                                                val uri = Uri.parse(node.path)
                                                val lastSeg = uri.lastPathSegment ?: node.path
                                                // ExternalStorage URI 格式：primary:folder/file
                                                val relativePart = lastSeg.substringAfter(':')
                                                if (relativePart.isNotBlank()) {
                                                    "/storage/emulated/0/$relativePart"
                                                } else {
                                                    node.path
                                                }
                                            } catch (e: Exception) {
                                                node.path
                                            }
                                        } else {
                                            node.path
                                        }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("file_path", displayPath)
                                        )
                                        scope.launch { snackbarHostState.showSnackbar("路径已复制到剪贴板") }
                                    }
                                    "删除" -> {
                                        dialogTargetNode = node
                                        showDeleteDialog = true
                                    }
                                }
                            }
                        )
                    }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ─────────────────────────────────────────────
    // 重命名对话框
    // ─────────────────────────────────────────────
    if (showRenameDialog) {
        val target = dialogTargetNode ?: run { showRenameDialog = false; return }
        SheetInputDialog(
            title = "重命名",
            label = "新名称",
            initialText = target.name,
            confirmLabel = "重命名",
            onConfirm = { newName ->
                val validationError = validateFileName(newName)
                if (validationError != null) {
                    scope.launch { snackbarHostState.showSnackbar(validationError) }
                    return@SheetInputDialog
                }
                showRenameDialog = false
                isOperationInProgress = true
                scope.launch(Dispatchers.IO) {
                    val (success, error) = if (target.path.startsWith("content://")) {
                        safRename(context, target.path, newName)
                    } else {
                        fileRename(target.path, newName)
                    }
                    withContext(Dispatchers.Main) {
                        isOperationInProgress = false
                        if (success) {
                            reloadTrigger++
                            snackbarHostState.showSnackbar("已重命名为「$newName」")
                        } else {
                            snackbarHostState.showSnackbar(error ?: "重命名失败")
                        }
                    }
                }
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    // ─────────────────────────────────────────────
    // 新建文件对话框
    // ─────────────────────────────────────────────
    if (showNewFileDialog) {
        SheetInputDialog(
            title = "新建文件",
            label = "文件名",
            placeholder = "例如：main.js",
            confirmLabel = "创建",
            onConfirm = { name ->
                val validationError = validateFileName(name)
                if (validationError != null) {
                    scope.launch { snackbarHostState.showSnackbar(validationError) }
                    return@SheetInputDialog
                }
                showNewFileDialog = false
                isOperationInProgress = true
                val targetDir = newItemTargetPath.ifBlank { rootDocPath }
                scope.launch(Dispatchers.IO) {
                    val (success, error) = if (targetDir.startsWith("content://")) {
                        safCreateFile(context, targetDir, name)
                    } else {
                        fileCreateFile(targetDir, name)
                    }
                    withContext(Dispatchers.Main) {
                        isOperationInProgress = false
                        if (success) {
                            reloadTrigger++
                            snackbarHostState.showSnackbar("已创建文件「$name」")
                        } else {
                            snackbarHostState.showSnackbar(error ?: "创建文件失败")
                        }
                    }
                }
            },
            onDismiss = { showNewFileDialog = false }
        )
    }

    // ─────────────────────────────────────────────
    // 新建文件夹对话框
    // ─────────────────────────────────────────────
    if (showNewFolderDialog) {
        SheetInputDialog(
            title = "新建文件夹",
            label = "文件夹名",
            placeholder = "例如：src",
            confirmLabel = "创建",
            onConfirm = { name ->
                val validationError = validateFileName(name)
                if (validationError != null) {
                    scope.launch { snackbarHostState.showSnackbar(validationError) }
                    return@SheetInputDialog
                }
                showNewFolderDialog = false
                isOperationInProgress = true
                val targetDir = newItemTargetPath.ifBlank { rootDocPath }
                scope.launch(Dispatchers.IO) {
                    val (success, error) = if (targetDir.startsWith("content://")) {
                        safCreateDirectory(context, targetDir, name)
                    } else {
                        fileCreateDirectory(targetDir, name)
                    }
                    withContext(Dispatchers.Main) {
                        isOperationInProgress = false
                        if (success) {
                            reloadTrigger++
                            snackbarHostState.showSnackbar("已创建文件夹「$name」")
                        } else {
                            snackbarHostState.showSnackbar(error ?: "创建文件夹失败")
                        }
                    }
                }
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    // ─────────────────────────────────────────────
    // 删除确认对话框
    // ─────────────────────────────────────────────
    if (showDeleteDialog) {
        val target = dialogTargetNode ?: run { showDeleteDialog = false; return }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Outlined.Delete,
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("确认删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "将永久删除「${target.name}」。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (target.isDirectory) {
                        Text(
                            text = "⚠ 该文件夹内所有子文件和子文件夹也将被一并删除，此操作不可撤销。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = "此操作不可撤销。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        isOperationInProgress = true
                        scope.launch(Dispatchers.IO) {
                            val (success, error) = if (target.path.startsWith("content://")) {
                                safDelete(context, target.path)
                            } else {
                                fileDelete(target.path)
                            }
                            withContext(Dispatchers.Main) {
                                isOperationInProgress = false
                                if (success) {
                                    reloadTrigger++
                                    snackbarHostState.showSnackbar("「${target.name}」已删除")
                                } else {
                                    snackbarHostState.showSnackbar(error ?: "删除失败")
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

// ─────────────────────────────────────────────
// 底部操作按钮
// ─────────────────────────────────────────────

@Composable
private fun SheetBottomAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────
// 文件树行 UI
// ─────────────────────────────────────────────

@Composable
private fun SheetFileTreeRow(
    row: SheetDisplayRow,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onContextMenu: () -> Unit
) {
    val node = row.node
    val indentDp = (row.depth * 18 + 8).dp

    val chevronDeg by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron_${node.path}"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentDp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：chevron + 图标
        if (node.isDirectory) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronDeg),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFFFFB74D)
            )
        } else {
            Spacer(Modifier.width(22.dp))
            SheetFileExtBadge(node.extension)
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            fontFamily = if (!node.isDirectory) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (node.isDirectory) FontWeight.Medium else FontWeight.Normal
        )

        // 右侧：⋮ 上下文菜单按钮
        IconButton(
            onClick = onContextMenu,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多操作",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// ─────────────────────────────────────────────
// 文件扩展名 Badge
// ─────────────────────────────────────────────

@Composable
private fun SheetFileExtBadge(extension: String) {
    val bg = sheetFileIconColor(extension)
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = extension.take(3).uppercase().ifBlank { "?" },
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            color = bg,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────
// 通用输入对话框（重命名 / 新建）
// ─────────────────────────────────────────────

@Composable
private fun SheetInputDialog(
    title: String,
    label: String,
    initialText: String = "",
    placeholder: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    if (err != null) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank() && validateFileName(text) == null) onConfirm(text.trim()) },
                enabled = text.isNotBlank() && validateFileName(text) == null
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ─────────────────────────────────────────────
// 树构建：java.io.File（本地绝对路径）
// ─────────────────────────────────────────────

private fun buildSheetNodeFromFile(file: File): SheetFileNode {
    return if (file.isDirectory) {
        val children = (file.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { buildSheetNodeFromFile(it) }
        SheetFileNode(
            name = file.name,
            path = file.absolutePath,
            isDirectory = true,
            extension = "",
            children = children
        )
    } else {
        SheetFileNode(
            name = file.name,
            path = file.absolutePath,
            isDirectory = false,
            extension = file.extension.lowercase()
        )
    }
}

// ─────────────────────────────────────────────
// 树构建：SAF DocumentFile（content URI）
// 双重检查 isDirectory，避免部分 Android 版本 MIME 未缓存的问题
// ─────────────────────────────────────────────

private fun buildSheetNodeFromSaf(context: Context, doc: DocumentFile): SheetFileNode {
    val name = doc.name ?: "未知"
    val isDir = doc.isDirectory || doc.type == DocumentsContract.Document.MIME_TYPE_DIR
    return if (isDir) {
        val children = doc.listFiles()
            .filter { it.name != null }
            .sortedWith(compareBy(
                { !(it.isDirectory || it.type == DocumentsContract.Document.MIME_TYPE_DIR) },
                { it.name?.lowercase() ?: "" }
            ))
            .map { buildSheetNodeFromSaf(context, it) }
        SheetFileNode(
            name = name,
            path = doc.uri.toString(),
            isDirectory = true,
            extension = "",
            children = children
        )
    } else {
        val ext = name.substringAfterLast('.', "").lowercase()
        SheetFileNode(
            name = name,
            path = doc.uri.toString(),
            isDirectory = false,
            extension = ext
        )
    }
}

// ─────────────────────────────────────────────
// 将树展平为可见行列表（根节点本身不显示）
// ─────────────────────────────────────────────

private fun flattenSheetVisible(
    node: SheetFileNode,
    depth: Int,
    expanded: Set<String>
): List<SheetDisplayRow> {
    if (depth == 0) {
        if (node.path !in expanded) return emptyList()
        return node.children.flatMap { flattenSheetVisible(it, 1, expanded) }
    }
    val selfRow = SheetDisplayRow(node, depth)
    return if (node.isDirectory && node.path in expanded) {
        listOf(selfRow) + node.children.flatMap { flattenSheetVisible(it, depth + 1, expanded) }
    } else {
        listOf(selfRow)
    }
}

// ─────────────────────────────────────────────
// 读取文件内容（支持绝对路径 + SAF URI）
// ─────────────────────────────────────────────

private fun readSheetFileContent(context: Context, path: String): String {
    return try {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)
                ?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                ?: "（无法读取文件内容）"
        } else {
            File(path).readText(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        "// 读取文件失败：${e.localizedMessage}\n"
    }
}

// ─────────────────────────────────────────────
// 扩展名 → 图标颜色映射
// ─────────────────────────────────────────────

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
