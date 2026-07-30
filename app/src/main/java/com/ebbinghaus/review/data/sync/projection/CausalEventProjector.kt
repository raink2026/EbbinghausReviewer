package com.ebbinghaus.review.data.sync.projection

import com.ebbinghaus.review.data.sync.NoteRevision
import com.ebbinghaus.review.data.sync.Profile
import com.ebbinghaus.review.data.sync.ProfileTimeService
import com.ebbinghaus.review.data.sync.ReviewEvent
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_ID
import com.ebbinghaus.review.data.sync.protocol.ALGORITHM_VERSION
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.OffsetDateTime

enum class ReviewStreamState {
    INITIAL,
    SCHEDULED,
    COMPLETE,
    REVIEW_CONFLICT,
    QUARANTINED
}

data class ReviewScheduleProjection(
    val state: ReviewStreamState,
    val stage: Int,
    val nextReviewAt: Long?,
    val leafEventIds: Set<String>,
    val history: List<ReviewEvent>,
    val issues: List<String>
)

class ReviewEventProjector(
    private val profile: Profile,
    private val timeService: ProfileTimeService
) {
    private val intervals = parseIntervals(profile.algorithmParametersJson)

    init {
        require(profile.timezone == timeService.timezoneId)
    }

    fun project(revision: NoteRevision, events: Collection<ReviewEvent>): ReviewScheduleProjection {
        require(revision.profileId == profile.profileId)
        if (events.isEmpty()) {
            return ReviewScheduleProjection(
                state = ReviewStreamState.INITIAL,
                stage = 0,
                nextReviewAt = revision.learningStartedAt,
                leafEventIds = emptySet(),
                history = emptyList(),
                issues = emptyList()
            )
        }
        val expectedStream = "review:${revision.revisionId}"
        val graph = buildEventGraph(events, expectedStream)
        val issues = graph.issues.toMutableList()
        val schedules = mutableMapOf<String, EventSchedule>()

        graph.topologicalOrder.forEach { eventId ->
            val event = graph.nodes.getValue(eventId)
            if (event.profileId != profile.profileId || event.revisionId != revision.revisionId ||
                event.noteId != revision.noteId
            ) {
                issues += "Event $eventId targets a different profile, note, or revision"
                return@forEach
            }
            if (event.algorithmId != profile.algorithmId ||
                event.algorithmVersion != profile.algorithmVersion ||
                event.algorithmId != ALGORITHM_ID || event.algorithmVersion != ALGORITHM_VERSION
            ) {
                issues += "Event $eventId uses unsupported algorithm ${event.algorithmId}/${event.algorithmVersion}"
                return@forEach
            }
            val payload = parsePayload(event, issues) ?: return@forEach
            val parentSchedules = graph.parents.getValue(eventId).mapNotNull(schedules::get)
            val schedule = when (event.eventType) {
                "review" -> validateReview(event, payload, parentSchedules, issues)
                "review_merge" -> validateReviewMerge(
                    event,
                    payload,
                    graph.parents.getValue(eventId),
                    schedules,
                    issues
                )
                else -> {
                    issues += "Review stream event $eventId has lifecycle type ${event.eventType}"
                    null
                }
            }
            if (schedule != null) schedules[eventId] = schedule
        }

        if (issues.isNotEmpty() || graph.leaves.any { it !in schedules }) {
            return ReviewScheduleProjection(
                state = ReviewStreamState.QUARANTINED,
                stage = 0,
                nextReviewAt = null,
                leafEventIds = graph.leaves,
                history = graph.nodes.values.sortedWith(EVENT_ORDER),
                issues = issues
            )
        }
        if (graph.leaves.size > 1) {
            return ReviewScheduleProjection(
                state = ReviewStreamState.REVIEW_CONFLICT,
                stage = 0,
                nextReviewAt = null,
                leafEventIds = graph.leaves,
                history = graph.nodes.values.sortedWith(EVENT_ORDER),
                issues = emptyList()
            )
        }
        val leafSchedule = schedules.getValue(graph.leaves.single())
        return ReviewScheduleProjection(
            state = if (leafSchedule.completed) ReviewStreamState.COMPLETE else ReviewStreamState.SCHEDULED,
            stage = leafSchedule.stage,
            nextReviewAt = leafSchedule.nextReviewAt,
            leafEventIds = graph.leaves,
            history = graph.nodes.values.sortedWith(EVENT_ORDER),
            issues = emptyList()
        )
    }

    private fun validateReview(
        event: ReviewEvent,
        payload: JsonObject,
        parentSchedules: List<EventSchedule>,
        issues: MutableList<String>
    ): EventSchedule? {
        val parentCount = parentSchedules.size
        if (parentCount > 1) {
            issues += "Review event ${event.eventId} cannot have multiple causal parents"
            return null
        }
        val expectedBefore = parentSchedules.singleOrNull()?.stage ?: 0
        val stageBefore = payload.int("stage_before", event, issues) ?: return null
        val stageAfter = payload.int("stage_after", event, issues) ?: return null
        val result = payload.string("result", event, issues) ?: return null
        if (stageBefore != expectedBefore) {
            issues += "Review event ${event.eventId} declares stage_before $stageBefore, expected $expectedBefore"
            return null
        }
        val expectedAfter = when (result) {
            "remember" -> stageBefore + 1
            "forget" -> 0
            else -> {
                issues += "Review event ${event.eventId} has unsupported result $result"
                return null
            }
        }
        if (stageAfter != expectedAfter || stageAfter !in 0..intervals.size) {
            issues += "Review event ${event.eventId} declares invalid stage_after $stageAfter"
            return null
        }
        return validateNextReview(event, payload, stageAfter, issues)
    }

    private fun validateReviewMerge(
        event: ReviewEvent,
        payload: JsonObject,
        parentIds: Set<String>,
        schedules: Map<String, EventSchedule>,
        issues: MutableList<String>
    ): EventSchedule? {
        val parentSchedules = parentIds.mapNotNull(schedules::get)
        if (parentSchedules.size < 2) {
            issues += "Review merge ${event.eventId} requires every conflicting leaf as a parent"
            return null
        }
        val selectedEventId = payload.optionalString("selected_event_id")
        val selected = if (selectedEventId != null) {
            if (selectedEventId !in parentIds) {
                issues += "Review merge ${event.eventId} selects a non-parent event"
                return null
            }
            schedules[selectedEventId]
        } else {
            val stageAfter = payload.int("stage_after", event, issues) ?: return null
            val next = payload.optionalOffsetMillis("next_review_at", event, issues)
            parentSchedules.singleOrNull { it.stage == stageAfter && it.nextReviewAt == next }
        }
        if (selected == null) {
            issues += "Review merge ${event.eventId} must select one valid parent schedule"
            return null
        }
        val declaredStage = payload.int("stage_after", event, issues) ?: return null
        val declaredNext = payload.optionalOffsetMillis("next_review_at", event, issues)
        if (declaredStage != selected.stage || declaredNext != selected.nextReviewAt) {
            issues += "Review merge ${event.eventId} does not preserve its selected schedule"
            return null
        }
        return selected
    }

    private fun validateNextReview(
        event: ReviewEvent,
        payload: JsonObject,
        stageAfter: Int,
        issues: MutableList<String>
    ): EventSchedule? {
        val completed = stageAfter == intervals.size
        val declaredNext = payload.optionalOffsetMillis("next_review_at", event, issues)
        val expectedNext = if (completed) null else timeService.reviewAtStartOfDay(
            event.occurredAt,
            intervals[stageAfter]
        )
        if (declaredNext != expectedNext) {
            issues += "Review event ${event.eventId} has invalid next_review_at"
            return null
        }
        return EventSchedule(stageAfter, expectedNext, completed)
    }

    private fun parseIntervals(json: String): List<Int> {
        val array = JsonParser.parseString(json).asJsonObject.getAsJsonArray("interval_days")
        val values = array.map { it.asInt }
        require(values.size == 8 && values.all { it > 0 }) {
            "Profile algorithm must define eight positive interval_days"
        }
        return values
    }
}

enum class LifecycleState {
    ACTIVE,
    DELETED,
    DELETION_CONFLICT,
    QUARANTINED
}

data class LifecycleProjection(
    val state: LifecycleState,
    val visibleRevisionId: String?,
    val leafEventIds: Set<String>,
    val history: List<ReviewEvent>,
    val issues: List<String>
) {
    val hidden: Boolean get() = state != LifecycleState.ACTIVE
}

object LifecycleEventProjector {
    fun project(
        profileId: String,
        noteId: String,
        revisionLeafIds: Set<String>,
        allRevisionIds: Set<String>,
        events: Collection<ReviewEvent>
    ): LifecycleProjection {
        if (events.isEmpty()) {
            return LifecycleProjection(
                LifecycleState.ACTIVE,
                revisionLeafIds.singleOrNull(),
                emptySet(),
                emptyList(),
                emptyList()
            )
        }
        val graph = buildEventGraph(events, "lifecycle:$noteId")
        val issues = graph.issues.toMutableList()
        val states = mutableMapOf<String, LifecycleNodeState>()
        val deletesMissingVisibleRevisions = mutableSetOf<String>()
        graph.topologicalOrder.forEach { eventId ->
            val event = graph.nodes.getValue(eventId)
            if (event.profileId != profileId || event.noteId != noteId || event.revisionId != null) {
                issues += "Lifecycle event $eventId targets another profile or note"
                return@forEach
            }
            val payload = parsePayload(event, issues) ?: return@forEach
            val parentIds = graph.parents.getValue(eventId)
            val parentStates = parentIds.mapNotNull(states::get)
            val state = when (event.eventType) {
                "delete" -> {
                    if (parentIds.size > 1) {
                        issues += "Delete event $eventId cannot have multiple parents"
                        null
                    } else {
                        val visible = payload.stringSet("visible_revision_ids", event, issues)
                            ?: return@forEach
                            LifecycleNodeState(deleted = true, visibleRevisionId = null, deleteEventId = eventId)
                            .also {
                                if (!visible.containsAll(revisionLeafIds)) {
                                    deletesMissingVisibleRevisions += eventId
                                }
                            }
                    }
                }
                "restore" -> {
                    if (parentIds.size != 1 || parentStates.size != 1 || !parentStates.single().deleted) {
                        issues += "Restore event $eventId must descend from the current delete leaf"
                        null
                    } else {
                        val resolvedDelete = payload.string("resolved_delete_event_id", event, issues)
                            ?: return@forEach
                        val targetRevision = payload.string("target_revision_id", event, issues)
                            ?: return@forEach
                        if (resolvedDelete !in parentIds || targetRevision !in allRevisionIds) {
                            issues += "Restore event $eventId references a stale delete or unknown revision"
                            null
                        } else {
                            LifecycleNodeState(false, targetRevision, resolvedDelete)
                        }
                    }
                }
                "lifecycle_resolve" -> {
                    if (parentIds.isEmpty()) {
                        issues += "Lifecycle resolution $eventId requires all conflicting leaves"
                        null
                    } else when (payload.string("resolution", event, issues)) {
                        "deleted" -> LifecycleNodeState(true, null, eventId)
                        "active" -> {
                            val target = payload.string("target_revision_id", event, issues)
                                ?: return@forEach
                            if (target !in allRevisionIds) {
                                issues += "Lifecycle resolution $eventId selects an unknown revision"
                                null
                            } else LifecycleNodeState(false, target, eventId)
                        }
                        else -> {
                            issues += "Lifecycle resolution $eventId has invalid resolution"
                            null
                        }
                    }
                }
                else -> {
                    issues += "Lifecycle stream event $eventId has review type ${event.eventType}"
                    null
                }
            }
            if (state != null) states[eventId] = state
        }

        val history = graph.nodes.values.sortedWith(EVENT_ORDER)
        if (graph.leaves.size > 1 || graph.leaves.any { it in deletesMissingVisibleRevisions }) {
            return LifecycleProjection(
                LifecycleState.DELETION_CONFLICT,
                null,
                graph.leaves,
                history,
                issues
            )
        }
        if (issues.isNotEmpty() || graph.leaves.any { it !in states }) {
            return LifecycleProjection(
                LifecycleState.QUARANTINED,
                null,
                graph.leaves,
                history,
                issues
            )
        }
        val leaf = states.getValue(graph.leaves.single())
        return LifecycleProjection(
            state = if (leaf.deleted) LifecycleState.DELETED else LifecycleState.ACTIVE,
            visibleRevisionId = leaf.visibleRevisionId,
            leafEventIds = graph.leaves,
            history = history,
            issues = emptyList()
        )
    }
}

private data class EventSchedule(val stage: Int, val nextReviewAt: Long?, val completed: Boolean)
private data class LifecycleNodeState(
    val deleted: Boolean,
    val visibleRevisionId: String?,
    val deleteEventId: String
)

private data class EventGraph(
    val nodes: Map<String, ReviewEvent>,
    val parents: Map<String, Set<String>>,
    val leaves: Set<String>,
    val topologicalOrder: List<String>,
    val issues: List<String>
)

private fun buildEventGraph(events: Collection<ReviewEvent>, expectedStream: String): EventGraph {
    val issues = mutableListOf<String>()
    val groups = events.groupBy { it.eventId }
    groups.filterValues { it.map(ReviewEvent::contentSha256).distinct().size > 1 }
        .keys.sorted().forEach { issues += "Event ID $it is reused with different content" }
    val nodes = groups.mapValues { it.value.first() }
    val parents = nodes.toSortedMap().mapValues { (eventId, event) ->
        if (event.streamId != expectedStream) {
            issues += "Event $eventId belongs to ${event.streamId}, expected $expectedStream"
        }
        val parsed = runCatching {
            JsonParser.parseString(event.parentEventIdsJson).asJsonArray.map { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
                element.asString
            }
        }.getOrElse {
            issues += "Event $eventId has invalid parent_event_ids JSON"
            emptyList()
        }
        if (parsed.distinct().size != parsed.size) {
            issues += "Event $eventId repeats a parent event ID"
        }
        parsed.toSet()
    }
    val missing = parents.values.flatten().toSet() - nodes.keys
    if (missing.isNotEmpty()) issues += "Missing parent events: ${missing.sorted().joinToString()}"
    val children = nodes.keys.associateWith { mutableSetOf<String>() }
    val indegree = nodes.keys.associateWith { 0 }.toMutableMap()
    parents.forEach { (child, parentIds) ->
        parentIds.filter { it in nodes }.forEach { parent ->
            children.getValue(parent) += child
            indegree[child] = indegree.getValue(child) + 1
        }
    }
    val ready = java.util.PriorityQueue(indegree.filterValues { it == 0 }.keys)
    val order = mutableListOf<String>()
    while (ready.isNotEmpty()) {
        val node = ready.remove()
        order += node
        children.getValue(node).sorted().forEach { child ->
            val remaining = indegree.getValue(child) - 1
            indegree[child] = remaining
            if (remaining == 0) ready += child
        }
    }
    if (order.size != nodes.size) issues += "Event graph contains a cycle"
    val referenced = parents.values.flatten().toSet()
    val leaves = (nodes.keys - referenced).toSortedSet()
    if (leaves.isEmpty()) issues += "Event graph has no leaf"
    return EventGraph(nodes, parents, leaves, order, issues)
}

private fun parsePayload(event: ReviewEvent, issues: MutableList<String>): JsonObject? = try {
    JsonParser.parseString(event.payloadJson).asJsonObject
} catch (error: Exception) {
    issues += "Event ${event.eventId} has invalid payload JSON"
    null
}

private fun JsonObject.string(
    key: String,
    event: ReviewEvent,
    issues: MutableList<String>
): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    ?: run {
        issues += "Event ${event.eventId} payload requires string $key"
        null
    }

private fun JsonObject.optionalString(key: String): String? = get(key)?.takeUnless { it.isJsonNull }
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(
    key: String,
    event: ReviewEvent,
    issues: MutableList<String>
): Int? = runCatching { get(key)?.asInt ?: error("missing") }.getOrElse {
    issues += "Event ${event.eventId} payload requires integer $key"
    null
}

private fun JsonObject.optionalOffsetMillis(
    key: String,
    event: ReviewEvent,
    issues: MutableList<String>
): Long? {
    val element = get(key) ?: return null
    if (element.isJsonNull) return null
    return runCatching { OffsetDateTime.parse(element.asString).toInstant().toEpochMilli() }.getOrElse {
        issues += "Event ${event.eventId} payload has invalid offset date-time $key"
        null
    }
}

private fun JsonObject.stringSet(
    key: String,
    event: ReviewEvent,
    issues: MutableList<String>
): Set<String>? = runCatching {
    getAsJsonArray(key).map { element ->
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
        element.asString
    }.toSet()
}.getOrElse {
    issues += "Event ${event.eventId} payload requires string array $key"
    null
}

private val EVENT_ORDER = compareBy<ReviewEvent>({ it.occurredAt }, { it.eventId })
