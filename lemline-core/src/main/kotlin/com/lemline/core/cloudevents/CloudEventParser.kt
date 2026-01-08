// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object CloudEventParser {

    private val logger = logger()

    fun CloudEvent.parseData(): JsonElement =
        data?.let { parseJsonOrNull(String(it.toBytes())) } ?: JsonNull

    fun CloudEvent.toJsonElement(readAs: ListenAndReadAs): JsonElement = when (readAs) {
        ListenAndReadAs.DATA -> parseDataContent()
        ListenAndReadAs.ENVELOPE -> buildEnvelope()
        ListenAndReadAs.RAW -> data?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it.toBytes())) } ?: JsonNull
    }

    private fun CloudEvent.parseDataContent(): JsonElement =
        data?.let { parseJsonOrPrimitive(String(it.toBytes())) } ?: JsonNull

    private fun CloudEvent.buildEnvelope(): JsonElement = buildJsonObject {
        put("specversion", JsonPrimitive(specVersion.toString()))
        put("id", JsonPrimitive(id))
        put("source", JsonPrimitive(source.toString()))
        put("type", JsonPrimitive(type))
        time?.let { put("time", JsonPrimitive(it.toString())) }
        subject?.let { put("subject", JsonPrimitive(it)) }
        dataSchema?.let { put("dataschema", JsonPrimitive(it.toString())) }
        dataContentType?.let { put("datacontenttype", JsonPrimitive(it)) }
        data?.let { put("data", parseJsonOrPrimitive(String(it.toBytes()))) }
    }

    private fun parseJsonOrNull(content: String): JsonElement =
        runCatching { Json.parseToJsonElement(content) }
            .onFailure { e -> logger.warn(e) { "Failed to parse CloudEvent data as JSON" } }
            .getOrDefault(JsonNull)

    private fun parseJsonOrPrimitive(content: String): JsonElement =
        runCatching { Json.parseToJsonElement(content) }.getOrElse { JsonPrimitive(content) }
}
