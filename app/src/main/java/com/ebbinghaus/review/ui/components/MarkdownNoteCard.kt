package com.ebbinghaus.review.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebbinghaus.review.data.sync.Note

@Composable
fun MarkdownNoteCard(note: Note, onClick: () -> Unit) {
    val active = note.projectionState == "ACTIVE"
    val status = when (note.projectionState) {
        "ACTIVE" -> if (note.isReviewComplete) "已完成" else "同步笔记 · 阶段 ${note.reviewStage}"
        "CONTENT_CONFLICT" -> "内容冲突"
        "REVIEW_CONFLICT" -> "复习冲突"
        "DELETION_CONFLICT" -> "删除冲突"
        "DELETED" -> "已删除"
        else -> "需要修复"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                if (active) {
                    Spacer(Modifier.height(8.dp))
                    ReviewStageRail(stage = note.reviewStage, completed = note.isReviewComplete)
                }
            }
            Spacer(Modifier.padding(5.dp))
            Icon(
                imageVector = if (active) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Warning,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            )
        }
    }
}
