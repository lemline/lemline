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
class WaitOutBoxModelTest {

    @Test
    fun `WaitOutboxModel serializes and deserializes and keep the same fields`() {
        val model = WaitModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)

        // default are removed from json string
        val status = if (model.runStatus == RunStatus.PENDING) "" else ""","rs":"${model.runStatus}""""
        // nullable properties
        val ra = nullable(model.runAt)

        Assertions.assertEquals(
            with(model) { """{"t":"w","id":"$id","i":${instanceMessage.toJsonString()}$status,"ra":$ra}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<WaitModel>(encoded)
        assertEquals(model, decoded)
    }

}
