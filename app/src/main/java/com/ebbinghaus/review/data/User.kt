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
    val profileIcon: String = "Person",
    val themeColor: Long? = null, // Legacy background ARGB; retained for migration compatibility.
    val themePreset: String = "wechat",
    val customThemeDark: Boolean = false,
    val customThemeFont: String = "system",
    val customThemeBackground: Long? = null,
    val customThemeSurface: Long? = null,
    val customThemePrimary: Long? = null,
    val customThemeText: Long? = null,
    val fontScale: Float = 1.0f // 全局字体缩放比例
)
