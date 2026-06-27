package com.example.myapplication.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

// ─────────────────────────────────────────────
// 内部数据模型（sheet 版独立定义，避免跨文件私有符号冲突）
// ─────────────────────────────────────────────

private data class SheetFileNode(
    val name: String,
    val path: String,
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

    var loadState by remember(project.localPath) {
        mutableStateOf<SheetLoadState>(SheetLoadState.Loading)
    }
    var expandedPaths by remember(project.localPath) {
        mutableStateOf(setOf(project.localPath ?: ""))
    }
    var openingFile by remember { mutableStateOf(false) }

    // 加载文件树
    LaunchedEffect(project.localPath) {
        loadState = SheetLoadState.Loading
        val path = project.localPath
        if (path.isNullOrBlank()) {
            loadState = SheetLoadState.Error("该项目没有关联本地路径")
            return@LaunchedEffect
        }
        loadState = withContext(Dispatchers.IO) {
            runCatching {
                val root: SheetFileNode = if (path.startsWith("content://")) {
                    val treeUri = Uri.parse(path)
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@runCatching SheetLoadState.Error("无法访问该目录，权限可能已失效")
                    buildSheetNodeFromSaf(context, rootDoc)
                } else {
                    val file = File(path)
                    if (!file.exists()) return@runCatching SheetLoadState.Error("目录不存在：$path")
                    buildSheetNodeFromFile(file)
                }
                SheetLoadState.Loaded(root)
            }.getOrElse { e ->
                SheetLoadState.Error("加载失败：${e.message}")
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            // ── 标题栏 ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
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

                // "文件树" 标签按钮（装饰性）
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

            // ── 文件树内容区 ─────────────────────────────────────
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
                                                        val content =
                                                            readSheetFileContent(context, row.node.path)
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
                                        }
                                    )
                                }
                            }
                        }

                        // 打开文件时的遮罩
                        if (openingFile) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            // ── 底部操作栏 ───────────────────────────────────────
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
                SheetBottomAction(label = "新建文件", onClick = { /* TODO */ })
                SheetBottomAction(label = "新建文件夹", onClick = { /* TODO */ })
                SheetBottomAction(label = "上传文件", onClick = { /* TODO */ })
                SheetBottomAction(label = "下载", onClick = { /* TODO */ })
            }
        }
    }
}

// ─────────────────────────────────────────────
// 底部操作按钮
// ─────────────────────────────────────────────

@Composable
private fun SheetBottomAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
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
    onClick: () -> Unit
) {
    val node = row.node
    val indentDp = (row.depth * 18 + 16).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentDp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFFFFB74D)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(sheetFileIconColor(node.extension).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = node.extension.take(2).uppercase().ifBlank { "  " },
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = sheetFileIconColor(node.extension),
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (node.isDirectory && node.children.isNotEmpty()) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                              else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }
    }
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
// 修复：通过 MIME type 和 isDirectory 双重判断，
// 避免部分 Android 版本 DocumentFile.isDirectory
// 因 MIME 未缓存而错误返回 false 的问题
// ─────────────────────────────────────────────

private fun buildSheetNodeFromSaf(context: Context, doc: DocumentFile): SheetFileNode {
    val name = doc.name ?: "未知"
    // 双重检查：优先用 isDirectory，备用检查 MIME type
    val isDir = doc.isDirectory || doc.type == DocumentsContract.Document.MIME_TYPE_DIR
    return if (isDir) {
        val children = doc.listFiles()
            .filter { it.name != null } // 过滤掉名称为 null 的条目
            .sortedWith(compareBy(
                // 目录排前面：isDirectory 或 MIME 为目录型
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
// 读取文件内容
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
        "// 读取文件失败：${e.message}\n"
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
