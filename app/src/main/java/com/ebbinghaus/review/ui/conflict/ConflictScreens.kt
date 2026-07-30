package com.ebbinghaus.review.ui.conflict

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.markdown.MarkdownRenderer
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictListScreen(
    navController: NavController,
    notes: List<Note>
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步冲突") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (notes.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("没有待处理冲突") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(notes, key = Note::noteId) { note ->
                    ListItem(
                        headlineContent = { Text(note.title) },
                        supportingContent = { Text(conflictLabel(note.projectionState)) },
                        leadingContent = {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable {
                            navController.navigate("conflict/${note.noteId}")
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    navController: NavController,
    viewModel: MainViewModel,
    noteId: String
) {
    var detail by remember { mutableStateOf<NoteConflictDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var mergedBody by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        detail = viewModel.getConflictDetail(noteId)
        mergedBody = detail?.revisions?.firstOrNull()?.markdownBody.orEmpty()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.note?.title ?: "解决冲突") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("加载中") }
            detail == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("冲突已解决或数据不可用") }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
                when (checkNotNull(detail).state) {
                    "CONTENT_CONFLICT" -> {
                        items(checkNotNull(detail).revisions, key = { it.revision.revisionId }) { branch ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text(
                                    "分支 ${branch.revision.revisionId.take(8)}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                MarkdownRenderer(branch.markdownBody, branch.assets)
                            }
                            HorizontalDivider()
                        }
                        item {
                            OutlinedTextField(
                                value = mergedBody,
                                onValueChange = { mergedBody = it },
                                label = { Text("合并后的 Markdown") },
                                minLines = 8,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    viewModel.mergeMarkdownConflict(noteId, mergedBody) { result ->
                                        result.onSuccess { navController.popBackStack("conflicts", false) }
                                            .onFailure { error = it.message }
                                    }
                                },
                                enabled = mergedBody.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) { Text("创建合并修订") }
                        }
                    }
                    "REVIEW_CONFLICT" -> {
                        item {
                            Text(
                                "选择要保留的复习进度",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        items(checkNotNull(detail).reviewOptions, key = ReviewConflictOption::eventId) { option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("阶段 ${option.stageAfter}")
                                    Text(
                                        option.nextReviewAt?.let {
                                            "下次复习 ${DateFormat.getDateTimeInstance().format(Date(it))}"
                                        } ?: "已完成",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(onClick = {
                                    viewModel.resolveReviewConflict(noteId, option.eventId) { result ->
                                        result.onSuccess { navController.popBackStack("conflicts", false) }
                                            .onFailure { error = it.message }
                                    }
                                }) { Text("采用") }
                            }
                            HorizontalDivider()
                        }
                    }
                    "DELETION_CONFLICT" -> {
                        item {
                            Text(
                                "选择删除状态",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    viewModel.resolveLifecycleConflict(noteId, keepDeleted = true) { result ->
                                        result.onSuccess { navController.popBackStack("conflicts", false) }
                                            .onFailure { error = it.message }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) { Text("保持删除") }
                        }
                        items(checkNotNull(detail).revisions, key = { it.revision.revisionId }) { branch ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text("恢复分支 ${branch.revision.revisionId.take(8)}")
                                MarkdownRenderer(branch.markdownBody, branch.assets)
                                Button(
                                    onClick = {
                                        viewModel.resolveLifecycleConflict(
                                            noteId,
                                            keepDeleted = false,
                                            targetRevisionId = branch.revision.revisionId
                                        ) { result ->
                                            result.onSuccess { navController.popBackStack("conflicts", false) }
                                                .onFailure { error = it.message }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("恢复此版本") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun conflictLabel(state: String): String = when (state) {
    "CONTENT_CONFLICT" -> "内容分支冲突"
    "REVIEW_CONFLICT" -> "复习进度冲突"
    "DELETION_CONFLICT" -> "删除与恢复冲突"
    else -> state
}
