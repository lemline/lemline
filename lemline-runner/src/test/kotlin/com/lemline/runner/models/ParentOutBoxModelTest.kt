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
class ParentOutBoxModelTest {

    @Test
    fun `ParentOutboxModel serializes and deserializes and keep the same fields`() {
        val model = ParentModel.random()
        val encoded = model.toJsonString()

        // default are removed from json string
        val status = if (model.runStatus == RunStatus.PENDING) "" else ""","s":"${model.runStatus}""""
        // nullable properties
        val sf = nullable(model.runAt)

        Assertions.assertEquals(
            with(model) { """{"t":"p","id":"$id","i":${instanceMessage.toJsonString()}$status,"f":$sf}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<ParentModel>(encoded)
        assertEquals(model, decoded)
    }

}
