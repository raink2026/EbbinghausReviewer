package com.ebbinghaus.review.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarUrl: String? = null, // 可选：支持图片头像
    val isCurrent: Boolean = false, // 标记是否为当前登录用户
    val showMenuLabels: Boolean = true,
    val homeIcon: String = "Home",
    val planIcon: String = "DateRange",
    val profileIcon: String = "Person"
)
