// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GatewayCommandOutboxModelTest {

    @Test
    fun `GatewayCommandOutboxModel can be created and fields accessed`() {
        val model = randomGatewayCommandOutboxModel()

        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
    }

    @Test
    fun `copy preserves all fields`() {
        val model = randomGatewayCommandOutboxModel()
        val copy = model.copy()

        assertEquals(model.id, copy.id)
        assertEquals(model.instanceMessage, copy.instanceMessage)
        assertEquals(model.outboxScheduledFor, copy.outboxScheduledFor)
    }

    @Test
    fun `new model has pending outbox state`() {
        val model = GatewayCommandOutboxModel(
            id = com.lemline.common.values.IDV7.random(),
            instanceMessage = randomGatewayCommandOutboxModel().instanceMessage,
            outboxScheduledFor = kotlin.time.Clock.System.now(),
        )

        assertEquals(0, model.outboxAttemptCount)
        assertNull(model.outboxCompletedAt)
        assertNull(model.outboxFailedAt)
        assertNull(model.outboxErrorClass)
        assertNull(model.outboxErrorMessage)
        assertNull(model.outboxErrorStackTrace)
        assertNull(model.cleanupAfter)
        assertNotNull(model.outboxDelayedUntil)
        assertEquals(model.outboxScheduledFor, model.outboxDelayedUntil)
    }

    @Test
    fun `workflowId is derived from instance message`() {
        val model = randomGatewayCommandOutboxModel()

        assertEquals(model.instanceMessage.workflowId, model.workflowId)
    }
}
