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

        // default are removed from json string
        val status = if (model.outBoxStatus == OutBoxStatus.PENDING) "" else ""","s":"${model.outBoxStatus}""""
        // nullable properties
        val sf = nullable(model.outboxScheduledFor)
        val sa = nullable(model.scheduleAfter)
        val se = nullable(model.scheduleEvery)
        val sc = nullable(model.scheduleCron)
        val sz = nullable(model.scheduleZone)

        Assertions.assertEquals(
            with(model) { """{"t":"s","id":"$id","i":${instanceMessage.toJsonString()}$status,"f":$sf,"sa":$sa,"se":$se,"sc":$sc,"sz":$sz}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<ScheduleOutboxModel>(encoded)
        assertEquals(model, decoded)
    }

}
