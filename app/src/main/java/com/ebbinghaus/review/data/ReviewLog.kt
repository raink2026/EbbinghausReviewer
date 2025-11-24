package com.ebbinghaus.review.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "review_logs")
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,      // 关联 ReviewItem 的 ID
    val reviewTime: Long,  // 复习发生的时间
    val stageBefore: Int,  // 复习前的阶段
    val action: String,    // "REMEMBER" (记得) or "FORGET" (忘了)
    val stageAfter: Int    // 复习后的阶段
)