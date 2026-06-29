// AxiomGitHubRepositoriesScreen.kt
// 完整实现：GitHub Repositories 页面

@file:OptIn(ExperimentalMaterial3Api::class)

package com.axiom.editor.ui.screens.github

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════
// 🖼️ 颜色主题 (Color Theme)
// ═══════════════════════════════════════════════════════════════════

object AxiomColors {
    val Background = Color(0xFF0F111A)
    val CardBackground = Color(0xFF1A1D30)
    val InputBackground = Color(0xFF252942)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF7C82A6)
    val AccentBlue = Color(0xFF2160C4)
    val AccentBlueLight = Color(0xFF4C8BF5)
    val AccentRed = Color(0xFFCF6679)
    val AccentGreen = Color(0xFF107C41)
    val TextMuted = Color(0xFF4A517A)
    val TextMutedLight = Color(0xFFA0A5C4)
    
    // 透明度版本
    val AccentBlueAlpha = Color(0x4D2160C4) // 30% opacity
    val AccentRedAlpha = Color(0x26EF5350)   // 15% opacity
    val AccentBlueAlpha2 = Color(0x264C8BF5) // 15% opacity
}

// ═══════════════════════════════════════════════════════════════════
// 📦 数据模型 (Data Models)
// ═══════════════════════════════════════════════════════════════════

data class LocalRepo(
    val name: String,
    val branch: String,
    val uncommittedChanges: Int = 0,
    val unpushedCommits: Int = 0
)

data class RemoteRepo(
    val name: String,
    val stars: Int,
    val language: String,
    val description: String = ""
)

// ═══════════════════════════════════════════════════════════════════
// 🧠 ViewModel
// ═══════════════════════════════════════════════════════════════════

class GitHubRepositoriesViewModel : androidx.lifecycle.ViewModel() {
    
    var isLoggedIn by mutableStateOf(false)
        private set
    
    var userAvatarUrl by mutableStateOf<String?>(null)
        private set
    
    var expandedRepoName by mutableStateOf<String?>(null)
        private set
    
    var localRepos by mutableStateOf(
        listOf(
            LocalRepo("codemirror6", "main", uncommittedChanges = 2),
            LocalRepo("axiom-editor", "feature/git-integration", unpushedCommits = 1),
            LocalRepo("kotlin-compiler", "develop")
        )
    )
        private set
    
    var remoteRepos by mutableStateOf(
        listOf(
            RemoteRepo("Free-SS-NODES", 142, "Python"),
            RemoteRepo("awesome-kotlin", 12400, "Kotlin"),
            RemoteRepo("JetBrains/kotlin", 48000, "Kotlin"),
            RemoteRepo("flutter/flutter", 168000, "Dart")
        )
    )
        private set
    
    var searchQuery by mutableStateOf("")
        private set
    
    fun toggleRepoExpansion(repoName: String) {
        expandedRepoName = if (expandedRepoName == repoName) null else repoName
    }
    
    fun updateSearchQuery(query: String) {
        searchQuery = query
    }
    
    fun login(username: String, token: String) {
        // 模拟登录逻辑
        isLoggedIn = true
        userAvatarUrl = "https://github.com/identicons/${username.lowercase()}.png"
    }
    
    fun logout() {
        isLoggedIn = false
        userAvatarUrl = null
    }
}

// ═══════════════════════════════════════════════════════════════════
// 🎨 主体页面 (Main Screen)
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubRepositoriesScreen(
    viewModel: GitHubRepositoriesViewModel = remember { GitHubRepositoriesViewModel() }
) {
    val isLoggedIn by remember { viewModel.isLoggedIn }
    val userAvatarUrl by remember { viewModel.userAvatarUrl }
    val expandedRepoName by remember { viewModel.expandedRepoName }
    val localRepos by remember { viewModel.localRepos }
    val remoteRepos by remember { viewModel.remoteRepos }
    val searchQuery by remember { viewModel.searchQuery }
    
    var showLoginSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AxiomColors.Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ─── 顶部导航栏 ───
            GitHubTopBar(
                isLoggedIn = isLoggedIn,
                avatarUrl = userAvatarUrl,
                onAddClick = { showLoginSheet = true }
            )
            
            // ─── 内容区域 ───
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 本地仓库标题
                item {
                    SectionTitle("本地仓库状态 (Local)")
                }
                
                // 本地仓库列表
                items(localRepos, key = { it.name }) { repo ->
                    LocalRepoCard(
                        repo = repo,
                        isExpanded = expandedRepoName == repo.name,
                        onToggle = { viewModel.toggleRepoExpansion(repo.name) },
                        modifier = Modifier.animateItem()
                    )
                }
                
                // 云端仓库标题
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionTitle("云端仓库 (Remote)")
                }
                
                // 搜索栏
                item {
                    RemoteSearchBar(
                        query = searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) }
                    )
                }
                
                // 云端仓库列表
                val filteredRepos = if (searchQuery.isBlank()) {
                    remoteRepos
                } else {
                    remoteRepos.filter { 
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.language.contains(searchQuery, ignoreCase = true)
                    }
                }
                
                items(filteredRepos, key = { it.name }) { repo ->
                    RemoteRepoCard(
                        repo = repo,
                        modifier = Modifier.animateItem()
                    )
                }
                
                // 底部安全区
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
        
        // ─── 登录弹窗 ───
        if (showLoginSheet) {
            LoginBottomSheet(
                sheetState = sheetState,
                onDismiss = { showLoginSheet = false },
                onLogin = { username, token ->
                    viewModel.login(username, token)
                    showLoginSheet = false
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 🔝 顶部导航栏 (Top App Bar)
// ═════════════════════════════════════════════════════��═════════════

@Composable
private fun GitHubTopBar(
    isLoggedIn: Boolean,
    avatarUrl: String?,
    onAddClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "GitHub Repositories",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AxiomColors.TextPrimary
        )
        
        if (isLoggedIn) {
            // 已登录：显示头像
            AsyncImage(
                model = avatarUrl,
                contentDescription = "用户头像",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            // 未登录：显示添加按钮
            IconButton(
                onClick = onAddClick,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(AxiomColors.CardBackground)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "添加账号",
                    tint = AxiomColors.TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 📑 区域标题 (Section Title)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = AxiomColors.TextMuted,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// 💾 本地仓库卡片 (Local Repository Card)
// ═════════════════════���═════════════════════════════════════════════

@Composable
private fun LocalRepoCard(
    repo: LocalRepo,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        label = "borderAnimation"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .then(
                if (isExpanded) {
                    Modifier.border(1.dp, AxiomColors.AccentBlueAlpha, RoundedCornerShape(16.dp))
                } else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) Color(0xFF1D213A) else AxiomColors.CardBackground
        )
    ) {
        Column {
            // ─── 卡片头部 ───
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
                    // 仓库信息
                    Column {
                        Text(
                            text = repo.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AxiomColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CallSplit,
                                contentDescription = null,
                                tint = AxiomColors.AccentBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = repo.branch,
                                fontSize = 12.sp,
                                color = AxiomColors.TextSecondary
                            )
                        }
                    }
                    
                    // 状态徽章
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (repo.uncommittedChanges > 0) {
                            StatusBadge(
                                text = "${repo.uncommittedChanges} 个修改",
                                backgroundColor = AxiomColors.AccentRedAlpha,
                                textColor = Color(0xFFEE5253)
                            )
                        }
                        if (repo.unpushedCommits > 0) {
                            StatusBadge(
                                text = "${repo.unpushedCommits} 个待推送",
                                backgroundColor = AxiomColors.AccentBlueAlpha2,
                                textColor = AxiomColors.AccentBlueLight
                            )
                        }
                    }
                }
            }
            
            // ─── 展开面板 ───
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                )) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LocalRepoExpandedContent(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
                )
            }
        }
    }
}

// ─── 状态徽章 ───
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

// ─── 展开内容 ───
@Composable
private fun LocalRepoExpandedContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 16.dp)) {
        // 远端操作按钮组
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
        
        // 提交区域
        CommitBox()
    }
}

// ─── 远端操作按钮 ───
@Composable
private fun RemoteActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = { /* TODO: Implement */ },
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AxiomColors.InputBackground
        ),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = AxiomColors.AccentBlueLight,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = AxiomColors.TextPrimary
            )
        }
    }
}

// ─── 提交输入框 ───
@Composable
private fun CommitBox() {
    var commitMessage by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        BasicTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = AxiomColors.TextPrimary
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AxiomColors.AccentBlue),
            modifier = Modifier
                .fillMaxWidth()
                .background(AxiomColors.InputBackground, RoundedCornerShape(6.dp))
                .padding(8.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (commitMessage.isEmpty()) {
                        Text(
                            text = "输入提交信息 (Commit message)...",
                            fontSize = 13.sp,
                            color = Color(0xFF52587A)
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
                    color = AxiomColors.AccentRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { /* TODO: Implement commit */ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxiomColors.AccentBlue
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "提交",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 🔍 云端仓库搜索栏 (Remote Search Bar)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteSearchBar(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AxiomColors.CardBackground, RoundedCornerShape(28.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = "搜索",
            tint = AxiomColors.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = AxiomColors.TextPrimary
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AxiomColors.AccentBlue),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索云端仓库...",
                            fontSize = 15.sp,
                            color = AxiomColors.TextSecondary
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// 📦 云端仓库卡片 (Remote Repository Card)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun RemoteRepoCard(
    repo: RemoteRepo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = AxiomColors.CardBackground
        )
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
                    color = AxiomColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "★ ${formatStars(repo.stars)}",
                        fontSize = 12.sp,
                        color = AxiomColors.TextSecondary
                    )
                    Text(
                        text = repo.language,
                        fontSize = 12.sp,
                        color = AxiomColors.TextSecondary
                    )
                }
            }
            
            Button(
                onClick = { /* TODO: Clone repository */ },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AxiomColors.AccentBlueAlpha2
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "克隆",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AxiomColors.AccentBlueLight
                )
            }
        }
    }
}

// 格式化星数显示
private fun formatStars(stars: Int): String {
    return when {
        stars >= 1000 -> String.format("%.1fK", stars / 1000.0)
        else -> stars.toString()
    }
}

// ═══════════════════════════════════════════════════════════════════
// 🔐 登录弹窗 (Login Bottom Sheet)
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onLogin: (username: String, token: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AxiomColors.CardBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(AxiomColors.TextMuted, RoundedCornerShape(2.dp))
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
                color = AxiomColors.TextPrimary
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "请输入您的 Personal Access Token (PAT) 以便同步仓库",
                fontSize = 13.sp,
                color = AxiomColors.TextSecondary
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // 用户名输入框
            Column {
                Text(
                    text = "GitHub 用户名",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AxiomColors.TextMutedLight
                )
                Spacer(modifier = Modifier.height(8.dp))
                LoginTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "例如: YangHuaYong",
                    isPassword = false
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Token 输入框
            Column {
                Text(
                    text = "个人访问令牌 (Token)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AxiomColors.TextMutedLight
                )
                Spacer(modifier = Modifier.height(8.dp))
                LoginTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "ghp_********************************",
                    isPassword = true
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "取消",
                        color = AxiomColors.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { onLogin(username, token) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AxiomColors.AccentBlue
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "验证并登录",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─── 登录输入框 ───
@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            fontSize = 14.sp,
            color = AxiomColors.TextPrimary
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AxiomColors.AccentBlue),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .background(AxiomColors.InputBackground, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 14.sp,
                        color = AxiomColors.TextSecondary
                    )
                }
                innerTextField()
            }
        }
    )
}