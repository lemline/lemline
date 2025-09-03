// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.NodeStates
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
internal class IngestionMessageTest {

    private fun sampleInstance(): InstanceMessage = InstanceMessage.fromObjects(
        workflowId = UUID.randomUUID(),
        workflowName = "test-workflow",
        workflowVersion = "1.0.0",
        workflowPosition = NodePosition.root,
        nodeStates = NodeStates(
            mapOf(
                NodePosition.root to NodeState(
                    rawInput = JsonObject(mapOf("k" to JsonPrimitive("v"))),
                    startedAt = Clock.System.now(),
                ),
            ),
        ),
        parentId = UUID.randomUUID(),
    )

    @Test
    fun `should be JSON serializable and deserializable for Parent message`() {
        val original: IngestionMessage = ParentIngestionMessage(
            id = UUID.randomUUID(),
            instance = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Clock.System.now(),
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait message`() {
        val original: IngestionMessage = WaitIngestionMessage(
            id = UUID.randomUUID(),
            instance = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = Clock.System.now(),
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule message`() {
        val original: IngestionMessage = ScheduleIngestionMessage(
            id = UUID.randomUUID(),
            instance = sampleInstance(),
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = null,
            scheduleAfter = "PT10S",
            scheduleEvery = "a",
            scheduleCron = "b",
            scheduleZone = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry message`() {
        val original: IngestionMessage = RetryIngestionMessage(
            id = UUID.randomUUID(),
            instance = sampleInstance(),
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = null,
            errorClass = "a",
            errorMessage = "b",
            errorStackTrace = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure message`() {
        val original: IngestionMessage = FailureIngestionMessage(
            id = UUID.randomUUID(),
            instance = sampleInstance(),
            payload = "p",
            reason = "r",
            errorClass = "a",
            errorMessage = "b",
            errorStackTrace = "c",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        val id = UUID.randomUUID()

        val scheduledFor = Instant.parse("2023-01-01T00:00:00Z")

        val instance = InstanceMessage.fromObjects(
            workflowId = UUID.randomUUID(),
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            nodeStates = NodeStates(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            parentId = UUID.randomUUID(),
        )

        val msgParent = ParentIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
        )
        val msgWait = WaitIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
        )
        val msgRetry = RetryIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
            errorClass = "errorClass",
            errorMessage = "errorMessage",
            errorStackTrace = "errorStackTrace",
        )
        val msgSchedule = ScheduleIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = scheduledFor,
            scheduleAfter = "after",
            scheduleEvery = "every",
            scheduleCron = "cron",
            scheduleZone = "zone",
        )
        val msgFailure = FailureIngestionMessage(
            id = id,
            instance = instance,
            payload = "payload",
            reason = "reason",
            errorClass = "errorClass",
            errorMessage = "errorMessage",
            errorStackTrace = "errorStackTrace",
        )

        // Expect compact keys with class discriminator t
        // ParentIngestionMessage has a serial name "p"
        assertEquals(
            """{"t":"p","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z"}""",
            msgParent.toJsonString(),
        )
        // WaitIngestionMessage has a serial name "w"
        assertEquals(
            """{"t":"w","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z"}""",
            msgWait.toJsonString(),
        )
        // RetryIngestionMessage has a serial name "r"
        assertEquals(
            """{"t":"r","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgRetry.toJsonString(),
        )
        // ScheduleIngestionMessage has a serial name "s"
        assertEquals(
            """{"t":"s","id":"$id","i":${instance.toJsonString()},"s":"FAILED","f":"2023-01-01T00:00:00Z","sa":"after","se":"every","sc":"cron","sz":"zone"}""",
            msgSchedule.toJsonString(),
        )
        // FailureIngestionMessage has a serial name "f"
        assertEquals(
            """{"t":"f","id":"$id","i":${instance.toJsonString()},"p":"payload","r":"reason","ec":"errorClass","em":"errorMessage","es":"errorStackTrace"}""",
            msgFailure.toJsonString(),
        )
    }
}
