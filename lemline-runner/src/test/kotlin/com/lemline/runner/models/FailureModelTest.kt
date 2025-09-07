package com.lemline.runner.models

import com.lemline.common.json.LemlineJson
import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import org.junit.jupiter.api.Assertions

@ExperimentalTime
class FailureModelTest {

    @Test
    fun `FailureModel serializes and deserializes and keep the same fields`() {
        val model = FailureModel.random()
        val encoded = model.toJsonString()

        // nullable properties
        val i = nullable(model.instanceMessage)
        val p = nullable(model.payload)
        val em = nullable(model.errorMessage)

        Assertions.assertEquals(
            with(model) { """{"id":"$id","i":$i,"p":$p,"er":"$errorReason","ec":"$errorClass","em":$em,"es":"$errorStackTrace"}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<FailureModel>(encoded)
        assertEquals(model, decoded)
    }
}
