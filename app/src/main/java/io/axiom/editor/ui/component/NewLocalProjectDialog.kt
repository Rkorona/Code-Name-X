package io.axiom.editor.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// ─────────────────────────────────────────────
// 存储位置枚举
// ─────────────────────────────────────────────

enum class StorageLocation { EXTERNAL, INTERNAL }

// ─────────────────────────────────────────────
// 新建本地项目对话框
// ─────────────────────────────────────────────

@Composable
fun NewLocalProjectDialog(
    onConfirm: (projectName: String, localPath: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var projectName by remember { mutableStateOf("") }
    var storageLocation by remember { mutableStateOf(StorageLocation.EXTERNAL) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 根据当前存储选择和项目名，实时计算预览路径
    val previewPath by remember(projectName, storageLocation) {
        derivedStateOf {
            val root = when (storageLocation) {
                StorageLocation.EXTERNAL ->
                    context.getExternalFilesDir(null)?.absolutePath
                        ?: context.filesDir.absolutePath
                StorageLocation.INTERNAL ->
                    context.filesDir.absolutePath
            }
            val name = projectName.trim().ifBlank { "<项目名称>" }
            "$root/projects/$name"
        }
    }

    // 自动弹出键盘
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun doConfirm() {
        keyboardController?.hide()
        handleConfirm(
            context = context,
            projectName = projectName,
            storageLocation = storageLocation,
            onError = { errorMessage = it },
            onConfirm = onConfirm
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "新建本地项目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // ── 项目名称输入框 ──────────────────────────
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { input ->
                        val sanitized = input.filter { ch ->
                            ch != '/' && ch != '\\' && ch != ':' &&
                            ch != '*' && ch != '?' && ch != '"'  &&
                            ch != '<' && ch != '>'  && ch != '|'
                        }
                        projectName = sanitized
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text("项目名称") },
                    placeholder = { Text("例如：my-project") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = if (errorMessage != null) {
                        { Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { doConfirm() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                // ── 存储位置选择 ────────────────────────────
                Text(
                    text = "存储位置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                StorageSelector(
                    selected = storageLocation,
                    onSelect = { storageLocation = it }
                )

                // ── 路径预览 ────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "创建路径",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = previewPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { doConfirm() },
                enabled = projectName.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("取消")
            }
        }
    )
}

// ─────────────────────────────────────────────
// 存储位置选择器（两段式按钮）
// ─────────────────────────────────────────────

@Composable
private fun StorageSelector(
    selected: StorageLocation,
    onSelect: (StorageLocation) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        StorageOption(
            label = "External",
            description = "文件管理器可见",
            isSelected = selected == StorageLocation.EXTERNAL,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(StorageLocation.EXTERNAL) }
        )
        StorageOption(
            label = "Internal",
            description = "仅 App 可访问",
            isSelected = selected == StorageLocation.INTERNAL,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(StorageLocation.INTERNAL) }
        )
    }
}

@Composable
private fun StorageOption(
    label: String,
    description: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceContainerHigh

    val labelColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    val descColor = if (isSelected)
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    else
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Surface(
        onClick = onClick,
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = descColor,
                fontSize = 10.sp
            )
        }
    }
}

// ─────────────────────────────────────────────
// 文件夹创建逻辑
// ─────────────────────────────────────────────

private fun handleConfirm(
    context: android.content.Context,
    projectName: String,
    storageLocation: StorageLocation,
    onError: (String) -> Unit,
    onConfirm: (name: String, localPath: String) -> Unit
) {
    val name = projectName.trim()

    if (name.isBlank()) {
        onError("项目名称不能为空")
        return
    }
    if (name.length > 64) {
        onError("名称不能超过 64 个字符")
        return
    }

    // 根据存储选择确定根目录
    val storageRoot: File = when (storageLocation) {
        StorageLocation.EXTERNAL -> {
            // /storage/emulated/0/Android/data/{包名}/files/
            context.getExternalFilesDir(null)
                ?: run {
                    onError("外部存储不可用，请选择内部存储")
                    return
                }
        }
        StorageLocation.INTERNAL -> {
            // /data/data/{包名}/files/
            context.filesDir
        }
    }

    val projectsRoot = File(storageRoot, "projects")
    if (!projectsRoot.exists()) {
        projectsRoot.mkdirs()
    }

    val projectDir = File(projectsRoot, name)
    if (projectDir.exists()) {
        onError("同名项目已存在，请换一个名称")
        return
    }

    val created = projectDir.mkdir()
    if (!created) {
        onError("文件夹创建失败，请检查存储空间")
        return
    }

    onConfirm(name, projectDir.absolutePath)
}
