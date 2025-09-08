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
class WaitOutBoxModelTest {

    @Test
    fun `WaitOutboxModel serializes and deserializes and keep the same fields`() {
        val model = WaitOutboxModel.random()
        val encoded = model.toJsonString()

        // default are removed from json string
        val status = if (model.outBoxStatus == OutBoxStatus.PENDING) "" else ""","s":"${model.outBoxStatus}""""
        // nullable properties
        val sf = nullable(model.outboxScheduledFor)

        Assertions.assertEquals(
            with(model) { """{"id":"$id","i":${instanceMessage.toJsonString()}$status,"f":$sf}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<WaitOutboxModel>(encoded)
        assertEquals(model, decoded)
    }

}
