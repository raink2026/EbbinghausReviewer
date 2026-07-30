package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_checkpoints",
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
        Index(value = ["profileId"], unique = true),
        Index(value = ["repositoryId"], unique = true),
        Index(value = ["repositoryId", "profileId"])
    ]
)
data class SyncCheckpoint(
    @PrimaryKey val checkpointId: String,
    val profileId: String,
    val repositoryId: String,
    val remoteCommitSha: String? = null,
    val lastPullAt: Long? = null,
    val lastPushAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
