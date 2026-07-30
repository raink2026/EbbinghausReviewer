package com.ebbinghaus.review.ui.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.data.sync.protocol.MarkdownContent
import com.ebbinghaus.review.data.sync.protocol.ResolvedMarkdownAsset
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.markdown.MarkdownRenderer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemScreen(navController: NavController, viewModel: MainViewModel) {
    MarkdownEditorScreen(navController, viewModel, noteId = null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMarkdownScreen(navController: NavController, viewModel: MainViewModel, noteId: String) {
    MarkdownEditorScreen(navController, viewModel, noteId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownEditorScreen(
    navController: NavController,
    viewModel: MainViewModel,
    noteId: String?
) {
    val profile by viewModel.currentProfile.collectAsState()
    var editorValue by remember { mutableStateOf(TextFieldValue("# \n\n")) }
    var preview by remember { mutableStateOf(false) }
    var resolvedAssets by remember { mutableStateOf<List<ResolvedMarkdownAsset>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(noteId) {
        if (noteId != null) {
            busy = true
            val detail = viewModel.getMarkdownNote(noteId)
            if (detail == null) {
                error = "无法加载活动修订"
            } else {
                editorValue = TextFieldValue(
                    detail.markdownBody,
                    TextRange(detail.markdownBody.length)
                )
                resolvedAssets = detail.assets
            }
            busy = false
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            runCatching {
                var value = editorValue
                uris.forEach { uri ->
                    val asset = viewModel.importMarkdownAsset(uri)
                    val inserted = MarkdownContent.insertAssetReference(
                        value.text,
                        value.selection.end,
                        asset
                    )
                    val newCursor = value.selection.end +
                        "![image](../assets/${asset.sha256}.${asset.extension})".length
                    value = TextFieldValue(inserted, TextRange(newCursor))
                }
                editorValue = value
            }.onFailure { error = it.message }
            busy = false
        }
    }

    LaunchedEffect(preview, editorValue.text) {
        if (preview) resolvedAssets = viewModel.resolveMarkdownAssets(editorValue.text)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (noteId == null) "新建 Markdown 笔记" else "修订 Markdown 笔记") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("源码", "预览").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = preview == (index == 1),
                        onClick = { preview = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2)
                    ) { Text(label) }
                }
            }

            if (preview) {
                MarkdownRenderer(
                    markdownBody = editorValue.text,
                    resolvedAssets = resolvedAssets,
                    modifier = Modifier.weight(1f)
                )
            } else {
                OutlinedTextField(
                    value = editorValue,
                    onValueChange = { editorValue = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = { Text("Markdown") }
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = profile != null && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("插入图片")
                }
                Button(
                    onClick = {
                        busy = true
                        error = null
                        val onComplete: (Result<String>) -> Unit = { result ->
                            busy = false
                            result.onSuccess { navController.popBackStack() }
                                .onFailure { error = it.message }
                        }
                        if (noteId == null) {
                            viewModel.saveMarkdownNote(editorValue.text, onComplete)
                        } else {
                            viewModel.reviseMarkdownNote(noteId, editorValue.text, onComplete)
                        }
                    },
                    enabled = profile != null && !busy && editorValue.text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
            if (profile == null) {
                OutlinedButton(
                    onClick = { navController.navigate("repository_settings") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("配置仓库档案") }
            }
        }
    }
}
