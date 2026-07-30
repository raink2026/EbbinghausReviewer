package com.ebbinghaus.review.data.sync

import com.google.gson.JsonParser
import java.io.File

data class OutboxBatchPlan(
    val operations: List<SyncOutbox>,
    val estimatedPayloadBytes: Long
)

class OutboxPlanner(
    private val maxActions: Int = DEFAULT_MAX_ACTIONS,
    private val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES
) {
    fun nextBatch(pending: List<SyncOutbox>): OutboxBatchPlan? {
        if (pending.isEmpty()) return null
        require(pending.map { it.profileId }.distinct().size == 1)
        require(pending.map { it.repositoryId }.distinct().size == 1)
        val ordered = pending.sortedWith(
            compareBy<SyncOutbox>({ priority(it) }, { it.createdAt }, { it.operationId })
        )
        val pendingIds = pending.mapTo(mutableSetOf()) { it.operationId }
        val selected = mutableListOf<SyncOutbox>()
        val selectedIds = mutableSetOf<String>()
        val remaining = ordered.toMutableList()
        var bytes = 0L

        fun canAdd(operation: SyncOutbox): Boolean {
            val size = estimatedSize(operation)
            return selected.size < maxActions && bytes + size <= maxPayloadBytes
        }

        while (remaining.isNotEmpty()) {
            var progressed = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val operation = iterator.next()
                val unresolved = dependencyIds(operation).intersect(pendingIds)
                if (unresolved.all { it in selectedIds } && canAdd(operation)) {
                    selected += operation
                    selectedIds += operation.operationId
                    bytes += estimatedSize(operation)
                    iterator.remove()
                    progressed = true
                }
            }
            if (!progressed || selected.size == maxActions) break
        }
        if (selected.isEmpty()) {
            throw IllegalStateException("Outbox dependencies cannot be satisfied within transport limits")
        }
        return OutboxBatchPlan(selected, bytes)
    }

    private fun dependencyIds(operation: SyncOutbox): Set<String> =
        JsonParser.parseString(operation.dependencyIdsJson).asJsonArray.map { it.asString }.toSet()

    private fun estimatedSize(operation: SyncOutbox): Long {
        val raw = File(operation.payloadCachePath).length()
        val content = if (operation.operationType == "CREATE_ASSET") (raw * 4 + 2) / 3 else raw
        return content + operation.repositoryPath.length + ACTION_OVERHEAD_BYTES
    }

    private fun priority(operation: SyncOutbox): Int = when (operation.operationType) {
        "CREATE_ASSET" -> 0
        "CREATE_EVENT" -> 1
        "CREATE_NOTE" -> 2
        else -> 3
    }

    companion object {
        const val DEFAULT_MAX_ACTIONS = 50
        const val DEFAULT_MAX_PAYLOAD_BYTES = 4L * 1024L * 1024L
        private const val ACTION_OVERHEAD_BYTES = 512L
    }
}
