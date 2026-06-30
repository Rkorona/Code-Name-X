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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // 登录成功后自动关闭弹窗
        LaunchedEffect(viewModel.isLoggedIn) {
            if (viewModel.isLoggedIn && showLoginSheet) {
                onLoginSheetDismiss()
            }
        }

        val context = LocalContext.current

        LaunchedEffect(viewModel.cloneMessage) {
            if (viewModel.cloneMessage != null) {
                kotlinx.coroutines.delay(3500)
                viewModel.dismissCloneMessage()
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            val filteredRepos = if (viewModel.searchQuery.isBlank()) {
                viewModel.remoteRepos
            } else {
                viewModel.remoteRepos.filter {
                    it.name.contains(viewModel.searchQuery, ignoreCase = true) ||
                        it.fullName.contains(viewModel.searchQuery, ignoreCase = true) ||
                        it.language.contains(viewModel.searchQuery, ignoreCase = true) ||
                        it.description.contains(viewModel.searchQuery, ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 已登录时显示用户信息卡片
                if (viewModel.isLoggedIn) {
                    item {
                        UserProfileCard(
                            userName = viewModel.userName,
                            avatarUrl = viewModel.userAvatarUrl,
                            localRepoCount = viewModel.localRepos.size,
                            remoteRepoCount = viewModel.remoteRepos.size,
                            onLogout = viewModel::logout
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                item { SectionTitle("本地仓库状态 (Local)") }

                items(viewModel.localRepos, key = { it.name }) { repo ->
                    val isExpanded = viewModel.expandedRepoName == repo.name
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
                        query = viewModel.searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) }
                    )
                }

                if (viewModel.reposLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = colors.accentBlue,
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                } else if (viewModel.isLoggedIn && filteredRepos.isEmpty() && viewModel.searchQuery.isBlank()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无云端仓库",
                                fontSize = 13.sp,
                                color = colors.textMuted
                            )
                        }
                    }
                } else {
                    items(filteredRepos, key = { it.fullName }) { repo ->
                        val isCloning = viewModel.cloningRepoName == repo.fullName
                        val isCloned  = viewModel.localRepos.any { it.name == repo.name }
                        RemoteRepoCard(
                            repo          = repo,
                            isCloning     = isCloning,
                            cloneProgress = if (isCloning) viewModel.cloneProgress else 0f,
                            isCloned      = isCloned,
                            onClone       = { viewModel.cloneRepo(repo, context) },
                            modifier      = Modifier.animateItem()
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }

            if (showLoginSheet) {
                LoginBottomSheet(
                    sheetState = sheetState,
                    loginState = viewModel.loginState,
                    loginError = viewModel.loginError,
                    onDismiss = {
                        viewModel.clearLoginError()
                        onLoginSheetDismiss()
                    },
                    onStartOAuth = { viewModel.startOAuthFlow(context) }
                )
            }

            // 克隆结果通知横条
            AnimatedVisibility(
                visible = viewModel.cloneMessage != null,
                enter = fadeIn() + expandVertically(expandFrom = androidx.compose.ui.Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Bottom),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val msg     = viewModel.cloneMessage ?: ""
                val isError = viewModel.cloneIsError
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isError) colors.accentRedAlpha
                            else colors.accentBlueAlpha
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Rounded.Info else Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (isError) colors.accentRed else colors.accentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = msg,
                        fontSize = 13.sp,
                        color = if (isError) colors.accentRed else colors.accentBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 用户信息卡片
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun UserProfileCard(
    userName: String,
    avatarUrl: String?,
    localRepoCount: Int,
    remoteRepoCount: Int,
    onLogout: () -> Unit
) {
    val colors = LocalGitHubColors.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.accentBlueAlpha)
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = "GitHub 头像",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        tint = colors.accentBlue,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(26.dp)
                    )
                }
            }

            // 用户信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(colors.accentGreen, CircleShape)
                    )
                    Text(
                        text = "已连接 GitHub",
                        fontSize = 11.sp,
                        color = colors.accentGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(label = "$localRepoCount 本地仓库", colors.textSecondary)
                    StatChip(label = "$remoteRepoCount 云端仓库", colors.textSecondary)
                }
            }

            // 退出按钮
            IconButton(
                onClick = onLogout,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExitToApp,
                    contentDescription = "退出登录",
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, color: Color) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
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
private fun StatusBadge(text: String, backgroundColor: Color, textColor: Color) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RemoteActionButton(Icons.Rounded.Downloading, "Fetch", Modifier.weight(1f))
            RemoteActionButton(Icons.Rounded.ArrowDownward, "Pull", Modifier.weight(1f))
            RemoteActionButton(Icons.Rounded.ArrowUpward, "Push", Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(10.dp))

        ExpandedTabSwitcher(
            selectedTab = selectedTab,
            changesCount = changedFiles.size,
            onTabSelected = onTabSwitch
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (selectedTab) {
            0 -> ChangesTab(changedFiles, onToggleStaged, onStageAll, onUnstageAll)
            1 -> HistoryTab(commitHistory)
        }
    }
}

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
        CommitBox()
    }
}

@Composable
private fun ChangedFileRow(file: ChangedFile, onToggleStaged: () -> Unit) {
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
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
        Text(
            text = file.status.label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = statusColor,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        val fileName = file.path.substringAfterLast("/")
        val dirPath  = file.path.substringBeforeLast("/", "")
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
            Text(text = "暂无提交记录", fontSize = 13.sp, color = colors.textMuted)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.commitWrap, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        commitHistory.forEachIndexed { index, commit ->
            CommitHistoryRow(commit)
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
// 操作按钮 / 提交框
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
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
            Icon(icon, label, tint = colors.accentBlueLight, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 12.sp, color = colors.textPrimary)
        }
    }
}

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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { commitMessage = "" }) {
                Text("还原", color = colors.accentRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlue),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("提交", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 云端搜索栏
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteSearchBar(query: String, onQueryChange: (String) -> Unit) {
    val colors = LocalGitHubColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, "搜索", tint = colors.textSecondary, modifier = Modifier.size(20.dp))
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
                        Text("搜索云端仓库...", fontSize = 15.sp, color = colors.textSecondary)
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
    isCloning: Boolean,
    cloneProgress: Float,
    isCloned: Boolean,
    onClone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalGitHubColors.current
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = repo.fullName.ifEmpty { repo.name },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (repo.isPrivate) {
                            Text(
                                text = "私有",
                                fontSize = 10.sp,
                                color = colors.textMuted,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .background(colors.accentBlueAlpha, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (repo.description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = repo.description,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (repo.stars > 0) {
                            Text("★ ${formatStars(repo.stars)}", fontSize = 12.sp, color = colors.textSecondary)
                        }
                        if (repo.language.isNotEmpty()) {
                            Text(repo.language, fontSize = 12.sp, color = colors.textSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                when {
                    isCloning -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = colors.accentBlue,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(cloneProgress * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = colors.textMuted
                            )
                        }
                    }
                    isCloned -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = colors.accentBlueAlpha
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accentBlueLight,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("已克隆", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accentBlueLight)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onClone,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentBlueAlpha2),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("克隆", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accentBlueLight)
                        }
                    }
                }
            }

            if (isCloning && cloneProgress > 0f) {
                LinearProgressIndicator(
                    progress = { cloneProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = colors.accentBlue,
                    trackColor = colors.accentBlueAlpha
                )
            }
        }
    }
}

private fun formatStars(stars: Int): String =
    if (stars >= 1000) String.format("%.1fK", stars / 1000.0) else stars.toString()

// ═══════════════════════════════════════════════════════════════════
// 登录弹窗（GitHub OAuth）
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginBottomSheet(
    sheetState: SheetState,
    loginState: GitHubLoginState,
    loginError: String,
    onDismiss: () -> Unit,
    onStartOAuth: () -> Unit
) {
    val colors = LocalGitHubColors.current
    val isLoading = loginState == GitHubLoginState.Loading

    ModalBottomSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题区
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(colors.accentBlueAlpha, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        tint = colors.accentBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "连接 GitHub 账号",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "通过 GitHub OAuth 安全授权",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 说明提示框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentBlueAlpha2, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = colors.accentBlueLight,
                    modifier = Modifier.size(15.dp).padding(top = 1.dp)
                )
                Text(
                    text = "点击下方按钮将跳转到 GitHub 授权页面，授权后自动返回完成登录。",
                    fontSize = 12.sp,
                    color = colors.accentBlueLight,
                    lineHeight = 18.sp
                )
            }

            // 错误提示
            if (loginState == GitHubLoginState.Error && loginError.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentRedAlpha, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "●", fontSize = 8.sp, color = colors.accentRed)
                    Text(text = loginError, fontSize = 12.sp, color = colors.accentRed)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 登录按钮
            Button(
                onClick = { if (!isLoading) onStartOAuth() },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentBlue,
                    disabledContainerColor = colors.accentBlueAlpha
                ),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在登录...", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("使用 GitHub 登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(
                onClick = { if (!isLoading) onDismiss() },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "取消",
                    color = if (isLoading) colors.textMuted else colors.textSecondary,
                    fontSize = 14.sp
                )
            }
        }
    }
}
