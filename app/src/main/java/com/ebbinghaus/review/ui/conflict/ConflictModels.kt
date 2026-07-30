package com.ebbinghaus.review.ui.conflict

import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.protocol.ResolvedMarkdownAsset

data class ConflictRevision(
    val revision: NoteRevision,
    val markdownBody: String,
    val assets: List<ResolvedMarkdownAsset>
)

data class ReviewConflictOption(
    val eventId: String,
    val stageAfter: Int,
    val nextReviewAt: Long?
)

data class NoteConflictDetail(
    val note: Note,
    val state: String,
    val revisions: List<ConflictRevision>,
    val reviewOptions: List<ReviewConflictOption>
)
