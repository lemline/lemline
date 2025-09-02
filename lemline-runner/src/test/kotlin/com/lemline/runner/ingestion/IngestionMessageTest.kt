// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.core.nodes.NodePosition
import com.lemline.core.nodes.NodeState
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.instances.InstanceMessage
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
        workflowState = WorkflowState(
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
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
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
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
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
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
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
            instance = null,
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
            outboxScheduledFor = null,
            message = "oops",
        )
        val json = original.toJsonString()
        val deserialized = IngestionMessage.fromJsonString(json)
        assertEquals(original, deserialized)
    }

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val instance = InstanceMessage.fromObjects(
            workflowId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            workflowName = "test-workflow",
            workflowVersion = "1.0.0",
            workflowPosition = NodePosition.root,
            workflowState = WorkflowState(mapOf(NodePosition.root to NodeState(rawInput = JsonPrimitive("")))),
            parentId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        )

        val msgParent: IngestionMessage = ParentIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
            outboxScheduledFor = null,
        )
        val msgWait: IngestionMessage = WaitIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
            outboxScheduledFor = null,
        )
        val msgRetry: IngestionMessage = RetryIngestionMessage(
            id = id,
            instance = null,
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
            outboxScheduledFor = null,
            message = "m",
        )
        val msgSchedule: IngestionMessage = ScheduleIngestionMessage(
            id = id,
            instance = instance,
            outBoxStatus = com.lemline.runner.outbox.OutBoxStatus.PENDING,
            outboxScheduledFor = null,
            scheduleAfter = "PT1S",
            scheduleEvery = null,
            scheduleCron = null,
            scheduleZone = null,
        )

        // Expect compact keys with class discriminator t
        // ParentIngestionMessage has serial name "p"
        assertEquals(
            "{\"t\":\"p\",\"i\":\"$id\",\"w\":${instance.payload},\"f\":null}",
            msgParent.toJsonString(),
        )
        // WaitIngestionMessage has serial name "w"
        assertEquals(
            "{\"t\":\"w\",\"i\":\"$id\",\"w\":${instance.payload},\"f\":null}",
            msgWait.toJsonString(),
        )
        // RetryIngestionMessage has serial name "r"
        assertEquals(
            "{\"t\":\"r\",\"i\":\"$id\",\"w\":null,\"f\":null,\"m\":\"m\"}",
            msgRetry.toJsonString(),
        )
        // ScheduleIngestionMessage has serial name "s"
        assertEquals(
            "{\"t\":\"s\",\"i\":\"$id\",\"w\":${instance.payload},\"f\":null,\"sa\":\"PT1S\",\"se\":null,\"sc\":null,\"sz\":null}",
            msgSchedule.toJsonString(),
        )
    }
}
