// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions

@ExperimentalTime
@ExperimentalSerializationApi
class ScheduleOutBoxModelTest {

    @Test
    fun `ScheduleOutboxModel serializes and deserializes and keep the same fields`() {
        val model = ScheduleOutboxModel.random()
        val encoded = model.toJsonString()

        // Verify round-trip serialization
        val decoded = LemlineJson.decodeFromString<ScheduleOutboxModel>(encoded)
        assertEquals(model.id, decoded.id)
        assertEquals(model.outBoxStatus, decoded.outBoxStatus)
        assertEquals(model.outboxScheduledFor, decoded.outboxScheduledFor)
        assertEquals(model.scheduleAfter, decoded.scheduleAfter)
        assertEquals(model.scheduleEvery, decoded.scheduleEvery)
        assertEquals(model.scheduleCron, decoded.scheduleCron)
        assertEquals(model.scheduleZone, decoded.scheduleZone)
        assertEquals(model.instanceMessage.workflowInfo, decoded.instanceMessage.workflowInfo)
        assertEquals(model, decoded)
    }

}
