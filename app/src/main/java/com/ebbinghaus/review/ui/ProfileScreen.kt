package com.ebbinghaus.review.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.User
import com.ebbinghaus.review.ui.components.AppListRow
import com.ebbinghaus.review.ui.components.GroupedSection
import com.ebbinghaus.review.ui.components.ThemePicker
import com.ebbinghaus.review.ui.theme.AppThemeConfig
import com.ebbinghaus.review.ui.theme.AppIcons
import com.ebbinghaus.review.ui.theme.toAppThemeConfig

@Composable
fun ProfileScreen(
    navController: NavController,
    onExport: () -> Unit,
    onImport: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()
    var showUserSwitcher by remember { mutableStateOf(false) }
    var showCreateUserDialog by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                UserHeader(
                    name = currentUser?.name ?: stringResource(R.string.not_logged_in),
                    onClick = { showUserSwitcher = true }
                )
            }
            item {
                GroupedSection(title = stringResource(R.string.data_management)) {
                    ProfileActionRow(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.recy__bin),
                        onClick = { navController.navigate("trash") }
                    )
                    ProfileActionRow(
                        icon = Icons.Default.Share,
                        title = stringResource(R.string.export_data),
                        onClick = onExport
                    )
                    ProfileActionRow(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.import_backup),
                        onClick = onImport
                    )
                    ProfileActionRow(
                        icon = Icons.Default.Settings,
                        title = "仓库与同步",
                        supporting = "Gitee 档案、同步状态与迁移",
                        onClick = { navController.navigate("repository_settings") },
                        showDivider = false
                    )
                }
            }
            item {
                GroupedSection(title = stringResource(R.string.personalization)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        ThemePicker(
                            config = currentUser?.toAppThemeConfig() ?: AppThemeConfig(),
                            onThemeChange = viewModel::updateTheme
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.font_size), style = MaterialTheme.typography.bodyLarge)
                        FontScaleSlider(
                            scale = currentUser?.fontScale ?: 1f,
                            onScaleChanged = viewModel::updateFontScale
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MenuCustomizationSection(
                        currentUser = currentUser,
                        onUpdateSettings = { showLabels, home, plan, profile ->
                            viewModel.updateMenuSettings(showLabels, home, plan, profile)
                        }
                    )
                }
            }
            item {
                GroupedSection(title = stringResource(R.string.about)) {
                    AppListRow(
                        headline = "艾宾浩斯复习助手",
                        supporting = stringResource(R.string.app_version),
                        showDivider = false
                    )
                }
            }
        }
    }

    if (showUserSwitcher) {
        AlertDialog(
            onDismissRequest = { showUserSwitcher = false },
            title = { Text(stringResource(R.string.switch_user)) },
            text = {
                Column {
                    allUsers.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.switchUser(user)
                                showUserSwitcher = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = user.isCurrent, onClick = null)
                            Text(user.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    TextButton(onClick = {
                        showUserSwitcher = false
                        showCreateUserDialog = true
                    }) { Text(stringResource(R.string.add_new_user)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUserSwitcher = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (showCreateUserDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateUserDialog = false },
            title = { Text(stringResource(R.string.create_new_user)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.nickname)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createUser(newName.trim())
                        showCreateUserDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateUserDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun UserHeader(name: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前档案 · 点击切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    AppListRow(
        headline = title,
        supporting = supporting,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            }
        },
        trailing = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        },
        showDivider = showDivider
    )
}

@Composable
fun MenuCustomizationSection(
    currentUser: User?,
    onUpdateSettings: (Boolean?, String?, String?, String?) -> Unit
) {
    var showCustomizeDialog by remember { mutableStateOf(false) }
    if (currentUser == null) return

    AppListRow(
        headline = stringResource(R.string.show_menu_labels),
        trailing = {
            Switch(
                checked = currentUser.showMenuLabels,
                onCheckedChange = { onUpdateSettings(it, null, null, null) }
            )
        }
    )
    AppListRow(
        headline = stringResource(R.string.customize_icons),
        onClick = { showCustomizeDialog = true },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.getIcon(currentUser.homeIcon, Icons.Default.Home), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                Icon(AppIcons.getIcon(currentUser.planIcon, Icons.Default.Settings), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                Icon(AppIcons.getIcon(currentUser.profileIcon, Icons.Default.Person), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.outline)
            }
        },
        showDivider = false
    )

    if (showCustomizeDialog) {
        MenuIconsConfigurationDialog(
            currentUser = currentUser,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showCustomizeDialog = false }
        )
    }
}

@Composable
private fun MenuIconsConfigurationDialog(
    currentUser: User,
    onUpdateSettings: (Boolean?, String?, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var showIconPicker by remember { mutableStateOf<String?>(null) }
    val target = showIconPicker
    if (target != null) {
        IconPickerDialog(
            currentIconName = when (target) {
                "home" -> currentUser.homeIcon
                "plan" -> currentUser.planIcon
                else -> currentUser.profileIcon
            },
            onIconSelected = { icon ->
                when (target) {
                    "home" -> onUpdateSettings(null, icon, null, null)
                    "plan" -> onUpdateSettings(null, null, icon, null)
                    else -> onUpdateSettings(null, null, null, icon)
                }
                showIconPicker = null
            },
            onDismiss = { showIconPicker = null }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.customize_icons)) },
            text = {
                Column {
                    MenuItemSetting(stringResource(R.string.icon_home), currentUser.homeIcon) { showIconPicker = "home" }
                    MenuItemSetting(stringResource(R.string.icon_plan), currentUser.planIcon) { showIconPicker = "plan" }
                    MenuItemSetting(stringResource(R.string.icon_profile), currentUser.profileIcon) { showIconPicker = "profile" }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
        )
    }
}

@Composable
private fun MenuItemSetting(label: String, iconName: String, onClick: () -> Unit) {
    AppListRow(
        headline = label,
        onClick = onClick,
        leading = { Icon(AppIcons.getIcon(iconName, Icons.Default.Home), contentDescription = null) },
        trailing = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline) }
    )
}

private val IconNameTranslation = mapOf(
    "Home" to "首页", "DateRange" to "日历", "Person" to "用户", "Star" to "星标",
    "Settings" to "设置", "Menu" to "菜单", "Info" to "信息", "Favorite" to "收藏",
    "Search" to "搜索", "Edit" to "编辑", "List" to "列表", "Notifications" to "通知",
    "CheckCircle" to "完成", "Face" to "表情", "AccountCircle" to "账户",
    "Home (Outlined)" to "首页（描边）", "DateRange (Outlined)" to "日历（描边）",
    "Person (Outlined)" to "用户（描边）", "Star (Outlined)" to "星标（描边）",
    "Settings (Outlined)" to "设置（描边）", "Info (Outlined)" to "信息（描边）",
    "Favorite (Outlined)" to "收藏（描边）", "Edit (Outlined)" to "编辑（描边）",
    "List (Outlined)" to "列表（描边）", "Face (Outlined)" to "表情（描边）",
    "AccountCircle (Outlined)" to "账户（描边）"
)

@Composable
private fun IconPickerDialog(currentIconName: String, onIconSelected: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_icon)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(AppIcons.AvailableIcons.toList()) { (name, icon) ->
                    val selected = name == currentIconName
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onIconSelected(name) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Text(IconNameTranslation[name] ?: name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun FontScaleSlider(scale: Float, onScaleChanged: (Float) -> Unit) {
    Column {
        Slider(value = scale, onValueChange = onScaleChanged, valueRange = 0.8f..1.5f, steps = 6)
        Text(
            text = "${(scale * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
