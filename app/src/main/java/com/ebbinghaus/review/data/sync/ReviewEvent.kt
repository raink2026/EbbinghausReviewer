package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_review_events",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Note::class,
            parentColumns = ["noteId", "profileId"],
            childColumns = ["noteId", "profileId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NoteRevision::class,
            parentColumns = ["revisionId", "profileId"],
            childColumns = ["revisionId", "profileId"]
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["noteId"]),
        Index(value = ["revisionId"]),
        Index(value = ["noteId", "profileId"]),
        Index(value = ["revisionId", "profileId"]),
        Index(value = ["profileId", "streamId"]),
        Index(value = ["contentSha256"])
    ]
)
data class ReviewEvent(
    @PrimaryKey val eventId: String,
    val profileId: String,
    val noteId: String,
    val revisionId: String?,
    val streamId: String,
    val parentEventIdsJson: String,
    val eventType: String,
    val occurredAt: Long,
    val sourceDeviceId: String,
    val algorithmId: String,
    val algorithmVersion: Int,
    val payloadJson: String,
    val contentSha256: String,
    val repositoryPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
