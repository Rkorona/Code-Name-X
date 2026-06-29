package io.axiom.editor.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import io.axiom.editor.ui.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────
// 内部数据模型
// ─────────────────────────────────────────────
private data class FileNode(
    val name: String,
    val path: String,           // 绝对路径 或 SAF content URI 字符串
    val isDirectory: Boolean,
    val extension: String,      // 文件扩展名（不含点），目录为空字符串
    val children: List<FileNode> = emptyList()
)

private data class DisplayRow(
    val node: FileNode,
    val depth: Int
)

private sealed class LoadState {
    object Loading : LoadState()
    data class Loaded(val root: FileNode) : LoadState()
    data class Error(val message: String) : LoadState()
}

// ─────────────────────────────────────────────
// 主屏幕
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    project: Project,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    val context = LocalContext.current

    var loadState by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    var expandedPaths by remember { mutableStateOf(setOf(project.localPath ?: "")) }
    var openingFile by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    // 懒加载：路径 -> 已加载的直接子节点列表
    var childrenCache by remember { mutableStateOf<Map<String, List<FileNode>>>(emptyMap()) }
    // 正在加载的目录路径集合（显示 spinner）
    var loadingDirs by remember { mutableStateOf<Set<String>>(emptySet()) }
    // SAF 项目的树根 URI，供子目录懒加载使用
    var safTreeUri by remember { mutableStateOf<Uri?>(null) }

    // 在 IO 线程浅层加载文件树（只扫描根 + 直接子节点）
    LaunchedEffect(project.localPath) {
        loadState = LoadState.Loading
        childrenCache = emptyMap()
        val path = project.localPath
        if (path.isNullOrBlank()) {
            loadState = LoadState.Error("该项目没有关联本地路径")
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val root: FileNode = if (path.startsWith("content://")) {
                    val treeUri = Uri.parse(path)
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@runCatching LoadState.Error("无法访问该目录，权限可能已失效")
                    buildShallowFromSaf(rootDoc)
                } else {
                    val file = File(path)
                    if (!file.exists()) return@runCatching LoadState.Error("目录不存在：$path")
                    buildShallowFromFile(file)
                }
                LoadState.Loaded(root)
            }.getOrElse { e ->
                LoadState.Error("加载失败：${e.localizedMessage ?: e.message}")
            }
        }

        if (result is LoadState.Loaded) {
            expandedPaths = setOf(result.root.path)
            if (path.startsWith("content://")) safTreeUri = Uri.parse(path)
        }
        loadState = result
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = project.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val state = loadState) {
                is LoadState.Loading -> {
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

                is LoadState.Error -> {
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
                            modifier = Modifier.size(52.dp),
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

                is LoadState.Loaded -> {
                    val displayRows = remember(state.root, expandedPaths, childrenCache) {
                        flattenVisible(state.root, depth = 0, expanded = expandedPaths, childrenCache = childrenCache)
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
                                modifier = Modifier.size(52.dp),
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
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(displayRows, key = { it.node.path }) { row ->
                                val isExpanded = row.node.path in expandedPaths
                                FileTreeRow(
                                    row = row,
                                    isExpanded = isExpanded,
                                    isLoading = row.node.path in loadingDirs,
                                    onClick = {
                                        if (row.node.isDirectory) {
                                            if (isExpanded) {
                                                expandedPaths = expandedPaths - row.node.path
                                            } else {
                                                expandedPaths = expandedPaths + row.node.path
                                                // 懒加载子节点（未缓存且未在加载中时触发）
                                                val alreadyLoaded = row.node.path in childrenCache || row.node.children.isNotEmpty()
                                                if (!alreadyLoaded && row.node.path !in loadingDirs) {
                                                    loadingDirs = loadingDirs + row.node.path
                                                    coroutineScope.launch {
                                                        val children = withContext(Dispatchers.IO) {
                                                            val tUri = safTreeUri
                                                            if (tUri != null) loadSafChildren(context, tUri, row.node.path)
                                                            else loadFileChildren(row.node.path)
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
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 文件树行 UI
// ─────────────────────────────────────────────
@Composable
private fun FileTreeRow(
    row: DisplayRow,
    isExpanded: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val node = row.node
    val indentDp = (row.depth * 18 + 8).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentDp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.FolderOpen
                else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF7C9CBF)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fileIconColor(node.extension).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = node.extension.take(2).uppercase().ifBlank { "  " },
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = fileIconColor(node.extension),
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

        if (node.isDirectory) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            } else {
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
}

// ─────────────────────────────────────────────
// 树构建：浅层扫描（只扫描根 + 直接子节点）
// ─────────────────────────────────────────────
private fun buildShallowFromFile(file: File): FileNode {
    if (!file.isDirectory) return FileNode(file.name, file.absolutePath, false, file.extension.lowercase())
    val children = (file.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { child ->
            FileNode(
                name = child.name,
                path = child.absolutePath,
                isDirectory = child.isDirectory,
                extension = if (child.isDirectory) "" else child.extension.lowercase()
            )
        }
    return FileNode(file.name, file.absolutePath, true, "", children)
}

private fun buildShallowFromSaf(doc: DocumentFile): FileNode {
    val name = doc.name ?: "未知"
    if (!doc.isDirectory) return FileNode(name, doc.uri.toString(), false, name.substringAfterLast('.', "").lowercase())
    val children = doc.listFiles()
        .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
        .map { child ->
            val cName = child.name ?: "未知"
            FileNode(
                name = cName,
                path = child.uri.toString(),
                isDirectory = child.isDirectory,
                extension = if (child.isDirectory) "" else cName.substringAfterLast('.', "").lowercase()
            )
        }
    return FileNode(name, doc.uri.toString(), true, "", children)
}

// ─────────────────────────────────────────────
// 懒加载：展开目录时按需扫描子节点
// ─────────────────────────────────────────────
private fun loadFileChildren(path: String): List<FileNode> {
    val file = File(path)
    return (file.listFiles() ?: emptyArray())
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { child ->
            FileNode(
                name = child.name,
                path = child.absolutePath,
                isDirectory = child.isDirectory,
                extension = if (child.isDirectory) "" else child.extension.lowercase()
            )
        }
}

private fun loadSafChildren(context: Context, treeUri: Uri, docPath: String): List<FileNode> {
    return try {
        val docUri = Uri.parse(docPath)
        val docId = DocumentsContract.getDocumentId(docUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val docFile = DocumentFile.fromTreeUri(context, treeDocUri) ?: return emptyList()
        docFile.listFiles()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
            .map { child ->
                val cName = child.name ?: "未知"
                FileNode(
                    name = cName,
                    path = child.uri.toString(),
                    isDirectory = child.isDirectory,
                    extension = if (child.isDirectory) "" else cName.substringAfterLast('.', "").lowercase()
                )
            }
    } catch (e: Exception) {
        emptyList()
    }
}

// ─────────────────────────────────────────────
// 将树展平为可见行列表（使用懒加载缓存）
// ─────────────────────────────────────────────
private fun flattenVisible(
    node: FileNode,
    depth: Int,
    expanded: Set<String>,
    childrenCache: Map<String, List<FileNode>>
): List<DisplayRow> {
    val children = childrenCache[node.path] ?: node.children
    if (depth == 0) {
        if (node.path !in expanded) return emptyList()
        return children.flatMap { flattenVisible(it, 1, expanded, childrenCache) }
    }
    val selfRow = DisplayRow(node, depth)
    return if (node.isDirectory && node.path in expanded) {
        listOf(selfRow) + children.flatMap { flattenVisible(it, depth + 1, expanded, childrenCache) }
    } else {
        listOf(selfRow)
    }
}

// ─────────────────────────────────────────────
// 扩展名颜色映射
// ─────────────────────────────────────────────
private fun fileIconColor(extension: String): Color = when (extension) {
    "js", "jsx", "mjs"           -> Color(0xFFF5C518)
    "ts", "tsx"                   -> Color(0xFF3178C6)
    "py"                          -> Color(0xFF3572A5)
    "kt", "kts"                   -> Color(0xFF7F52FF)
    "java"                        -> Color(0xFFB07219)
    "dart"                        -> Color(0xFF00B4AB)
    "html", "htm"                 -> Color(0xFFE34C26)
    "css", "scss", "sass", "less" -> Color(0xFF563D7C)
    "json", "jsonc"               -> Color(0xFF40BF40)
    "md", "markdown"              -> Color(0xFF888888)
    "xml", "svg"                  -> Color(0xFFE07020)
    "sh", "bash", "zsh"           -> Color(0xFF89E051)
    "go"                          -> Color(0xFF00ADD8)
    "rs"                          -> Color(0xFFDEA584)
    "cpp", "cc", "cxx", "c", "h" -> Color(0xFF555599)
    "rb"                          -> Color(0xFFCC342D)
    "php"                         -> Color(0xFF4F5D95)
    "swift"                       -> Color(0xFFFF6940)
    else                          -> Color(0xFF9E9E9E)
}