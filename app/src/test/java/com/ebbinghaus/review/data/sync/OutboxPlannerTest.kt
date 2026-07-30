package com.ebbinghaus.review.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OutboxPlannerTest {
    @Test
    fun dependencyOrderPreventsEventFromPassingItsNote() {
        val asset = operation("asset", "CREATE_ASSET")
        val note = operation("note", "CREATE_NOTE", listOf("asset"))
        val event = operation("event", "CREATE_EVENT", listOf("note"))

        val first = OutboxPlanner(maxActions = 2, maxPayloadBytes = 1_000_000)
            .nextBatch(listOf(event, note, asset))

        assertEquals(listOf("asset", "note"), first?.operations?.map { it.operationId })
        assertEquals(
            listOf("event"),
            OutboxPlanner(maxActions = 2, maxPayloadBytes = 1_000_000)
                .nextBatch(listOf(event))?.operations?.map { it.operationId }
        )
    }

    @Test
    fun oversizedDependencyBatchPublishesAssetBeforeDependentNote() {
        val asset = operation("asset", "CREATE_ASSET")
        val note = operation("note", "CREATE_NOTE", listOf("asset"))

        val first = OutboxPlanner(maxActions = 1, maxPayloadBytes = 1_000_000)
            .nextBatch(listOf(note, asset))

        assertEquals(listOf("asset"), first?.operations?.map { it.operationId })
    }

    @Test
    fun emptyOutboxDoesNotCreateCommitPlan() {
        assertNull(OutboxPlanner().nextBatch(emptyList()))
    }

    private fun operation(
        id: String,
        type: String,
        dependencies: List<String> = emptyList()
    ): SyncOutbox {
        val payload = Files.createTempFile("outbox-$id", ".bin").toFile().apply {
            writeText(id)
            deleteOnExit()
        }
        return SyncOutbox(
            operationId = id,
            profileId = "profile-a",
            repositoryId = "repository-a",
            operationType = type,
            repositoryPath = "2026-07-22/${id}.json",
            contentSha256 = id.padEnd(64, '0').take(64),
            payloadCachePath = payload.absolutePath,
            dependencyIdsJson = dependencies.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                .takeIf { dependencies.isNotEmpty() } ?: "[]",
            profileDate = "2026-07-22"
        )
    }
}
