package com.ebbinghaus.review.ui.review

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.FullImageDialog
import com.ebbinghaus.review.ui.components.PhotoGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    itemId: Long
) {
    var item by remember { mutableStateOf<ReviewItem?>(null) }
    var isAnswerVisible by remember { mutableStateOf(false) }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(itemId) {
        item = viewModel.getItemById(itemId)
    }

    if (fullScreenImageUrl != null) {
        FullImageDialog(
            imageUrl = fullScreenImageUrl!!,
            onDismiss = { fullScreenImageUrl = null }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reviewing)) }) }
    ) { innerPadding ->
        if (item == null) {
            Box(modifier = Modifier.padding(innerPadding)) { Text(stringResource(R.string.loading)) }
        } else {
            val currentItem = item!!
            val isReviewable = currentItem.isReviewable
            
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = currentItem.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                
                if (currentItem.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = currentItem.description, style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                }
                
                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(if (isAnswerVisible) Color.Transparent else Color.LightGray)
                        .clickable { isAnswerVisible = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (isAnswerVisible) {
                        LazyColumn(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize().padding(16.dp)
                        ) {
                            item {
                                if (!currentItem.imagePaths.isNullOrEmpty()) {
                                    val paths = currentItem.imagePaths!!.split("|").filter { it.isNotBlank() }
                                    if (paths.isNotEmpty()) {
                                        PhotoGrid(
                                            imagePaths = paths,
                                            onImageClick = { fullScreenImageUrl = it }
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                            item {
                                Text(
                                    text = currentItem.content,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    } else {
                        Text(stringResource(R.string.tap_to_show_answer), color = Color.DarkGray)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isAnswerVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 放弃按钮
                        Button(
                            onClick = {
                                navController.popBackStack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.skip))
                        }
                        
                        if (isReviewable) {
                            // 完成复习按钮
                            Button(
                                onClick = {
                                    viewModel.markAsReviewed(currentItem, remembered = true)
                                    navController.popBackStack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(stringResource(R.string.done))
                            }
                        } else {
                            // 不可复习状态
                             Button(
                                onClick = {
                                    navController.popBackStack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text(stringResource(R.string.finished_today))
                            }
                        }
                    }
                }
            }
        }
    }
}
