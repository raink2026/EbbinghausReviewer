package com.ebbinghaus.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebbinghaus.review.data.migration.LegacyMigrationStatus
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.GroupedSection
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositorySettingsScreen(
    onBack: () -> Unit,
    viewModel: RepositorySettingsViewModel = viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val profile by viewModel.currentProfile.collectAsState()
    val repository by viewModel.currentRepository.collectAsState()
    val checkpoint by viewModel.checkpoint.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val migrationPreview by viewModel.migrationPreview.collectAsState()
    val migrationStatus by viewModel.migrationStatus.collectAsState()
    val cacheUsage by viewModel.cacheUsage.collectAsState()

    var displayName by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repositoryName by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var timezone by remember { mutableStateOf("Asia/Shanghai") }
    var token by remember { mutableStateOf("") }
    var autoSync by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(profile?.profileId, repository?.repositoryId) {
        displayName = profile?.displayName.orEmpty()
        timezone = profile?.timezone ?: "Asia/Shanghai"
        owner = repository?.owner.orEmpty()
        repositoryName = repository?.name.orEmpty()
        branch = repository?.branch ?: "main"
        autoSync = repository?.autoSync ?: true
        wifiOnly = repository?.wifiOnly ?: false
        token = ""
        viewModel.loadMigrationStatus()
        viewModel.refreshCacheUsage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "仓库与同步", onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GroupedSection(title = "档案与仓库") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = profileMenuExpanded,
                        onExpandedChange = { profileMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = profile?.displayName ?: "新档案",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("当前档案") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false }
                        ) {
                            profiles.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.displayName) },
                                    onClick = {
                                        viewModel.switchProfile(option.profileId)
                                        profileMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("档案名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        timezone,
                        { timezone = it },
                        readOnly = repository != null,
                        label = { Text(if (repository == null) "固定 IANA 时区" else "固定 IANA 时区（仓库身份）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(owner, { owner = it.trim() }, label = { Text("Owner") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(repositoryName, { repositoryName = it.trim() }, label = { Text("Repository") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(branch, { branch = it.trim() }, label = { Text("分支") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token,
                        { token = it.trim() },
                        label = { Text(if (repository == null) "Gitee 私人访问令牌" else "替换令牌") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            viewModel.connect(
                                RepositoryConnectRequest(
                                    displayName, owner, repositoryName, branch, timezone, token, autoSync, wifiOnly
                                )
                            ) { result -> if (result.isSuccess) token = "" }
                        },
                        enabled = !busy && token.isNotBlank() && displayName.isNotBlank() && owner.isNotBlank() &&
                            repositoryName.isNotBlank() && branch.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (repository == null) "测试并绑定" else "测试并保存") }
                }
            }

            GroupedSection(title = "同步偏好") {
                SettingToggle("自动同步", autoSync) {
                    autoSync = it
                    if (repository != null) viewModel.updateSyncPreferences(autoSync, wifiOnly)
                }
                SettingToggle("仅 Wi-Fi 同步", wifiOnly) {
                    wifiOnly = it
                    if (repository != null) viewModel.updateSyncPreferences(autoSync, wifiOnly)
                }
            }

            GroupedSection(title = "同步状态") {
                StatusRow("当前状态", repository?.syncState ?: "未绑定")
                StatusRow("远端提交", checkpoint?.remoteCommitSha ?: "-")
                StatusRow("待推送", pendingCount.toString())
                StatusRow("最近拉取", formatTime(checkpoint?.lastPullAt))
                StatusRow("最近推送", formatTime(checkpoint?.lastPushAt))
                repository?.lastSyncError?.let { StatusRow("最近错误", it, error = true) }
                message?.let { StatusRow("结果", it) }
            }

            GroupedSection(title = "本地缓存") {
                StatusRow("缓存占用", formatCacheUsage(cacheUsage.fileCount, cacheUsage.byteSize))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedButton(
                        onClick = viewModel::cleanReconstructibleCache,
                        enabled = !busy && profile != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.padding(3.dp))
                        Text("清理可重建缓存")
                    }
                }
            }

            GroupedSection(title = "旧数据迁移") {
                StatusRow("目标档案", profile?.displayName ?: "-")
                StatusRow("迁移状态", migrationStatusLabel(migrationStatus))
                migrationPreview?.let { preview ->
                    StatusRow("旧数据", preview.totalItems.toString())
                    StatusRow("可迁移笔记", preview.convertedNotes.toString())
                    StatusRow("未迁移的删除笔记", preview.skippedDeletedItems.toString())
                    StatusRow("资产路径", preview.assetPaths.toString())
                    StatusRow("复习事件", preview.eventCount.toString())
                    preview.blockingIssues.forEach { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    preview.scheduleDifferences.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (migrationStatus in setOf(LegacyMigrationStatus.NOT_STARTED, LegacyMigrationStatus.ROLLED_BACK)) {
                        OutlinedButton(
                            onClick = viewModel::previewLegacyMigration,
                            enabled = !busy && repository?.isBound == true,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("检查旧数据") }
                        migrationPreview?.takeIf { it.canQueue }?.let { preview ->
                            Button(
                                onClick = { viewModel.queueLegacyMigration(preview.scheduleDifferences.isNotEmpty()) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (preview.scheduleDifferences.isEmpty()) "迁移到当前档案" else "采用重放排程并迁移")
                            }
                        }
                    }
                    if (migrationStatus == LegacyMigrationStatus.QUEUED) {
                        Button(
                            onClick = viewModel::publishLegacyMigration,
                            enabled = !busy && pendingCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("发布迁移") }
                        OutlinedButton(
                            onClick = viewModel::rollbackLegacyMigration,
                            enabled = !busy && checkpoint?.lastPushAt == null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("首推前回滚") }
                        OutlinedButton(
                            onClick = viewModel::rehearseLegacyRestore,
                            enabled = !busy && pendingCount == 0 && checkpoint?.lastPushAt != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("运行空投影恢复演练") }
                    }
                }
            }

            GroupedSection(title = "连接操作") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::requestManualSync,
                            enabled = repository?.isBound == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("手动同步")
                        }
                        OutlinedButton(
                            onClick = viewModel::clearToken,
                            enabled = repository != null,
                            modifier = Modifier.weight(1f)
                        ) { Text("清除令牌") }
                    }
                    OutlinedButton(
                        onClick = viewModel::unbind,
                        enabled = repository?.isBound == true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("解除绑定并保留本地数据", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusRow(label: String, value: String, error: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
            textAlign = TextAlign.End,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
} ?: "-"

private fun formatCacheUsage(files: Int, bytes: Long): String {
    val size = when {
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    return "$size / $files 个文件"
}

private fun migrationStatusLabel(status: LegacyMigrationStatus): String = when (status) {
    LegacyMigrationStatus.NOT_STARTED -> "未开始"
    LegacyMigrationStatus.QUEUED -> "已排队"
    LegacyMigrationStatus.RESTORE_VERIFIED -> "恢复演练已通过"
    LegacyMigrationStatus.ROLLED_BACK -> "已回滚"
}
