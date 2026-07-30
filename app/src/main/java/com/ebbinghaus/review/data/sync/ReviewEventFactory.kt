package com.ebbinghaus.review.data.sync

import com.ebbinghaus.review.data.sync.projection.ReviewScheduleProjection
import com.ebbinghaus.review.data.sync.projection.ReviewStreamState
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_ID
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_VERSION
import com.ebbinghaus.review.data.sync.protocol.EVENT_SCHEMA
import com.ebbinghaus.review.data.sync.protocol.EventAlgorithm
import com.ebbinghaus.review.data.sync.protocol.EventDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.ParsedRepositoryEvent
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.OffsetDateTime
import java.util.UUID

data class ReviewEventDraft(
    val event: ReviewEvent,
    val documentBytes: ByteArray,
    val stageAfter: Int,
    val nextReviewAt: Long?,
    val completed: Boolean
)

class ReviewEventFactory(
    private val profile: Profile,
    private val sourceDeviceId: String,
    private val timeService: ProfileTimeService,
    private val uuidSource: UuidSource = UuidSource { UUID.randomUUID().toString() }
) {
    private val gson = Gson()
    private val intervals = JsonParser.parseString(profile.algorithmParametersJson)
        .asJsonObject.getAsJsonArray("interval_days").map { it.asInt }

    init {
        require(profile.algorithmId == ALGORITHM_ID && profile.algorithmVersion == ALGORITHM_VERSION)
        require(intervals.size == 8 && intervals.all { it > 0 })
    }

    fun createReview(
        note: Note,
        revision: NoteRevision,
        currentProjection: ReviewScheduleProjection,
        remembered: Boolean
    ): ReviewEventDraft {
        require(note.profileId == profile.profileId && revision.profileId == profile.profileId)
        require(note.activeRevisionId == revision.revisionId)
        require(currentProjection.state in setOf(ReviewStreamState.INITIAL, ReviewStreamState.SCHEDULED)) {
            "Review cannot advance from ${currentProjection.state}"
        }
        val stageBefore = currentProjection.stage
        val stageAfter = if (remembered) stageBefore + 1 else 0
        require(stageAfter in 0..intervals.size)
        val completed = stageAfter == intervals.size
        val occurredAt = timeService.now().toEpochMilli()
        val nextReviewAt = if (completed) null else timeService.reviewAtStartOfDay(
            occurredAt,
            intervals[stageAfter]
        )
        val eventId = uuidSource.next()
        val payload = JsonObject().apply {
            addProperty("note_id", note.noteId)
            addProperty("revision_id", revision.revisionId)
            addProperty("result", if (remembered) "remember" else "forget")
            addProperty("stage_before", stageBefore)
            addProperty("stage_after", stageAfter)
            if (nextReviewAt == null) {
                add("next_review_at", JsonNull.INSTANCE)
            } else {
                addProperty("next_review_at", timeService.formatOffsetDateTime(nextReviewAt))
            }
        }
        val parsedEvent = ParsedRepositoryEvent(
            schema = EVENT_SCHEMA,
            eventId = eventId,
            streamId = "review:${revision.revisionId}",
            parentEventIds = currentProjection.leafEventIds.sorted(),
            eventType = "review",
            occurredAt = timeService.formatOffsetDateTime(occurredAt),
            sourceDeviceId = sourceDeviceId,
            algorithm = EventAlgorithm(profile.algorithmId, profile.algorithmVersion),
            payload = payload,
            originalBytes = byteArrayOf()
        )
        val bytes = EventDocumentCodec.serialize(parsedEvent)
        val repositoryPath = timeService.repositoryPath(
            RepositoryDateCategory.EVENTS,
            "$eventId.json",
            occurredAt
        )
        check(EventDocumentCodec.parse(bytes, repositoryPath).isValid)
        return ReviewEventDraft(
            event = ReviewEvent(
                eventId = eventId,
                profileId = profile.profileId,
                noteId = note.noteId,
                revisionId = revision.revisionId,
                streamId = parsedEvent.streamId,
                parentEventIdsJson = gson.toJson(parsedEvent.parentEventIds),
                eventType = "review",
                occurredAt = occurredAt,
                sourceDeviceId = sourceDeviceId,
                algorithmId = profile.algorithmId,
                algorithmVersion = profile.algorithmVersion,
                payloadJson = gson.toJson(payload),
                contentSha256 = sha256(bytes),
                repositoryPath = repositoryPath,
                createdAt = occurredAt
            ),
            documentBytes = bytes,
            stageAfter = stageAfter,
            nextReviewAt = nextReviewAt,
            completed = completed
        )
    }

    fun createMerge(
        note: Note,
        revision: NoteRevision,
        conflict: ReviewScheduleProjection,
        selectedLeaf: ReviewEvent
    ): ReviewEventDraft {
        require(note.profileId == profile.profileId && revision.profileId == profile.profileId)
        require(revision.noteId == note.noteId)
        require(conflict.state == ReviewStreamState.REVIEW_CONFLICT)
        require(selectedLeaf.eventId in conflict.leafEventIds)
        require(selectedLeaf.streamId == "review:${revision.revisionId}")
        val selectedPayload = JsonParser.parseString(selectedLeaf.payloadJson).asJsonObject
        val stageAfter = selectedPayload.get("stage_after").asInt
        require(stageAfter in 0..intervals.size)
        val nextReviewAt = selectedPayload.get("next_review_at")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val completed = stageAfter == intervals.size
        require(completed == (nextReviewAt == null))
        val eventId = uuidSource.next()
        val occurredAt = timeService.now().toEpochMilli()
        val payload = JsonObject().apply {
            addProperty("note_id", note.noteId)
            addProperty("revision_id", revision.revisionId)
            addProperty("selected_event_id", selectedLeaf.eventId)
            addProperty("stage_after", stageAfter)
            if (nextReviewAt == null) {
                add("next_review_at", JsonNull.INSTANCE)
            } else {
                addProperty("next_review_at", timeService.formatOffsetDateTime(nextReviewAt))
            }
        }
        val parsedEvent = ParsedRepositoryEvent(
            schema = EVENT_SCHEMA,
            eventId = eventId,
            streamId = "review:${revision.revisionId}",
            parentEventIds = conflict.leafEventIds.sorted(),
            eventType = "review_merge",
            occurredAt = timeService.formatOffsetDateTime(occurredAt),
            sourceDeviceId = sourceDeviceId,
            algorithm = EventAlgorithm(profile.algorithmId, profile.algorithmVersion),
            payload = payload,
            originalBytes = byteArrayOf()
        )
        val bytes = EventDocumentCodec.serialize(parsedEvent)
        val repositoryPath = timeService.repositoryPath(
            RepositoryDateCategory.EVENTS,
            "$eventId.json",
            occurredAt
        )
        check(EventDocumentCodec.parse(bytes, repositoryPath).isValid)
        return ReviewEventDraft(
            event = ReviewEvent(
                eventId = eventId,
                profileId = profile.profileId,
                noteId = note.noteId,
                revisionId = revision.revisionId,
                streamId = parsedEvent.streamId,
                parentEventIdsJson = gson.toJson(parsedEvent.parentEventIds),
                eventType = "review_merge",
                occurredAt = occurredAt,
                sourceDeviceId = sourceDeviceId,
                algorithmId = profile.algorithmId,
                algorithmVersion = profile.algorithmVersion,
                payloadJson = gson.toJson(payload),
                contentSha256 = sha256(bytes),
                repositoryPath = repositoryPath,
                createdAt = occurredAt
            ),
            documentBytes = bytes,
            stageAfter = stageAfter,
            nextReviewAt = nextReviewAt,
            completed = completed
        )
    }
}
