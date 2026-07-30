package com.ebbinghaus.review.data.sync.projection

import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionReplayTest {
    private val gson = Gson()
    private val profile = Profile(
        profileId = "profile-a",
        displayName = "A",
        timezone = "Asia/Shanghai",
        algorithmId = "ebbinghaus-8-stage",
        algorithmVersion = 1,
        algorithmParametersJson = "{\"interval_days\":[1,2,4,7,15,30,60,120]}"
    )
    private val timeService = ProfileTimeService.forProfile(profile)

    @Test
    fun revisionProjectionIsStableAcrossOrderAndDuplicateIngestion() {
        val root = revision("root")
        val left = revision("left", listOf("root"), "restart")
        val right = revision("right", listOf("root"), "restart")

        val first = RevisionGraphProjector.project(listOf(root, left, right))
        val replayed = RevisionGraphProjector.project(listOf(right, root, left, root))

        assertEquals(RevisionGraphState.CONTENT_CONFLICT, first.state)
        assertEquals(setOf("left", "right"), first.leafRevisionIds)
        assertEquals(setOf("root"), first.archivedRevisionIds)
        assertEquals(first, replayed)
        assertNull(first.activeRevisionId)
    }

    @Test
    fun reviewConflictMergePreservesSelectedScheduleAfterShuffledReplay() {
        val revision = revision("revision")
        val firstTime = 1_784_731_200_000L
        val secondTime = firstTime + 60_000L
        val firstNext = timeService.reviewAtStartOfDay(firstTime, 2)
        val secondNext = timeService.reviewAtStartOfDay(secondTime, 1)
        val first = reviewEvent("event-a", revision, emptyList(), "remember", 0, 1, firstNext, firstTime)
        val second = reviewEvent("event-b", revision, emptyList(), "forget", 0, 0, secondNext, secondTime)
        val conflicted = ReviewEventProjector(profile, timeService)
            .project(revision, listOf(second, first))

        assertEquals(ReviewStreamState.REVIEW_CONFLICT, conflicted.state)
        assertEquals(setOf("event-a", "event-b"), conflicted.leafEventIds)

        val mergePayload = JsonObject().apply {
            addProperty("selected_event_id", "event-a")
            addProperty("stage_after", 1)
            addProperty("next_review_at", timeService.formatOffsetDateTime(firstNext))
        }
        val merge = event(
            id = "event-merge",
            revision = revision,
            parents = listOf("event-a", "event-b"),
            type = "review_merge",
            payload = mergePayload,
            occurredAt = secondTime + 60_000L
        )
        val resolved = ReviewEventProjector(profile, timeService)
            .project(revision, listOf(merge, first, second, first))

        assertEquals(ReviewStreamState.SCHEDULED, resolved.state)
        assertEquals(1, resolved.stage)
        assertEquals(firstNext, resolved.nextReviewAt)
        assertEquals(setOf("event-merge"), resolved.leafEventIds)
    }

    @Test
    fun singleLeafDeleteRevisionConflictCanBeExplicitlyResolved() {
        val delete = lifecycleEvent(
            id = "delete-a",
            parents = emptyList(),
            payload = JsonObject().apply {
                addProperty("note_id", "note-a")
                add("visible_revision_ids", gson.toJsonTree(listOf("old-revision")))
            },
            type = "delete"
        )
        val conflicted = LifecycleEventProjector.project(
            profileId = profile.profileId,
            noteId = "note-a",
            revisionLeafIds = setOf("new-revision"),
            allRevisionIds = setOf("old-revision", "new-revision"),
            events = listOf(delete)
        )

        assertEquals(LifecycleState.DELETION_CONFLICT, conflicted.state)
        assertTrue(conflicted.hidden)

        val resolution = lifecycleEvent(
            id = "resolve-a",
            parents = listOf("delete-a"),
            payload = JsonObject().apply {
                addProperty("note_id", "note-a")
                addProperty("resolution", "active")
                addProperty("target_revision_id", "new-revision")
            },
            type = "lifecycle_resolve"
        )
        val resolved = LifecycleEventProjector.project(
            profileId = profile.profileId,
            noteId = "note-a",
            revisionLeafIds = setOf("new-revision"),
            allRevisionIds = setOf("old-revision", "new-revision"),
            events = listOf(resolution, delete)
        )

        assertEquals(LifecycleState.ACTIVE, resolved.state)
        assertEquals("new-revision", resolved.visibleRevisionId)
        assertFalse(resolved.hidden)
    }

    @Test
    fun everyUnresolvedDomainConflictIsExcludedFromDueReview() {
        val root = revision("root")
        val left = revision("left", listOf("root"), "restart")
        val right = revision("right", listOf("root"), "restart")
        val projection = NoteProjectionEngine(profile, timeService)
            .project(listOf(root, left, right), emptyList())

        assertEquals(NoteDomainState.CONTENT_CONFLICT, projection.state)
        assertNull(projection.activeRevisionId)
        assertNull(projection.activeSchedule)
        assertFalse(projection.isDue(Long.MAX_VALUE))
    }

    @Test
    fun reviewConflictIsExcludedFromDueReviewAndPreservesBothEventBranches() {
        val revision = revision("revision")
        val occurredAt = 1_784_731_200_000L
        val left = reviewEvent(
            "event-left",
            revision,
            emptyList(),
            "remember",
            0,
            1,
            timeService.reviewAtStartOfDay(occurredAt, 2),
            occurredAt
        )
        val right = reviewEvent(
            "event-right",
            revision,
            emptyList(),
            "forget",
            0,
            0,
            timeService.reviewAtStartOfDay(occurredAt, 1),
            occurredAt + 1
        )

        val projection = NoteProjectionEngine(profile, timeService)
            .project(listOf(revision), listOf(right, left, left))

        assertEquals(NoteDomainState.REVIEW_CONFLICT, projection.state)
        assertNull(projection.activeRevisionId)
        assertNull(projection.activeSchedule)
        assertEquals(
            setOf("event-left", "event-right"),
            projection.reviewHistoryByRevision.getValue(revision.revisionId).leafEventIds
        )
        assertFalse(projection.isDue(Long.MAX_VALUE))
    }

    @Test
    fun deletionConflictIsExcludedFromDueReviewAndKeepsRevisionHistory() {
        val root = revision("root")
        val current = revision("current", listOf("root"), "restart")
        val delete = lifecycleEvent(
            id = "delete-root",
            parents = emptyList(),
            payload = JsonObject().apply {
                addProperty("note_id", "note-a")
                add("visible_revision_ids", gson.toJsonTree(listOf("root")))
            },
            type = "delete"
        )

        val projection = NoteProjectionEngine(profile, timeService)
            .project(listOf(current, root), listOf(delete))

        assertEquals(NoteDomainState.DELETION_CONFLICT, projection.state)
        assertEquals(setOf("root"), projection.archivedRevisionIds)
        assertEquals(setOf("delete-root"), projection.lifecycle.leafEventIds)
        assertNull(projection.activeSchedule)
        assertFalse(projection.isDue(Long.MAX_VALUE))
    }

    private fun revision(
        id: String,
        parents: List<String> = emptyList(),
        kind: String = "create"
    ) = NoteRevision(
        revisionId = id,
        profileId = profile.profileId,
        noteId = "note-a",
        parentRevisionIdsJson = gson.toJson(parents),
        revisionKind = kind,
        authoredAt = 1_784_731_200_000L,
        learningStartedAt = 1_784_731_200_000L,
        sourceDeviceId = "device-a",
        contentSha256 = id.padEnd(64, '0').take(64),
        markdownCachePath = "$id.md"
    )

    private fun reviewEvent(
        id: String,
        revision: NoteRevision,
        parents: List<String>,
        result: String,
        before: Int,
        after: Int,
        next: Long?,
        occurredAt: Long
    ): ReviewEvent = event(
        id,
        revision,
        parents,
        "review",
        JsonObject().apply {
            addProperty("note_id", revision.noteId)
            addProperty("revision_id", revision.revisionId)
            addProperty("result", result)
            addProperty("stage_before", before)
            addProperty("stage_after", after)
            if (next == null) add("next_review_at", JsonNull.INSTANCE)
            else addProperty("next_review_at", timeService.formatOffsetDateTime(next))
        },
        occurredAt
    )

    private fun event(
        id: String,
        revision: NoteRevision,
        parents: List<String>,
        type: String,
        payload: JsonObject,
        occurredAt: Long
    ) = ReviewEvent(
        eventId = id,
        profileId = profile.profileId,
        noteId = revision.noteId,
        revisionId = revision.revisionId,
        streamId = "review:${revision.revisionId}",
        parentEventIdsJson = gson.toJson(parents),
        eventType = type,
        occurredAt = occurredAt,
        sourceDeviceId = "device-a",
        algorithmId = profile.algorithmId,
        algorithmVersion = profile.algorithmVersion,
        payloadJson = gson.toJson(payload),
        contentSha256 = id.padEnd(64, 'f').take(64)
    )

    private fun lifecycleEvent(
        id: String,
        parents: List<String>,
        payload: JsonObject,
        type: String
    ) = ReviewEvent(
        eventId = id,
        profileId = profile.profileId,
        noteId = "note-a",
        revisionId = null,
        streamId = "lifecycle:note-a",
        parentEventIdsJson = gson.toJson(parents),
        eventType = type,
        occurredAt = 1_784_731_200_000L,
        sourceDeviceId = "device-a",
        algorithmId = profile.algorithmId,
        algorithmVersion = profile.algorithmVersion,
        payloadJson = gson.toJson(payload),
        contentSha256 = id.padEnd(64, 'e').take(64)
    )
}
