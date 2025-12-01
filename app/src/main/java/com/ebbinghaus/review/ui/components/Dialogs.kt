package com.ebbinghaus.review.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryLogsDialog(item: ReviewItem, logs: List<ReviewLog>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.review_history_prefix)}${item.title}") },
        text = {
            if (logs.isEmpty()) {
                Text(stringResource(R.string.no_history_logs))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(logs) { log ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 阶段圆圈
                            val color = if (log.action == "REMEMBER") Color(0xFF4CAF50) else Color.Gray
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(color, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(log.stageBefore.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Column {
                                val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                Text(formatter.format(Date(log.reviewTime)), style = MaterialTheme.typography.bodyMedium)
                                val status = if (log.action == "REMEMBER") 
                                    "${stringResource(R.string.remembered_stage)} ${log.stageAfter}" 
                                else 
                                    stringResource(R.string.forgot_reset)
                                Text(status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
fun FullImageDialog(imageUrl: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        if (scale > 1f) {
                           offset += pan
                        } else {
                           offset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                }
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = android.net.Uri.parse(imageUrl),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}
