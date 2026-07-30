package com.ebbinghaus.review.data.sync

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_revisions",
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
        )
    ],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["noteId"]),
        Index(value = ["revisionId", "profileId"], unique = true),
        Index(value = ["noteId", "profileId"]),
        Index(value = ["profileId", "learningStartedAt"]),
        Index(value = ["contentSha256"])
    ]
)
data class NoteRevision(
    @PrimaryKey val revisionId: String,
    val profileId: String,
    val noteId: String,
    val parentRevisionIdsJson: String,
    val revisionKind: String,
    val authoredAt: Long,
    val learningStartedAt: Long,
    val sourceDeviceId: String,
    val contentSha256: String,
    val markdownCachePath: String,
    val repositoryPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
