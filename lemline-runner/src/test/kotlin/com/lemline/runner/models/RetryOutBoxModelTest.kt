// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions

internal fun nullable(value: JsonSerializable?): String = value?.toJsonString() ?: "null"
internal fun nullable(value: String?): String = value?.let { "\"$value\"" } ?: "null"
internal fun nullable(value: Instant?): String = value?.let { "\"$value\"" } ?: "null"
internal fun nullable(value: IDV7?): String = value?.let { "\"$value\"" } ?: "null"
internal fun nullable(value: JsonElement?): String = value?.toString() ?: "null"

@ExperimentalSerializationApi
class RetryOutBoxModelTest {


    @Test
    fun `RetryOutboxModel serializes and deserializes and keep the same fields`() {
        val model = RetryOutboxModel.random()
        val encoded = model.toJsonString()

        // Verify round-trip serialization
        val decoded = LemlineJson.decodeFromString<RetryOutboxModel>(encoded)
        assertEquals(model.id, decoded.id)
        assertEquals(model.outBoxStatus, decoded.outBoxStatus)
        assertEquals(model.outboxScheduledFor, decoded.outboxScheduledFor)
        assertEquals(model.errorReason, decoded.errorReason)
        assertEquals(model.errorClass, decoded.errorClass)
        assertEquals(model.errorMessage, decoded.errorMessage)
        assertEquals(model.errorStackTrace, decoded.errorStackTrace)
        assertEquals(model.instanceMessage.workflowInfo, decoded.instanceMessage.workflowInfo)
        assertEquals(model, decoded)
    }

}
