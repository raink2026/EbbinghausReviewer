package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RemoteRepository::class,
            parentColumns = ["repositoryId", "profileId"],
            childColumns = ["repositoryId", "profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["repositoryId"]),
        Index(value = ["repositoryId", "profileId"]),
        Index(value = ["profileId", "status"]),
        Index(value = ["batchId"])
    ]
)
data class SyncOutbox(
    @PrimaryKey val operationId: String,
    val profileId: String,
    val repositoryId: String,
    val operationType: String,
    val repositoryPath: String,
    val contentSha256: String,
    val payloadCachePath: String,
    val dependencyIdsJson: String,
    val profileDate: String,
    val batchId: String? = null,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
