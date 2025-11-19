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
class ScheduleOutBoxModelTest {

    @Test
    fun `ScheduleOutboxModel can be created and fields accessed`() {
        val model = ScheduleModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        // Note: outboxScheduledFor and outboxDelayedUntil are initialized to initialScheduledFor
        // but may be modified by randomize(), so we just verify they're accessible

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.outboxScheduledFor, copy.outboxScheduledFor)
        assertEquals(model.scheduleAfter, copy.scheduleAfter)
        assertEquals(model.scheduleEvery, copy.scheduleEvery)
        assertEquals(model.scheduleCron, copy.scheduleCron)
    }

}
