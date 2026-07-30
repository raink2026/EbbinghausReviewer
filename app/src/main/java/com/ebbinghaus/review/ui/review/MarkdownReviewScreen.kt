package com.ebbinghaus.review.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.ReviewStageRail
import com.ebbinghaus.review.ui.markdown.MarkdownNoteDetail
import com.ebbinghaus.review.ui.markdown.MarkdownRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownReviewScreen(navController: NavController, viewModel: MainViewModel, noteId: String) {
    var detail by remember { mutableStateOf<MarkdownNoteDetail?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(noteId) {
        detail = viewModel.getMarkdownNote(noteId)
        loading = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = detail?.note?.title ?: "Markdown 笔记",
                onBack = { navController.popBackStack() }
            ) {
                IconButton(onClick = { navController.navigate("edit_markdown/$noteId") }) {
                    Icon(Icons.Default.Edit, contentDescription = "修订")
                }
            }
        }
    ) { innerPadding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("加载中", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            detail == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("活动修订不可用", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val current = checkNotNull(detail)
                Column(Modifier.fillMaxSize().padding(innerPadding).padding(12.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(current.note.title, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            ReviewStageRail(
                                stage = current.note.reviewStage,
                                completed = current.note.isReviewComplete
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .semantics {
                                stateDescription = if (revealed) "答案已显示" else "答案未显示"
                            }
                            .clickable(
                                enabled = !revealed,
                                role = Role.Button,
                                onClickLabel = "显示答案"
                            ) { revealed = true },
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        if (revealed) {
                            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                                item {
                                    MarkdownRenderer(current.markdownBody, current.assets)
                                    if (current.archivedRevisions.isNotEmpty()) {
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "已归档修订 ${current.archivedRevisions.size}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("内容已隐藏", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    Text("点击显示内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (revealed) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.markMarkdownNoteReviewed(noteId, remembered = false) {
                                        navController.popBackStack()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text("忘记", color = MaterialTheme.colorScheme.error) }
                            Button(
                                onClick = {
                                    viewModel.markMarkdownNoteReviewed(noteId, remembered = true) {
                                        navController.popBackStack()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(50.dp)
                            ) { Text("记得") }
                        }
                    }
                }
            }
        }
    }
}
