package com.ebbinghaus.review.ui.markdown

import com.ebbinghaus.review.data.sync.Note
import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.protocol.ResolvedMarkdownAsset

data class MarkdownNoteDetail(
    val note: Note,
    val revision: NoteRevision,
    val markdownBody: String,
    val assets: List<ResolvedMarkdownAsset>,
    val archivedRevisions: List<NoteRevision>
)
