package com.ebbinghaus.review.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.User
import com.ebbinghaus.review.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    onExport: () -> Unit,
    onImport: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()

    // UI 状态：控制弹窗
    var showUserSwitcher by remember { mutableStateOf(false) }
    var showCreateUserDialog by remember { mutableStateOf(false) }

    // 个性化状态 (暂存本地，实际可存 DataStore)
    var useDarkWallpaper by remember { mutableStateOf(true) }

    Scaffold { innerPadding ->
        // 使用 Box 实现背景图层叠
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. 全局背景图 (支持切换)
            // 这里可以用 coil 加载网络图，或者本地资源
            // 示例使用纯色渐变模拟 "更好背景"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = if (useDarkWallpaper)
                                listOf(DarkWallpaperStart, DarkWallpaperEnd)
                            else
                                listOf(LightWallpaperStart, LightWallpaperEnd)
                        )
                    )
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding
            ) {
                // === 头部用户信息 ===
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp, bottom = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 头像
                        Surface(
                            shape = CircleShape,
                            modifier = Modifier.size(100.dp).clickable { showUserSwitcher = true },
                            shadowElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.background(Color.LightGray)) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(60.dp), tint = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // 用户名 + 切换角标
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .clickable { showUserSwitcher = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = currentUser?.name ?: stringResource(R.string.not_logged_in),
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
                        }
                    }
                }

                // === 内容区域 (白色圆角背景) ===
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp)
                    ) {
                        Text(stringResource(R.string.data_management), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))

                        // 功能入口 Grid
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ActionItem(icon = Icons.Outlined.Delete, label = stringResource(R.string.recy__bin), color = ActionItemRed) {
                                navController.navigate("trash")
                            }
                            ActionItem(icon = Icons.Default.Share, label = stringResource(R.string.export_data), color = ActionItemBlue) {
                                onExport()
                            }
                            ActionItem(icon = Icons.Default.Add, label = stringResource(R.string.import_backup), color = ActionItemGreen) {
                                onImport()
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Text(stringResource(R.string.personalization), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))

                        // 个性化设置项
                        SettingSwitchItem(stringResource(R.string.deep_starry_sky_background), useDarkWallpaper) { useDarkWallpaper = it }
                        SettingItem(stringResource(R.string.font_size), stringResource(R.string.standard))

                        Spacer(modifier = Modifier.height(32.dp))
                        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.app_version), color = Color.Gray)
                    }
                }
            }
        }

        // 用户切换弹窗
        if (showUserSwitcher) {
            AlertDialog(
                onDismissRequest = { showUserSwitcher = false },
                title = { Text(stringResource(R.string.switch_user)) },
                text = {
                    Column {
                        allUsers.forEach { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.switchUser(user)
                                        showUserSwitcher = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = user.isCurrent, onClick = null)
                                Text(user.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        TextButton(onClick = { showCreateUserDialog = true }) {
                            Text(stringResource(R.string.add_new_user))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showUserSwitcher = false }) { Text(stringResource(R.string.close)) } }
            )
        }

        // 新建用户弹窗
        if (showCreateUserDialog) {
            var newName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreateUserDialog = false },
                title = { Text(stringResource(R.string.create_new_user)) },
                text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.nickname)) }) },
                confirmButton = {
                    Button(onClick = {
                        if (newName.isNotBlank()) {
                            viewModel.createUser(newName)
                            showCreateUserDialog = false
                        }
                    }) { Text(stringResource(R.string.create)) }
                }
            )
        }
    }
}

@Composable
fun ActionItem(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingSwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingItem(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}
