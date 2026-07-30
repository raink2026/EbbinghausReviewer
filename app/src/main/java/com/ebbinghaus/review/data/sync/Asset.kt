package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_assets",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["profileId", "sha256", "extension"], unique = true),
        Index(value = ["cachePath"], unique = true)
    ]
)
data class Asset(
    @PrimaryKey val assetId: String,
    val profileId: String,
    val sha256: String,
    val extension: String,
    val byteSize: Long,
    val cachePath: String,
    val referenceCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
