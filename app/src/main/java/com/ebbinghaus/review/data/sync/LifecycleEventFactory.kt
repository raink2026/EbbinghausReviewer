package com.ebbinghaus.review.data.sync

import com.ebbinghaus.review.data.sync.projection.LifecycleProjection
import com.ebbinghaus.review.data.sync.projection.LifecycleState
import com.ebbinghaus.review.data.sync.protocol.EVENT_SCHEMA
import com.ebbinghaus.review.data.sync.protocol.EventAlgorithm
import com.ebbinghaus.review.data.sync.protocol.EventDocumentCodec
import com.ebbinghaus.review.data.sync.protocol.ParsedRepositoryEvent
import com.ebbinghaus.review.data.sync.protocol.sha256
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID

data class LifecycleEventDraft(val event: ReviewEvent, val documentBytes: ByteArray)

class LifecycleEventFactory(
    private val profile: Profile,
    private val sourceDeviceId: String,
    private val timeService: ProfileTimeService,
    private val uuidSource: UuidSource = UuidSource { UUID.randomUUID().toString() }
) {
    private val gson = Gson()

    fun delete(
        note: Note,
        visibleRevisionIds: Set<String>,
        currentLifecycle: LifecycleProjection
    ): LifecycleEventDraft {
        require(currentLifecycle.state == LifecycleState.ACTIVE)
        return create(
            note = note,
            eventType = "delete",
            parentEventIds = currentLifecycle.leafEventIds,
            payload = JsonObject().apply {
                addProperty("note_id", note.noteId)
                add("visible_revision_ids", gson.toJsonTree(visibleRevisionIds.sorted()))
            }
        )
    }

    fun restore(
        note: Note,
        targetRevisionId: String,
        currentLifecycle: LifecycleProjection
    ): LifecycleEventDraft {
        require(currentLifecycle.state == LifecycleState.DELETED)
        val deleteEventId = currentLifecycle.leafEventIds.single()
        return create(
            note = note,
            eventType = "restore",
            parentEventIds = setOf(deleteEventId),
            payload = JsonObject().apply {
                addProperty("note_id", note.noteId)
                addProperty("resolved_delete_event_id", deleteEventId)
                addProperty("target_revision_id", targetRevisionId)
            }
        )
    }

    fun resolve(
        note: Note,
        currentLifecycle: LifecycleProjection,
        keepDeleted: Boolean,
        targetRevisionId: String? = null
    ): LifecycleEventDraft {
        require(currentLifecycle.state == LifecycleState.DELETION_CONFLICT)
        require(currentLifecycle.leafEventIds.isNotEmpty())
        require(keepDeleted || targetRevisionId != null)
        return create(
            note = note,
            eventType = "lifecycle_resolve",
            parentEventIds = currentLifecycle.leafEventIds,
            payload = JsonObject().apply {
                addProperty("note_id", note.noteId)
                addProperty("resolution", if (keepDeleted) "deleted" else "active")
                if (!keepDeleted) addProperty("target_revision_id", targetRevisionId)
            }
        )
    }

    private fun create(
        note: Note,
        eventType: String,
        parentEventIds: Set<String>,
        payload: JsonObject
    ): LifecycleEventDraft {
        require(note.profileId == profile.profileId)
        val eventId = uuidSource.next()
        val occurredAt = timeService.now().toEpochMilli()
        val parsed = ParsedRepositoryEvent(
            schema = EVENT_SCHEMA,
            eventId = eventId,
            streamId = "lifecycle:${note.noteId}",
            parentEventIds = parentEventIds.sorted(),
            eventType = eventType,
            occurredAt = timeService.formatOffsetDateTime(occurredAt),
            sourceDeviceId = sourceDeviceId,
            algorithm = EventAlgorithm(profile.algorithmId, profile.algorithmVersion),
            payload = payload,
            originalBytes = byteArrayOf()
        )
        val bytes = EventDocumentCodec.serialize(parsed)
        val repositoryPath = timeService.repositoryPath(
            RepositoryDateCategory.EVENTS,
            "$eventId.json",
            occurredAt
        )
        check(EventDocumentCodec.parse(bytes, repositoryPath).isValid)
        return LifecycleEventDraft(
            ReviewEvent(
                eventId = eventId,
                profileId = profile.profileId,
                noteId = note.noteId,
                revisionId = null,
                streamId = parsed.streamId,
                parentEventIdsJson = gson.toJson(parsed.parentEventIds),
                eventType = eventType,
                occurredAt = occurredAt,
                sourceDeviceId = sourceDeviceId,
                algorithmId = profile.algorithmId,
                algorithmVersion = profile.algorithmVersion,
                payloadJson = gson.toJson(payload),
                contentSha256 = sha256(bytes),
                repositoryPath = repositoryPath,
                createdAt = occurredAt
            ),
            bytes
        )
    }
}
