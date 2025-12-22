package com.ebbinghaus.review.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PhotoGrid(
    imagePaths: List<String>,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (imagePaths.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (imagePaths.size) {
            1 -> {
                PhotoItem(
                    path = imagePaths[0],
                    onClick = { onImageClick(imagePaths[0]) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                )
            }
            2 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhotoItem(
                        path = imagePaths[0],
                        onClick = { onImageClick(imagePaths[0]) },
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                    )
                    PhotoItem(
                        path = imagePaths[1],
                        onClick = { onImageClick(imagePaths[1]) },
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                    )
                }
            }
            3 -> {
                PhotoItem(
                    path = imagePaths[0],
                    onClick = { onImageClick(imagePaths[0]) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhotoItem(
                        path = imagePaths[1],
                        onClick = { onImageClick(imagePaths[1]) },
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                    )
                    PhotoItem(
                        path = imagePaths[2],
                        onClick = { onImageClick(imagePaths[2]) },
                        modifier = Modifier.weight(1f).aspectRatio(1f)
                    )
                }
            }
            else -> {
                // 4 or more: Grid of 2 columns
                val rows = (imagePaths.size + 1) / 2
                for (i in 0 until rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val firstIndex = i * 2
                        val secondIndex = i * 2 + 1

                        if (firstIndex < imagePaths.size) {
                            PhotoItem(
                                path = imagePaths[firstIndex],
                                onClick = { onImageClick(imagePaths[firstIndex]) },
                                modifier = Modifier.weight(1f).aspectRatio(1f)
                            )
                        }

                        if (secondIndex < imagePaths.size) {
                            PhotoItem(
                                path = imagePaths[secondIndex],
                                onClick = { onImageClick(imagePaths[secondIndex]) },
                                modifier = Modifier.weight(1f).aspectRatio(1f)
                            )
                        } else {
                            // Spacer to fill the gap if odd number
                            Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoItem(
    path: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = Uri.parse(path),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    )
}
