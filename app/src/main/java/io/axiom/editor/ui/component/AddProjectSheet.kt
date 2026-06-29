package io.axiom.editor.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────
// 新建项目来源枚举
// ─────────────────────────────────────────────

enum class AddProjectAction {
    NEW_LOCAL,
    CLONE_GITHUB,
    IMPORT_FILE,
    FROM_TEMPLATE
}

// ─────────────────────────────────────────────
// 新建项目 Bottom Sheet 入口
// ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProjectSheet(
    onAction: (AddProjectAction) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        SheetContent(
            onAction = { action ->
                onAction(action)
                onDismiss()
            }
        )
    }
}

// ─────────────────────────────────────────────
// Sheet 内容主体
// ─────────────────────────────────────────────

@Composable
private fun SheetContent(
    onAction: (AddProjectAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp)
    ) {
        Text(
            text = "新建项目",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "选择项目来源",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        AddProjectOption(
            icon = Icons.Outlined.FolderOpen,
            iconBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            title = "新建本地项目",
            subtitle = "从空白文件或模板开始，保存在本机",
            onClick = { onAction(AddProjectAction.NEW_LOCAL) }
        )

        OptionDivider()

        AddProjectOption(
            icon = Icons.Outlined.Hub,
            iconBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
            title = "从 GitHub 克隆",
            subtitle = "输入仓库地址，同步远程代码到本地",
            onClick = { onAction(AddProjectAction.CLONE_GITHUB) }
        )

        OptionDivider()

        AddProjectOption(
            icon = Icons.Outlined.Download,
            iconBackgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            title = "导入本地文件",
            subtitle = "从设备存储中选择已有的项目文件夹",
            onClick = { onAction(AddProjectAction.IMPORT_FILE) }
        )

        OptionDivider()

        AddProjectOption(
            icon = Icons.Outlined.AutoAwesome,
            iconBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = "从模板开始",
            subtitle = "从预置模板快速创建项目，稍后完善",
            onClick = { onAction(AddProjectAction.FROM_TEMPLATE) }
        )
    }
}

// ─────────────────────────────────────────────
// 选项行分隔线
// ─────────────────────────────────────────────

@Composable
private fun OptionDivider() {
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .padding(start = 72.dp), // 与图标右边缘对齐，文字部分才有线
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

// ─────────────────────────────────────────────
// 单个选项行
// ─────────────────────────────────────────────

@Composable
private fun AddProjectOption(
    icon: ImageVector,
    iconBackgroundColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val rowBackground by animateColorAsState(
        targetValue = if (isPressed)
            MaterialTheme.colorScheme.surfaceContainerHigh
        else
            Color.Transparent,
        animationSpec = tween(durationMillis = 80),
        label = "option_row_bg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标容器
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 文字区域
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 右箭头
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            fontWeight = FontWeight.Light
        )
    }
}
