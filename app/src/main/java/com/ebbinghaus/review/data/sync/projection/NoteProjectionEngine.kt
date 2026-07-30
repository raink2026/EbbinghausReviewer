package com.ebbinghaus.review.data.sync.projection

import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.ReviewEvent

enum class NoteDomainState {
    ACTIVE,
    CONTENT_CONFLICT,
    REVIEW_CONFLICT,
    DELETED,
    DELETION_CONFLICT,
    QUARANTINED
}

data class NoteDomainProjection(
    val state: NoteDomainState,
    val activeRevisionId: String?,
    val archivedRevisionIds: Set<String>,
    val activeSchedule: ReviewScheduleProjection?,
    val reviewHistoryByRevision: Map<String, ReviewScheduleProjection>,
    val lifecycle: LifecycleProjection,
    val issues: List<String>
) {
    fun isDue(nowMillis: Long): Boolean =
        state == NoteDomainState.ACTIVE &&
            activeSchedule?.state in setOf(ReviewStreamState.INITIAL, ReviewStreamState.SCHEDULED) &&
            activeSchedule?.nextReviewAt?.let { it <= nowMillis } == true
}

class NoteProjectionEngine(
    private val profile: Profile,
    private val timeService: ProfileTimeService
) {
    private val reviewProjector = ReviewEventProjector(profile, timeService)

    fun project(
        revisions: Collection<NoteRevision>,
        events: Collection<ReviewEvent>
    ): NoteDomainProjection {
        val revisionProjection = RevisionGraphProjector.project(revisions)
        val revisionsById = revisions.associateBy { it.revisionId }
        val reviewEvents = events.filter { it.streamId.startsWith("review:") }
            .groupBy { it.revisionId }
        val reviewHistory = revisionsById.toSortedMap().mapValues { (revisionId, revision) ->
            reviewProjector.project(revision, reviewEvents[revisionId].orEmpty())
        }
        val lifecycle = LifecycleEventProjector.project(
            profileId = profile.profileId,
            noteId = revisions.firstOrNull()?.noteId.orEmpty(),
            revisionLeafIds = revisionProjection.leafRevisionIds,
            allRevisionIds = revisionsById.keys,
            events = events.filter { it.streamId.startsWith("lifecycle:") }
        )
        val activeRevisionId = when {
            lifecycle.state == LifecycleState.ACTIVE && lifecycle.visibleRevisionId != null ->
                lifecycle.visibleRevisionId
            revisionProjection.state == RevisionGraphState.ACTIVE -> revisionProjection.activeRevisionId
            else -> null
        }
        val activeSchedule = activeRevisionId?.let(reviewHistory::get)
        val issues = revisionProjection.issues + lifecycle.issues +
            reviewHistory.values.flatMap { it.issues }
        val state = when {
            revisionProjection.state in setOf(
                RevisionGraphState.MISSING_PARENT,
                RevisionGraphState.MALFORMED_CYCLE,
                RevisionGraphState.INVALID,
                RevisionGraphState.EMPTY
            ) -> NoteDomainState.QUARANTINED
            revisionProjection.state == RevisionGraphState.CONTENT_CONFLICT ->
                NoteDomainState.CONTENT_CONFLICT
            lifecycle.state == LifecycleState.DELETION_CONFLICT -> NoteDomainState.DELETION_CONFLICT
            lifecycle.state == LifecycleState.DELETED -> NoteDomainState.DELETED
            lifecycle.state == LifecycleState.QUARANTINED -> NoteDomainState.QUARANTINED
            activeSchedule?.state == ReviewStreamState.REVIEW_CONFLICT -> NoteDomainState.REVIEW_CONFLICT
            activeSchedule?.state == ReviewStreamState.QUARANTINED -> NoteDomainState.QUARANTINED
            else -> NoteDomainState.ACTIVE
        }
        return NoteDomainProjection(
            state = state,
            activeRevisionId = activeRevisionId.takeIf { state == NoteDomainState.ACTIVE },
            archivedRevisionIds = revisionProjection.archivedRevisionIds,
            activeSchedule = activeSchedule.takeIf { state == NoteDomainState.ACTIVE },
            reviewHistoryByRevision = reviewHistory,
            lifecycle = lifecycle,
            issues = issues
        )
    }
}
