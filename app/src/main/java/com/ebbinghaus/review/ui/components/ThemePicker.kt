package com.ebbinghaus.review.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ebbinghaus.review.ui.theme.AppThemeConfig
import com.ebbinghaus.review.ui.theme.AppThemeFont
import com.ebbinghaus.review.ui.theme.AppThemePreset
import com.ebbinghaus.review.ui.theme.resolveAppTheme

@Composable
fun ThemePicker(
    config: AppThemeConfig,
    onThemeChange: (AppThemeConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var editCustom by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text("主题", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(AppThemePreset.entries, key = AppThemePreset::storageId) { preset ->
                ThemePreviewCard(
                    preset = preset,
                    config = config,
                    selected = config.preset == preset,
                    onClick = {
                        if (preset == AppThemePreset.CUSTOM) {
                            editCustom = true
                        } else {
                            onThemeChange(config.copy(preset = preset))
                        }
                    }
                )
            }
        }
        Text(
            text = when (config.preset) {
                AppThemePreset.WECHAT -> "固定浅色 · 白色与浅绿"
                AppThemePreset.TWITTER -> "固定深色 · 黑色与白色"
                AppThemePreset.MINIMAL -> "跟随系统 · 中性灰阶"
                AppThemePreset.CUSTOM -> "${if (config.customDark) "深色" else "浅色"} · ${config.customFont.displayName}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (editCustom) {
        CustomThemeDialog(
            initial = config.copy(preset = AppThemePreset.CUSTOM),
            onDismiss = { editCustom = false },
            onSave = {
                onThemeChange(it)
                editCustom = false
            }
        )
    }
}

@Composable
private fun ThemePreviewCard(
    preset: AppThemePreset,
    config: AppThemeConfig,
    selected: Boolean,
    onClick: () -> Unit
) {
    val previewConfig = config.copy(preset = preset)
    val resolved = resolveAppTheme(systemDark = false, config = previewConfig)
    val scheme = resolved.colorScheme
    Column(
        modifier = Modifier
            .width(116.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "选择 ${preset.displayName} 主题"
                this.selected = selected
                role = Role.RadioButton
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(9.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(38.dp).align(Alignment.TopCenter),
                color = scheme.surface,
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(scheme.primary))
                    Spacer(Modifier.width(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth(0.85f).height(3.dp).background(scheme.onSurface))
                        Box(Modifier.fillMaxWidth(0.55f).height(3.dp).background(scheme.onSurfaceVariant))
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { index ->
                    Box(
                        Modifier.size(width = 18.dp, height = 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (index == 0) scheme.primary else scheme.outlineVariant)
                    )
                }
            }
        }
        Text(
            preset.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp)
        )
    }
}

@Composable
private fun CustomThemeDialog(
    initial: AppThemeConfig,
    onDismiss: () -> Unit,
    onSave: (AppThemeConfig) -> Unit
) {
    var working by remember(initial) { mutableStateOf(initial) }
    var editingRole by remember { mutableStateOf<ThemeColorRole?>(null) }
    val preview = resolveAppTheme(systemDark = false, config = working).colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义主题") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).testTag("custom_theme_editor_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !working.customDark,
                            onClick = { working = working.copy(customDark = false) },
                            label = { Text("浅色基底") }
                        )
                        FilterChip(
                            selected = working.customDark,
                            onClick = { working = working.copy(customDark = true) },
                            label = { Text("深色基底") }
                        )
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                        color = preview.background,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                color = preview.surface,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(18.dp).clip(CircleShape).background(preview.primary))
                                    Spacer(Modifier.width(8.dp))
                                    Text("主题预览", color = preview.onSurface, fontFamily = working.customFont.family)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.fillMaxWidth(0.65f).height(5.dp).clip(CircleShape).background(preview.primary))
                        }
                    }
                }
                items(ThemeColorRole.entries, key = ThemeColorRole::name) { role ->
                    val color = role.colorFrom(working, preview)
                    AppListRow(
                        headline = role.label,
                        supporting = role.description,
                        onClick = { editingRole = role },
                        leading = {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(color)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .semantics { contentDescription = "${role.label}色样" }
                            )
                        }
                    )
                }
                item {
                    Text("字体", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                }
                items(AppThemeFont.entries, key = AppThemeFont::storageId) { font ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { working = working.copy(customFont = font) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = working.customFont == font,
                            onClick = { working = working.copy(customFont = font) }
                        )
                        Column {
                            Text(font.displayName, fontFamily = font.family)
                            Text("记忆轨道 Aa 123", style = MaterialTheme.typography.bodySmall, fontFamily = font.family)
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            working = AppThemeConfig(
                                preset = AppThemePreset.CUSTOM,
                                customDark = working.customDark,
                                customFont = AppThemeFont.SYSTEM
                            )
                        }
                    ) { Text("恢复安全默认值") }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(working) }) { Text("保存主题") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    editingRole?.let { role ->
        ColorEditorDialog(
            title = role.label,
            initial = role.colorFrom(working, preview),
            onDismiss = { editingRole = null },
            onSave = { color ->
                working = role.apply(working, color.toArgb().toLong() and 0xFFFFFFFFL)
                editingRole = null
            }
        )
    }
}

private enum class ThemeColorRole(val label: String, val description: String) {
    BACKGROUND("页面背景", "页面画布与列表间隔"),
    SURFACE("内容面", "卡片、顶部栏与底部菜单"),
    PRIMARY("强调色", "按钮、进度与选中状态"),
    TEXT("文字色", "正文与标题");

    fun colorFrom(config: AppThemeConfig, preview: androidx.compose.material3.ColorScheme): Color = when (this) {
        BACKGROUND -> config.customBackground?.let(::Color) ?: preview.background
        SURFACE -> config.customSurface?.let(::Color) ?: preview.surface
        PRIMARY -> config.customPrimary?.let(::Color) ?: preview.primary
        TEXT -> config.customText?.let(::Color) ?: preview.onBackground
    }

    fun apply(config: AppThemeConfig, argb: Long): AppThemeConfig = when (this) {
        BACKGROUND -> config.copy(customBackground = argb)
        SURFACE -> config.copy(customSurface = argb)
        PRIMARY -> config.copy(customPrimary = argb)
        TEXT -> config.copy(customText = argb)
    }
}

@Composable
private fun ColorEditorDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onSave: (Color) -> Unit
) {
    var red by remember(initial) { mutableStateOf((initial.red * 255).toInt().toString()) }
    var green by remember(initial) { mutableStateOf((initial.green * 255).toInt().toString()) }
    var blue by remember(initial) { mutableStateOf((initial.blue * 255).toInt().toString()) }
    fun channel(value: String): Int = value.toIntOrNull()?.coerceIn(0, 255) ?: 0
    fun color(): Color = Color(channel(red), channel(green), channel(blue))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(MaterialTheme.shapes.medium).background(color()))
                ColorChannelField("红", red) { red = it }
                ColorChannelField("绿", green) { green = it }
                ColorChannelField("蓝", blue) { blue = it }
            }
        },
        confirmButton = { Button(onClick = { onSave(color()) }) { Text("应用颜色") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ColorChannelField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) onChange(it) },
        label = { Text(label) },
        singleLine = true
    )
}
