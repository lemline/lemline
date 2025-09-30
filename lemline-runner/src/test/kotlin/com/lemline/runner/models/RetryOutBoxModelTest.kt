// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.runner.outbox.bases.RunStatus
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
        val model = RetryModel.random()
        val encoded = model.toJsonString()

        // default are removed from json string
        val status = if (model.runStatus == RunStatus.PENDING) "" else ""","s":"${model.runStatus}""""
        // nullable properties
        val sf = nullable(model.runAt)
        val em = nullable(model.errorMessage)

        Assertions.assertEquals(
            with(model) { """{"t":"r","id":"$id","i":${instanceMessage.toJsonString()}$status,"f":$sf,"er":"$errorReason","ec":"$errorClass","em":$em,"es":"$errorStackTrace"}""" },
            encoded,
        )

        val decoded = LemlineJson.decodeFromString<RetryModel>(encoded)
        assertEquals(model, decoded)
    }

}
