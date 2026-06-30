@file:OptIn(ExperimentalMaterial3Api::class)

package io.axiom.editor.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.axiom.editor.ui.model.ChangedFile
import io.axiom.editor.ui.model.CommitRecord
import io.axiom.editor.ui.model.FileChangeStatus
import io.axiom.editor.ui.model.LocalRepo
import io.axiom.editor.ui.model.RemoteRepo
import io.axiom.editor.ui.theme.LocalGitHubColors
import io.axiom.editor.ui.theme.gitHubColors

// ═══════════════════════════════════════════════════════════════════
// 主体页面
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(
    modifier: Modifier = Modifier,
    viewModel: GitHubViewModel = viewModel(),
    listState: LazyListState = rememberLazyListState(),
    showLoginSheet: Boolean = false,
    onLoginSheetDismiss: () -> Unit = {}
) {
    CompositionLocalProvider(LocalGitHubColors provides gitHubColors()) {
        val colors = LocalGitHubColors.current

        val expandedRepoName = viewModel.expandedRepoName
        val localRepos = viewModel.localRepos
        val remoteRepos = viewModel.remoteRepos
        val searchQuery = viewModel.searchQuery

        val sheetState = rememberModalBottomSheetState()

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                val filteredRepos = if (searchQuery.isBlank()) {
                    remoteRepos
                } else {
                    remoteRepos.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                            it.language.contains(searchQuery, ignoreCase = true)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    item { SectionTitle("本地仓库状态 (Local)") }

                    items(localRepos, key = { it.name }) { repo ->
                        val isExpanded = expandedRepoName == repo.name
                        LocalRepoCard(
                            repo = repo,
                            isExpanded = isExpanded,
                            onToggle = { viewModel.toggleRepoExpansion(repo.name) },
                            changedFiles = viewModel.changedFiles[repo.name] ?: emptyList(),
                            commitHistory = viewModel.commitHistory[repo.name] ?: emptyList(),
                            expandedTab = if (isExpanded) viewModel.expandedTabIndex else 0,
                            onTabSwitch = viewModel::switchExpandedTab,
                            onToggleStaged = { path -> viewModel.toggleFileStaged(repo.name, path) },
                            onStageAll = { viewModel.stageAll(repo.name) },
                            onUnstageAll = { viewModel.unstageAll(repo.name) },
                            modifier = Modifier.animateItem()
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionTitle("云端仓库 (Remote)")
                    }

                    item {
                        RemoteSearchBar(
                            query = searchQuery,
                            onQueryChange = { viewModel.updateSearchQuery(it) }
                        )
                    }

                    items(filteredRepos, key = { it.name }) { repo ->
                        RemoteRepoCard(repo = repo, modifier = Modifier.animateItem())
                    }

                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }

            if (showLoginSheet) {
                LoginBottomSheet(
                    sheetState = sheetState,
                    onDismiss = onLoginSheetDismiss,
                    onLogin = { username, token ->
                        viewModel.login(username, token)
                        onLoginSheetDismiss()
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 区域标题
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SectionTitle(title: String) {
    val colors = LocalGitHubColors.current
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textMuted,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// 本地仓库卡片
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun LocalRepoCard(
    repo: LocalRepo,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    changedFiles: List<ChangedFile>,
    commitHistory: List<CommitRecord>,
    expandedTab: Int,
    onTabSwitch: (Int) -> Unit,
    onToggleStaged: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalGitHubColors.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .then(
                if (isExpanded)
                    Modifier.border(1.dp, colors.expandedBorder, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) colors.cardExpanded else colors.card
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = repo.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CallSplit,
                                contentDescription = null,
                                tint = colors.accentBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = repo.branch,
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (repo.uncommittedChanges > 0) {
                            StatusBadge(
                                text = "${repo.uncommittedChanges} 个修改",
                                backgroundColor = colors.accentRedAlpha,
                                textColor = colors.accentRed
                            )
                        }
                        if (repo.unpushedCommits > 0) {
                            StatusBadge(
                                text = "${repo.unpushedCommits} 个待推送",
                                backgroundColor = colors.accentBlueAlpha2,
                                textColor = colors.accentBlueLight
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LocalRepoExpandedContent(
                    changedFiles = changedFiles,
                    commitHistory = commitHistory,
                    selectedTab = expandedTab,
                    onTabSwitch = onTabSwitch,
                    onToggleStaged = onToggleStaged,
                    onStageAll = onStageAll,
                    onUnstageAll = onUnstageAll,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    backgroundColor: Color,
    textColor: Color
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// 展开内容（Tab 切换：变更 / 历史）
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun LocalRepoExpandedContent(
    changedFiles: List<ChangedFile>,
    commitHistory: List<CommitRecord>,
    selectedTab: Int,
    onTabSwitch: (Int) -> Unit,
    onToggleStaged: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(bottom = 14.dp)) {

        // Fetch / Pull / Push
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RemoteActionButton(
                icon = Icons.Rounded.Downloading,
                label = "Fetch",
                modifier = Modifier.weight(1f)
            )
            RemoteActionButton(
                icon = Icons.Rounded.ArrowDownward,
                label = "Pull",
                modifier = Modifier.weight(1f)
            )
            RemoteActionButton(
                icon = Icons.Rounded.ArrowUpward,
                label = "Push",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tab 切换器
        ExpandedTabSwitcher(
            selectedTab = selectedTab,
            changesCount = changedFiles.size,
            onTabSelected = onTabSwitch
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (selectedTab) {
            0 -> ChangesTab(
                changedFiles = changedFiles,
                onToggleStaged = onToggleStaged,
                onStageAll = onStageAll,
                onUnstageAll = onUnstageAll
            )
            1 -> HistoryTab(commitHistory = commitHistory)
        }
    }
}

// ─── Tab 切换器 ────────────────────────────────────────────────────

@Composable
private fun ExpandedTabSwitcher(
    selectedTab: Int,
    changesCount: Int,
    onTabSelected: (Int) -> Unit
) {
    val colors = LocalGitHubColors.current
    val labels = listOf(
        if (changesCount > 0) "变更 ($changesCount)" else "变更",
        "提交历史"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.commitWrap, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = selectedTab == i
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) colors.accentBlueAlpha else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onTabSelected(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) colors.accentBlue else colors.textSecondary
                )
            }
        }
    }
}

// ─── 变更 Tab ──────────────────────────────────────────────────────

@Composable
private fun ChangesTab(
    changedFiles: List<ChangedFile>,
    onToggleStaged: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit
) {
    val colors = LocalGitHubColors.current
    val allStaged = changedFiles.isNotEmpty() && changedFiles.all { it.isStaged }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.commitWrap, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        if (changedFiles.isNotEmpty()) {
            // 文件列表标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "变更文件",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textMuted,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (allStaged) "取消全部暂存" else "全部暂存",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentBlue,
                    modifier = Modifier
                        .clickable { if (allStaged) onUnstageAll() else onStageAll() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 文件列表
            changedFiles.forEach { file ->
                ChangedFileRow(file = file, onToggleStaged = { onToggleStaged(file.path) })
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = colors.expandedBorder.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 提交信息输入
        CommitBox()
    }
}

// ─── 变更文件行 ─────────────────────────────────────────────────────

@Composable
private fun ChangedFileRow(
    file: ChangedFile,
    onToggleStaged: () -> Unit
) {
    val colors = LocalGitHubColors.current
    val statusColor = when (file.status) {
        FileChangeStatus.MODIFIED  -> Color(0xFFE08A3C)
        FileChangeStatus.ADDED     -> colors.accentGreen
        FileChangeStatus.DELETED   -> colors.accentRed
        FileChangeStatus.RENAMED   -> colors.accentBlue
        FileChangeStatus.UNTRACKED -> colors.textMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleStaged)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 暂存勾选框
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (file.isStaged) colors.accentBlue else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    if (file.isStaged) colors.accentBlue else colors.textMuted,
                    RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (file.isStaged) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        // 状态徽章
        Text(
            text = file.status.label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = statusColor,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )

        // 文件路径
        val fileName = file.path.substringAfterLast("/")
        val dirPath = file.path.substringBeforeLast("/", "")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (dirPath.isNotEmpty()) {
                Text(
                    text = dirPath,
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─── 历史 Tab ──────────────────────────────────────────────────────

@Composable
private fun HistoryTab(commitHistory: List<CommitRecord>) {
    val colors = LocalGitHubColors.current

    if (commitHistory.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.commitWrap, RoundedCornerShape(10.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无提交记录",
                fontSize = 13.sp,
                color = colors.textMuted
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.commitWrap, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        commitHistory.forEachIndexed { index, commit ->
            CommitHistoryRow(commit = commit)
            if (index < commitHistory.lastIndex) {
                HorizontalDivider(
                    color = colors.expandedBorder.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 2.dp, horizontal = 24.dp)
                )
            }
        }
    }
}

// ─── 提交记录行 ─────────────────────────────────────────────────────

@Composable
private fun CommitHistoryRow(commit: CommitRecord) {
    val colors = LocalGitHubColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 短哈希徽章
        Text(
            text = commit.hash.take(7),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentBlueLight,
            modifier = Modifier
                .background(colors.accentBlueAlpha2, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = commit.shortMessage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!commit.isPushed) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "未推送",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accentRed,
                        modifier = Modifier
                            .background(colors.accentRedAlpha, RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${commit.author} · ${commit.timeAgo}",
                fontSize = 10.sp,
                color = colors.textSecondary
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 操作按钮（Fetch / Pull / Push）
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalGitHubColors.current
    Button(
        onClick = {},
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.actionBtn),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = colors.accentBlueLight,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 12.sp, color = colors.textPrimary)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 提交信息输入框
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CommitBox() {
    val colors = LocalGitHubColors.current
    var commitMessage by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            textStyle = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accentBlue),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.input, RoundedCornerShape(6.dp))
                .padding(8.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (commitMessage.isEmpty()) {
                        Text(
                            text = "输入提交信息 (Commit message)...",
                            fontSize = 13.sp,
                            color = colors.placeholder
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { commitMessage = "" }) {
                Text(
                    text = "还原",
                    color = colors.accentRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlue),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(text = "提交", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 云端仓库搜索栏
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val colors = LocalGitHubColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "搜索",
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(fontSize = 15.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accentBlue),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(text = "搜索云端仓库...", fontSize = 15.sp, color = colors.textSecondary)
                    }
                    innerTextField()
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// 云端仓库卡片
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteRepoCard(
    repo: RemoteRepo,
    modifier: Modifier = Modifier
) {
    val colors = LocalGitHubColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repo.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "★ ${formatStars(repo.stars)}", fontSize = 12.sp, color = colors.textSecondary)
                    Text(text = repo.language, fontSize = 12.sp, color = colors.textSecondary)
                }
            }

            Button(
                onClick = {},
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlueAlpha2),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "克隆",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentBlueLight
                )
            }
        }
    }
}

private fun formatStars(stars: Int): String =
    if (stars >= 1000) String.format("%.1fK", stars / 1000.0) else stars.toString()

// ═══════════════════════════════════════════════════════════════════
// 登录弹窗
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onLogin: (username: String, token: String) -> Unit
) {
    val colors = LocalGitHubColors.current
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(colors.textMuted, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "验证 GitHub 账号",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "请输入您的 Personal Access Token (PAT) 以便同步仓库",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "GitHub 用户名",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textMutedLight
            )
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "例如: YangHuaYong",
                isPassword = false
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "个人访问令牌 (Token)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textMutedLight
            )
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = "ghp_********************************",
                isPassword = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "取消",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onLogin(username, token) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlue),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(text = "验证并登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean
) {
    val colors = LocalGitHubColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontSize = 14.sp, color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accentBlue),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.input, RoundedCornerShape(8.dp))
            .border(1.dp, colors.loginBorder, RoundedCornerShape(8.dp))
            .padding(12.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(text = placeholder, fontSize = 14.sp, color = colors.textSecondary)
                }
                innerTextField()
            }
        }
    )
}
