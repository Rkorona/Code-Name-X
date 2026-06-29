package io.axiom.editor.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.axiom.editor.ui.screen.ProjectSortOrder

/**
 * 应用顶部导航栏，支持多种 Tab 状态
 *
 * @param selectedTab 当前选中的 Tab (0=Projects, 1=GitHub, 3=Settings)
 * @param sortOrder 当前排序方式（仅 Tab 0 有效）
 * @param onSortOrderChange 排序方式变更回调（仅 Tab 0 有效）
 * @param isSearchActive 是否激活搜索模式（仅 Tab 0 有效）
 * @param onSearchActiveChange 搜索模式切换回调（仅 Tab 0 有效）
 * @param searchQuery 当前搜索关键词（仅 Tab 0 搜索模式有效）
 * @param onSearchQueryChange 搜索关键词变更回调（仅 Tab 0 搜索模式有效）
 * @param isScrolled 页面是否已滚动（滚动后顶栏背景自动加深）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    selectedTab: Int,
    sortOrder: ProjectSortOrder = ProjectSortOrder.DEFAULT,
    onSortOrderChange: (ProjectSortOrder) -> Unit = {},
    isSearchActive: Boolean = false,
    onSearchActiveChange: (Boolean) -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    isScrolled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // 未滚动时与页面背景（Scaffold background）保持一致，滚动后加深
    val barBgColor by animateColorAsState(
        targetValue = if (isScrolled)
            MaterialTheme.colorScheme.surfaceContainerHigh
        else
            MaterialTheme.colorScheme.background,
        animationSpec = tween(durationMillis = 250),
        label = "topBarBg"
    )

    // 搜索栏激活时自动弹出键盘
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    when (selectedTab) {
        0 -> {
            if (isSearchActive) {
                // ── 搜索模式：Termius 风格胶囊搜索栏 ──
                Surface(
                    color = barBgColor,
                    modifier = modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 4.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        onSearchActiveChange(false)
                                        onSearchQueryChange("")
                                        keyboardController?.hide()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "关闭搜索",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                TextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(searchFocusRequester),
                                    placeholder = {
                                        Text(
                                            text = "搜索项目名称…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                        )
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { keyboardController?.hide() }
                                    ),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                        errorIndicatorColor = Color.Transparent,
                                    ),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── 正常模式：Logo + 标题 + 操作按钮 ──
                TopAppBar(
                    expandedHeight = 64.dp,
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Outlined.Hub,
                                contentDescription = "Logo",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Axiom",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = { onSearchActiveChange(true) }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.SortByAlpha,
                                    contentDescription = "排序",
                                    tint = if (sortOrder == ProjectSortOrder.DEFAULT)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                                shape = RoundedCornerShape(14.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 0.dp,
                                shadowElevation = 4.dp
                            ) {
                                ProjectSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.label,
                                                color = if (sortOrder == order)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (sortOrder == order)
                                                    FontWeight.SemiBold
                                                else
                                                    FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            onSortOrderChange(order)
                                            sortMenuExpanded = false
                                        },
                                        leadingIcon = if (sortOrder == order) ({
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "已选中",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }) else null
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = barBgColor
                    ),
                    modifier = modifier
                )
            }
        }
        3 -> {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barBgColor
                ),
                modifier = modifier
            )
        }
    }
}
