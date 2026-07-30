package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_profiles",
    indices = [Index(value = ["isCurrent"])]
)
data class Profile(
    @PrimaryKey val profileId: String,
    val displayName: String,
    val timezone: String,
    val algorithmId: String,
    val algorithmVersion: Int,
    val algorithmParametersJson: String,
    val isCurrent: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
