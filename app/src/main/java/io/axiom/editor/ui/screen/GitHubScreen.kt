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
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
                        LocalRepoCard(
                            repo = repo,
                            isExpanded = expandedRepoName == repo.name,
                            onToggle = { viewModel.toggleRepoExpansion(repo.name) },
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
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    backgroundColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color
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

@Composable
private fun LocalRepoExpandedContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 16.dp)) {
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

        Spacer(modifier = Modifier.height(12.dp))
        CommitBox()
    }
}

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

@Composable
private fun CommitBox() {
    val colors = LocalGitHubColors.current
    var commitMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.commitWrap, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
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
                Text(text = "还原", color = colors.accentRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                Text(text = "克隆", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.accentBlueLight)
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
            Text(text = "验证 GitHub 账号", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = "请输入您的 Personal Access Token (PAT) 以便同步仓库", fontSize = 13.sp, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(20.dp))

            Text(text = "GitHub 用户名", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textMutedLight)
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(value = username, onValueChange = { username = it }, placeholder = "例如: YangHuaYong", isPassword = false)

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "个人访问令牌 (Token)", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textMutedLight)
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(value = token, onValueChange = { token = it }, placeholder = "ghp_********************************", isPassword = true)

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "取消", color = colors.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
