// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
class WaitOutBoxModelTest {

    @Test
    fun `WaitOutboxModel can be created and fields accessed`() {
        val model = WaitOutboxModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        assertNotNull(model.scheduledFor)
        assertEquals(model.scheduledFor, model.outboxScheduledFor)
        assertEquals(model.scheduledFor, model.outboxDelayedUntil)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.scheduledFor, copy.scheduledFor)
    }

}
