package com.ebbinghaus.review.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "review_items")
data class ReviewItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val content: String,
    val imagePaths: String? = null,
    val createdTime: Long = System.currentTimeMillis(),
    
    // === 艾宾浩斯核心字段 ===
    val stage: Int = 0,
    val nextReviewTime: Long,
    val isFinished: Boolean = false,

    // === 【新增】软删除支持 ===
    val isDeleted: Boolean = false,
    val deletedTime: Long? = null
) {
    val isReviewable: Boolean
        get() = !isFinished && nextReviewTime <= System.currentTimeMillis()
}