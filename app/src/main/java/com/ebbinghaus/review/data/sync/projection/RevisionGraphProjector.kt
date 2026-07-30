package com.ebbinghaus.review.data.sync.projection

import com.ebbinghaus.review.data.sync.NoteRevision
import com.google.gson.JsonParser

enum class RevisionGraphState {
    EMPTY,
    ACTIVE,
    CONTENT_CONFLICT,
    MISSING_PARENT,
    MALFORMED_CYCLE,
    INVALID
}

data class RevisionGraphProjection(
    val state: RevisionGraphState,
    val activeRevisionId: String?,
    val leafRevisionIds: Set<String>,
    val archivedRevisionIds: Set<String>,
    val issues: List<String>
) {
    val reviewSchedulingAllowed: Boolean
        get() = state == RevisionGraphState.ACTIVE && activeRevisionId != null
}

object RevisionGraphProjector {
    fun project(revisions: Collection<NoteRevision>): RevisionGraphProjection {
        if (revisions.isEmpty()) {
            return RevisionGraphProjection(
                RevisionGraphState.EMPTY,
                null,
                emptySet(),
                emptySet(),
                emptyList()
            )
        }
        val issues = mutableListOf<String>()
        val grouped = revisions.groupBy { it.revisionId }
        grouped.filterValues { it.map(NoteRevision::contentSha256).distinct().size > 1 }
            .keys.sorted()
            .forEach { issues += "Revision ID $it is reused with different content" }
        val nodes = grouped.mapValues { it.value.first() }
        val profileIds = nodes.values.map { it.profileId }.distinct()
        val noteIds = nodes.values.map { it.noteId }.distinct()
        if (profileIds.size != 1 || noteIds.size != 1) {
            issues += "A revision graph must contain exactly one profile and note identity"
        }

        val parents = mutableMapOf<String, Set<String>>()
        nodes.toSortedMap().forEach { (revisionId, revision) ->
            val parsedList = runCatching { parseIdsList(revision.parentRevisionIdsJson) }.getOrElse {
                issues += "Revision $revisionId has invalid parent_revision_ids JSON"
                emptyList()
            }
            val parsedParents = parsedList.toSet()
            if (parsedParents.size != parsedList.size) {
                issues += "Revision $revisionId repeats a parent revision ID"
            }
            if (revisionId in parsedParents) issues += "Revision $revisionId cannot parent itself"
            when (revision.revisionKind) {
                "create" -> if (parsedParents.isNotEmpty()) issues +=
                    "Create revision $revisionId cannot have parents"
                "restart" -> if (parsedParents.size != 1) issues +=
                    "Restart revision $revisionId must have one parent"
                "merge" -> if (parsedParents.size < 2) issues +=
                    "Merge revision $revisionId must have at least two parents"
                else -> issues += "Revision $revisionId has unsupported kind ${revision.revisionKind}"
            }
            parents[revisionId] = parsedParents
        }

        val missingParents = parents.values.flatten().toSortedSet() - nodes.keys
        if (missingParents.isNotEmpty()) {
            issues += "Missing parent revisions: ${missingParents.joinToString()}"
        }
        val cycleNodes = findCycleNodes(nodes.keys, parents)
        if (cycleNodes.isNotEmpty()) {
            issues += "Revision graph contains a cycle: ${cycleNodes.sorted().joinToString()}"
        }
        val referenced = parents.values.flatten().toSet()
        val leaves = (nodes.keys - referenced).toSortedSet()
        val archived = (nodes.keys - leaves).toSortedSet()
        val state = when {
            cycleNodes.isNotEmpty() || leaves.isEmpty() -> RevisionGraphState.MALFORMED_CYCLE
            missingParents.isNotEmpty() -> RevisionGraphState.MISSING_PARENT
            issues.isNotEmpty() -> RevisionGraphState.INVALID
            leaves.size == 1 -> RevisionGraphState.ACTIVE
            else -> RevisionGraphState.CONTENT_CONFLICT
        }
        return RevisionGraphProjection(
            state = state,
            activeRevisionId = leaves.singleOrNull().takeIf { state == RevisionGraphState.ACTIVE },
            leafRevisionIds = leaves,
            archivedRevisionIds = archived,
            issues = issues
        )
    }

    private fun parseIdsList(json: String): List<String> =
        JsonParser.parseString(json).asJsonArray.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
            element.asString
        }

    private fun findCycleNodes(
        nodeIds: Set<String>,
        parents: Map<String, Set<String>>
    ): Set<String> {
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        val cycleNodes = mutableSetOf<String>()

        fun visit(nodeId: String) {
            when (state[nodeId]) {
                1 -> {
                    val start = stack.indexOf(nodeId).coerceAtLeast(0)
                    cycleNodes += stack.subList(start, stack.size)
                    return
                }
                2 -> return
            }
            state[nodeId] = 1
            stack += nodeId
            parents[nodeId].orEmpty().filter { it in nodeIds }.sorted().forEach(::visit)
            stack.removeAt(stack.lastIndex)
            state[nodeId] = 2
        }
        nodeIds.sorted().forEach(::visit)
        return cycleNodes
    }
}
