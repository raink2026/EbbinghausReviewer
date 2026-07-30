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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.AppTopBar
import com.ebbinghaus.review.ui.components.FullImageDialog
import com.ebbinghaus.review.ui.components.PhotoGrid
import com.ebbinghaus.review.ui.components.ReviewStageRail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(navController: NavController, viewModel: MainViewModel, itemId: Long) {
    var item by remember { mutableStateOf<ReviewItem?>(null) }
    var isAnswerVisible by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) { item = viewModel.getItemById(itemId) }
    fullScreenImageUrl?.let { url ->
        FullImageDialog(imageUrl = url, onDismiss = { fullScreenImageUrl = null })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.reviewing),
                onBack = { navController.popBackStack() }
            )
        }
    ) { innerPadding ->
        val currentItem = item
        if (currentItem == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(currentItem.title, style = MaterialTheme.typography.titleLarge)
                    if (currentItem.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentItem.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ReviewStageRail(stage = currentItem.stage, completed = currentItem.isFinished)
                }
            }

            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        stateDescription = if (isAnswerVisible) "答案已显示" else "答案未显示"
                    }
                    .clickable(
                        enabled = !isAnswerVisible,
                        role = Role.Button,
                        onClickLabel = "显示答案"
                    ) { isAnswerVisible = true },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            ) {
                if (isAnswerVisible) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            val paths = currentItem.imagePaths.orEmpty().split("|").filter(String::isNotBlank)
                            if (paths.isNotEmpty()) {
                                PhotoGrid(paths, onImageClick = { fullScreenImageUrl = it })
                                Spacer(Modifier.height(16.dp))
                            }
                            Text(currentItem.content, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("答案已隐藏", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.tap_to_show_answer),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isAnswerVisible) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text(stringResource(R.string.skip)) }
                    Button(
                        onClick = {
                            if (currentItem.isReviewable) viewModel.markAsReviewed(currentItem, remembered = true)
                            navController.popBackStack()
                        },
                        enabled = currentItem.isReviewable,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(
                            if (currentItem.isReviewable) stringResource(R.string.done)
                            else stringResource(R.string.finished_today)
                        )
                    }
                }
            }
        }
    }
}
