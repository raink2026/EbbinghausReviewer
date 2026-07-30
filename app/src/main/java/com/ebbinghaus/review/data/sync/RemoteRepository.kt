package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "remote_repositories",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profileId"], unique = true),
        Index(value = ["repositoryId", "profileId"], unique = true),
        Index(value = ["owner", "name", "branch"])
    ]
)
data class RemoteRepository(
    @PrimaryKey val repositoryId: String,
    val profileId: String,
    val owner: String,
    val name: String,
    val branch: String,
    val credentialAlias: String,
    val isBound: Boolean = true,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val syncState: String = "IDLE",
    val lastSyncError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
