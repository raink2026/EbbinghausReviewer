package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_notes",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = NoteRevision::class,
            parentColumns = ["revisionId", "profileId"],
            childColumns = ["activeRevisionId", "profileId"],
            deferred = true
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["noteId", "profileId"], unique = true),
        Index(value = ["profileId", "projectionState"]),
        Index(value = ["activeRevisionId", "profileId"])
    ]
)
data class Note(
    @PrimaryKey val noteId: String,
    val profileId: String,
    val title: String,
    val activeRevisionId: String? = null,
    val projectionState: String = "ACTIVE",
    val reviewStage: Int = 0,
    val nextReviewAt: Long? = null,
    val isReviewComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
