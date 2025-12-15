package com.ebbinghaus.review.data.repository

import android.net.Uri
import com.ebbinghaus.review.data.ReviewDao
import com.ebbinghaus.review.data.ReviewItem
import com.ebbinghaus.review.data.ReviewLog
import com.ebbinghaus.review.data.ReviewItemMinimal
import com.ebbinghaus.review.utils.EbbinghausManager
import kotlinx.coroutines.flow.Flow

class ReviewRepository(
    private val reviewDao: ReviewDao,
    private val imageRepository: ImageRepository
) {

    val allActiveItems: Flow<List<ReviewItem>> = reviewDao.getAllActiveItems()

    fun getDueItems(currentTime: Long): Flow<List<ReviewItem>> = reviewDao.getDueItems(currentTime)

    fun getTodayReviewedItems(startTime: Long, endTime: Long, currentTime: Long): Flow<List<ReviewItem>> =
        reviewDao.getTodayReviewedItems(startTime, endTime, currentTime)

    val deletedItems: Flow<List<ReviewItem>> = reviewDao.getDeletedItems()

    suspend fun getItemsForHeatMap(): List<ReviewItemMinimal> = reviewDao.getItemsForHeatMap()

    suspend fun getAllItemsSync(): List<ReviewItem> = reviewDao.getAllItemsSync()

    suspend fun addItem(title: String, description: String, content: String, imageUris: List<String>) {
        // Save images first
        val savedImagePaths = mutableListOf<String>()
        try {
            imageUris.forEach { uriString ->
                val savedPath = imageRepository.saveImage(Uri.parse(uriString))
                savedImagePaths.add(savedPath)
            }
        } catch (e: Exception) {
            // If any image fails, we might want to clean up partially saved ones or just continue?
            // For now, re-throw so ViewModel knows operation failed.
            // Or better: proceed with what we have?
            // The prompt says "If internal copy fails... code fallback... Recommendation: throw exception".
            // So we let the exception propagate.
            throw e
        }

        val imagesString = if (savedImagePaths.isNotEmpty()) savedImagePaths.joinToString("|") else null

        val newItem = ReviewItem(
            title = title,
            description = description,
            content = content,
            imagePaths = imagesString,
            nextReviewTime = System.currentTimeMillis(),
            stage = 0
        )
        reviewDao.insert(newItem)
    }

    suspend fun markAsReviewed(item: ReviewItem, remembered: Boolean) {
        val currentStage = item.stage
        var nextStage = currentStage
        var nextTime = item.nextReviewTime

        if (remembered) {
            nextStage = item.stage + 1
            val calculatedTime = EbbinghausManager.calculateNextReviewTime(item.stage)
            nextTime = if (calculatedTime == -1L) item.nextReviewTime else calculatedTime

            if (calculatedTime == -1L) {
                reviewDao.update(item.copy(stage = nextStage, isFinished = true))
            } else {
                reviewDao.update(item.copy(stage = nextStage, nextReviewTime = nextTime))
            }
        } else {
            // Forgot: reset
            nextStage = 0
            nextTime = EbbinghausManager.calculateNextReviewTime(0)
            reviewDao.update(item.copy(stage = 0, nextReviewTime = nextTime))
        }

        // Log
        val log = ReviewLog(
            itemId = item.id,
            reviewTime = System.currentTimeMillis(),
            stageBefore = currentStage,
            action = if (remembered) "REMEMBER" else "FORGET",
            stageAfter = if (remembered && EbbinghausManager.calculateNextReviewTime(item.stage) == -1L) 99 else nextStage
        )
        reviewDao.insertLog(log)
    }

    suspend fun moveToTrash(item: ReviewItem) {
        reviewDao.update(item.copy(isDeleted = true, deletedTime = System.currentTimeMillis()))
    }

    suspend fun restoreFromTrash(item: ReviewItem) {
        reviewDao.update(item.copy(isDeleted = false, deletedTime = null))
    }

    suspend fun deletePermanently(item: ReviewItem) {
        reviewDao.delete(item)
    }

    suspend fun deleteExpiredItems(threshold: Long) {
        reviewDao.deleteExpiredItems(threshold)
    }

    suspend fun getItemLogs(itemId: Long): List<ReviewLog> {
        return reviewDao.getLogsByItemId(itemId)
    }

    suspend fun updateItem(item: ReviewItem) {
        reviewDao.update(item)
    }

    suspend fun getById(id: Long): ReviewItem? {
        return reviewDao.getById(id)
    }
}
