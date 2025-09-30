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
class ForkModelTest {

    @Test
    fun `ForkModel serializes and deserializes and keep the same fields`() {
        val model = ForkModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)

        // nullable properties
        val i = LemlineJson.encodeToString(model.workflowInfo)
        val p = LemlineJson.encodeToString(model.forkPosition)
        val fo = nullable(model.forkOutput)
        val status = if (model.runStatus == RunStatus.PENDING) "" else ""","rs":"${model.runStatus}""""
        val ra = nullable(model.runAt)

        Assertions.assertEquals(
            with(model) { """{"t":"k","id":"$id","i":$i,"fi":"$forkId","fp":$p,"fn":"$forkName","fo":$fo$status,"ra":$ra}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<ForkModel>(encoded)
        assertEquals(model, decoded)
    }
}
