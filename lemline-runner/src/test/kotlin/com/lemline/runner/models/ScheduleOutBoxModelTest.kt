// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.runner.outbox.bases.RunStatus
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
        val model = ScheduleModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)

        // default are removed from json string
        val status = if (model.runStatus == RunStatus.PENDING) "" else ""","rs":"${model.runStatus}""""
        // nullable properties
        val ra = nullable(model.runAt)
        val sa = nullable(model.scheduleAfter)
        val se = nullable(model.scheduleEvery)
        val sc = nullable(model.scheduleCron)
        val sz = nullable(model.scheduleZone)

        Assertions.assertEquals(
            with(model) { """{"t":"s","id":"$id","i":${instanceMessage.toJsonString()}$status,"ra":$ra,"sa":$sa,"se":$se,"sc":$sc,"sz":$sz}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<ScheduleModel>(encoded)
        assertEquals(model, decoded)
    }

}
