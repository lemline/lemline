// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.ingestion.RetryIngestionMessage
import com.lemline.runner.instances.InstanceMessage
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.random.random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
class RetryModelTest {

    @Test
    fun `toModel should not throw and propagate fields correctly`() {
        val instance = InstanceMessage.random()
        val id = IDV7.random()
        val scheduledFor = Instant.random()
        val ingestion = RetryIngestionMessage(
            id = id,
            instanceMessage = instance,
            outBoxStatus = OutBoxStatus.PENDING,
            outboxScheduledFor = scheduledFor,
            errorReason = "reason",
            errorClass = "clazz",
            errorMessage = "msg",
            errorStackTrace = "stack"
        )

        val model = ingestion.toModel()

        // basic propagation
        assertEquals(id, model.id)
        assertEquals(instance, model.instanceMessage)
        assertEquals(OutBoxStatus.PENDING, model.outBoxStatus)
        assertEquals(scheduledFor, model.outboxScheduledFor)
        assertEquals("reason", model.errorReason)
        assertEquals("clazz", model.errorClass)
        assertEquals("msg", model.errorMessage)
        assertEquals("stack", model.errorStackTrace)

        // ensure workflowState and parentId are accessible through base OutboxModel
        val ws: WorkflowState = model.workflowState
        assertNotNull(ws)
        assertEquals(instance.workflowState, ws)
        assertEquals(instance.parentId, model.parentId)
    }

    @Test
    fun `serialization round-trip for RetryIngestionMessage keeps instance and toModel works`() {
        val original = RetryIngestionMessage(
            id = IDV7.random(),
            instanceMessage = InstanceMessage.random(),
            outBoxStatus = OutBoxStatus.FAILED,
            outboxScheduledFor = Instant.random(),
            errorReason = String.random(),
            errorClass = String.random(),
            errorMessage = String.random(),
            errorStackTrace = String.random(),
        )

        val serialized = original.toJsonString()
        val deserialized = com.lemline.runner.ingestion.IngestionMessage.fromJsonString(serialized) as RetryIngestionMessage
        assertEquals(original, deserialized)

        // and toModel should still be safe
        val model = deserialized.toModel()
        assertEquals(deserialized.instanceMessage, model.instanceMessage)
        assertEquals(deserialized.errorReason, model.errorReason)
    }
}
