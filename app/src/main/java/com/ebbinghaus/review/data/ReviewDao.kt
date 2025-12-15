package com.ebbinghaus.review.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {
    @Insert
    suspend fun insert(item: ReviewItem): Long

    @Update
    suspend fun update(item: ReviewItem)

    @Delete
    suspend fun delete(item: ReviewItem) // 物理删除

    // === 查询逻辑需过滤 isDeleted = 0 ===

    @Query("SELECT * FROM review_items WHERE isFinished = 0 AND isDeleted = 0 ORDER BY nextReviewTime ASC")
    fun getAllActiveItems(): Flow<List<ReviewItem>>

    @Query("SELECT * FROM review_items WHERE isFinished = 0 AND isDeleted = 0 AND nextReviewTime <= :currentTime")
    fun getDueItems(currentTime: Long): Flow<List<ReviewItem>>
    
    // 【新增】今日已完成 (过滤已删除)
    @Query("SELECT * FROM review_items WHERE createdTime BETWEEN :startTime AND :endTime AND (nextReviewTime > :currentTime OR isFinished = 1) AND isDeleted = 0")
    fun getTodayReviewedItems(startTime: Long, endTime: Long, currentTime: Long): Flow<List<ReviewItem>>

    @Query("SELECT COUNT(*) FROM review_items WHERE isFinished = 0 AND isDeleted = 0 AND nextReviewTime <= :currentTime")
    suspend fun getDueCountSync(currentTime: Long): Int

    // 日历查询 (过滤已删除)
    @Query("SELECT createdTime FROM review_items WHERE isDeleted = 0")
    suspend fun getAllCreatedTimes(): List<Long>

    // 导出 (过滤已删除)
    @Query("SELECT * FROM review_items WHERE isDeleted = 0")
    suspend fun getAllItemsSync(): List<ReviewItem>

    @Query("SELECT stage, nextReviewTime, isFinished FROM review_items WHERE isFinished = 0 AND isDeleted = 0")
    suspend fun getItemsForHeatMap(): List<ReviewItemMinimal>
    
    @Query("SELECT * FROM review_items WHERE id = :id")
    suspend fun getById(id: Long): ReviewItem?

    @Query("DELETE FROM review_items")
    suspend fun deleteAll()
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReviewItem>)

    // === 回收站 & 软删除 ===

    // 获取回收站内容
    @Query("SELECT * FROM review_items WHERE isDeleted = 1 ORDER BY deletedTime DESC")
    fun getDeletedItems(): Flow<List<ReviewItem>>

    // 清理过期数据 (物理删除)
    @Query("DELETE FROM review_items WHERE isDeleted = 1 AND deletedTime < :thresholdTime")
    suspend fun deleteExpiredItems(thresholdTime: Long)

    // === 历史记录 (Logs) ===

    @Insert
    suspend fun insertLog(log: ReviewLog)

    @Query("SELECT * FROM review_logs WHERE itemId = :itemId ORDER BY reviewTime ASC")
    suspend fun getLogsByItemId(itemId: Long): List<ReviewLog>
}