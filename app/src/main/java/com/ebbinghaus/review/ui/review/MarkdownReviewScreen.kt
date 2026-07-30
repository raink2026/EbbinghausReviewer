package com.ebbinghaus.review.ui.review

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.markdown.MarkdownNoteDetail
import com.ebbinghaus.review.ui.markdown.MarkdownRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownReviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    noteId: String
) {
    var detail by remember { mutableStateOf<MarkdownNoteDetail?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(noteId) {
        detail = viewModel.getMarkdownNote(noteId)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.note?.title ?: "Markdown 笔记") },
                actions = {
                    IconButton(onClick = { navController.navigate("edit_markdown/$noteId") }) {
                        Icon(Icons.Default.Edit, contentDescription = "修订")
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { Text("加载中") }
            detail == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { Text("活动修订不可用", color = MaterialTheme.colorScheme.error) }
            else -> {
                val current = checkNotNull(detail)
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .clickable { revealed = true },
                        contentAlignment = if (revealed) Alignment.TopStart else Alignment.Center
                    ) {
                        if (revealed) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    MarkdownRenderer(
                                        markdownBody = current.markdownBody,
                                        resolvedAssets = current.assets
                                    )
                                }
                                if (current.archivedRevisions.isNotEmpty()) {
                                    item {
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
                            Text("点击显示内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (revealed) {
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    viewModel.markMarkdownNoteReviewed(noteId, remembered = false) {
                                        navController.popBackStack()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("忘记") }
                            Spacer(Modifier.padding(4.dp))
                            Button(
                                onClick = {
                                    viewModel.markMarkdownNoteReviewed(noteId, remembered = true) {
                                        navController.popBackStack()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("记得") }
                        }
                    }
                }
            }
        }
    }
}
