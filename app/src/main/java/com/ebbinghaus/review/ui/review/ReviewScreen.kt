package com.ebbinghaus.review.ui.review

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ebbinghaus.review.R
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.ui.MainViewModel
import com.ebbinghaus.review.ui.components.FullImageDialog
import com.ebbinghaus.review.utils.EbbinghausManager
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.reviewing), style = MaterialTheme.typography.titleMedium)
                        item?.let {
                            Text(
                                text = EbbinghausManager.getStageDescription(it.stage),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (item == null) {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.loading))
            }
        } else {
            val currentItem = item!!
            
            // Swipe Logic
            val offsetX = remember { Animatable(0f) }
            val configuration = LocalConfiguration.current
            val screenWidth = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
            val swipeThreshold = screenWidth * 0.3f

            val rotation = remember {
                derivedStateOf {
                    (offsetX.value / screenWidth) * 20f
                }
            }

            val alphaForget = remember {
                derivedStateOf {
                    if (offsetX.value < 0) (-offsetX.value / swipeThreshold).coerceIn(0f, 1f) else 0f
                }
            }
             val alphaRemember = remember {
                derivedStateOf {
                    if (offsetX.value > 0) (offsetX.value / swipeThreshold).coerceIn(0f, 1f) else 0f
                }
            }

            fun performAction(remembered: Boolean) {
                 scope.launch {
                     // Animate out
                     val targetX = if (remembered) screenWidth else -screenWidth
                     offsetX.animateTo(targetX, animationSpec = tween(300))

                     // Perform DB Action
                     viewModel.markAsReviewed(currentItem, remembered)

                     // Show Snackbar with Undo
                     val result = snackbarHostState.showSnackbar(
                         message = if (remembered) "Marked as Remembered" else "Marked as Forgotten",
                         actionLabel = "UNDO",
                         duration = androidx.compose.material3.SnackbarDuration.Short
                     )

                     if (result == SnackbarResult.ActionPerformed) {
                         viewModel.undoLastReview()
                         // Restore item view
                         offsetX.snapTo(0f)
                         // Since we are observing `item` which is local state, and ViewModel updates DB.
                         // But `item` variable here is local. We need to refresh it.
                         // Actually `ReviewScreen` is usually popped after review in standard flow,
                         // but here we might want to stay or pop.
                         // Current logic: Pop back stack.
                     }

                     // If we are popping back stack, we can't show snackbar easily here unless we pass it to previous screen.
                     // But user requested "Undo" capability.
                     // Option: Don't pop immediately? Or pop and show Snackbar on list screen?
                     // Usually Anki shows next card.
                     // Since this app reviews one by one from list, we should probably pop.
                     // BUT if we pop, this composable is destroyed.

                     // Let's modify behavior: Don't pop immediately?
                     // Or assume this screen handles ONE item and returns.
                     // If we pop, we can't Undo easily here.
                     // Refined Logic: Pop after small delay? Or pop and use MainScreen snackbar?
                     // Simpler for now: Pop immediately. Undo is available on MainScreen?
                     // No, MainScreen needs to handle it.

                     // Let's try to keep it simple:
                     // Perform action -> Pop.
                     // But where is Undo?
                     // If we pop, we go to `MainScreen`. `MainScreen` doesn't know about the action.
                     // Recommendation: Review Mode should ideally be a list of cards.
                     // But current architecture is `MainScreen` -> `ReviewScreen` (Single Item).
                     // If we want Undo, `MainScreen` needs to show the Snackbar.

                     // Workaround:
                     // We can delay pop slightly? No, that's bad UX.
                     // We can rely on `MainViewModel` to show a Toast (done currently).
                     // But Toast doesn't have buttons.
                     // Let's assume we pop back.

                     navController.popBackStack()
                 }
            }

            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                
                // Background indicators for Swipe
                 Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Icon (Forget)
                         Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Forget",
                            modifier = Modifier
                                .size(64.dp)
                                .alpha(alphaForget.value),
                            tint = MaterialTheme.colorScheme.error
                        )

                        // Right Icon (Remember)
                        Icon(
                            imageVector = Icons.Default.Refresh, // Using Refresh as "Cycle" / "Next" or Check
                            contentDescription = "Remember",
                            modifier = Modifier
                                .size(64.dp)
                                .alpha(alphaRemember.value),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Card Stack (The Item)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .rotate(rotation.value)
                        .padding(16.dp)
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (offsetX.value.absoluteValue > swipeThreshold) {
                                        val remembered = offsetX.value > 0
                                        performAction(remembered)
                                    } else {
                                        scope.launch { offsetX.animateTo(0f) }
                                    }
                                },
                                onDragCancel = {
                                     scope.launch { offsetX.animateTo(0f) }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                     ReviewCard(
                        item = currentItem,
                        isAnswerVisible = isAnswerVisible,
                        onToggleAnswer = { isAnswerVisible = !isAnswerVisible },
                        onImageClick = { fullScreenImageUrl = it }
                    )
                }

                // Bottom Control Buttons (Floating above)
                // Only show if Answer is Visible (Standard Anki style) OR always?
                // Anki: Show Answer button first. Then Option buttons.
                // Current UI: Click card to show answer.

                if (isAnswerVisible) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Forget Button
                            val forgetInterval = EbbinghausManager.getIntervalDescription(0)
                            Button(
                                onClick = { performAction(false) },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.forget_button), style = MaterialTheme.typography.labelLarge)
                                    Text(forgetInterval, style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            // Remember Button
                            val nextStage = currentItem.stage + 1
                            val rememberInterval = EbbinghausManager.getIntervalDescription(nextStage)
                            Button(
                                onClick = { performAction(true) },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.remember_button), style = MaterialTheme.typography.labelLarge)
                                    Text(rememberInterval, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                     // Hint for Swipe
                     Text(
                        text = stringResource(R.string.tap_to_show_answer),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .alpha(0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ReviewCard(
    item: ReviewItem,
    isAnswerVisible: Boolean,
    onToggleAnswer: () -> Unit,
    onImageClick: (String) -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp) // Leave space for buttons
            .clickable(onClick = onToggleAnswer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (item.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Divider / Question Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                 if (isAnswerVisible) {
                    LazyColumn(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            if (!item.imagePaths.isNullOrEmpty()) {
                                val paths = item.imagePaths.split("|")
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(paths) { path ->
                                        if (path.isNotBlank()) {
                                            AsyncImage(
                                                model = Uri.parse(path),
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .clickable { onImageClick(path) },
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                        item {
                            Text(
                                text = item.content,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                     // Placeholder when answer hidden
                     Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.3f)) {
                         Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(48.dp))
                         Spacer(modifier = Modifier.height(8.dp))
                         Text("Tap to reveal")
                     }
                }
            }
        }
    }
}
