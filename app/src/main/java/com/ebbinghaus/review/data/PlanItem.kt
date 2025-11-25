package com.ebbinghaus.review.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PlanStatus {
    TODO,       // 待办
    DONE,       // 完成
    GIVEN_UP    // 放弃 (主动选择不做，区别于单纯的没做)
}

@Entity(tableName = "plan_items")
data class PlanItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val date: String, // 格式 "yyyy-MM-dd"
    val status: PlanStatus = PlanStatus.TODO,
    val createdTime: Long = System.currentTimeMillis(),
    val isPriority: Boolean = false // 标记是否为“重要任务”
)
