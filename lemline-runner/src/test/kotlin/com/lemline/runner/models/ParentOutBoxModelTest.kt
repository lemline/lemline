// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
class ParentWaitingModelTest {

    @Test
    fun `ParentWaitingModel serializes and deserializes and keep the same fields`() {
        val model = ParentWaitingModel.random()
        val encoded = model.toJsonString()

        // Verify basic structure
        val decoded = LemlineJson.decodeFromString<ParentWaitingModel>(encoded)
        assertEquals(model.id, decoded.id)
        assertEquals(model.instanceMessage.workflowInfo, decoded.instanceMessage.workflowInfo)
        assertEquals(model, decoded)
    }

}
