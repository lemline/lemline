// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.lemline.common.json.*
import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.util.*

object CloudEventParser {

    private val logger = logger()

    fun CloudEvent.parseData(): JsonNode =
        data?.let { parseJsonOrNull(String(it.toBytes())) } ?: NullNode.instance

    fun CloudEvent.toJsonNode(readAs: ListenAndReadAs): JsonNode = when (readAs) {
        ListenAndReadAs.DATA -> parseDataContent()
        ListenAndReadAs.ENVELOPE -> buildEnvelope()
        ListenAndReadAs.RAW -> data?.let { jsonValue(Base64.getEncoder().encodeToString(it.toBytes())) } ?: NullNode.instance
    }

    /**
     * Formats a CloudEvent as a human-readable string for logging.
     * Uses the envelope format with data rendered as JSON string.
     */
    fun CloudEvent.toReadableString(): String = LemlineJson.encodeToString(buildEnvelope())

    private fun CloudEvent.parseDataContent(): JsonNode =
        data?.let { parseJsonOrPrimitive(String(it.toBytes())) } ?: NullNode.instance

    private fun CloudEvent.buildEnvelope(): JsonNode = buildJsonObject {
        put("specversion", jsonValue(specVersion.toString()))
        put("id", jsonValue(id))
        put("source", jsonValue(source.toString()))
        put("type", jsonValue(type))
        time?.let { put("time", jsonValue(it.toString())) }
        subject?.let { put("subject", jsonValue(it)) }
        dataSchema?.let { put("dataschema", jsonValue(it.toString())) }
        dataContentType?.let { put("datacontenttype", jsonValue(it)) }
        data?.let { put("data", parseJsonOrPrimitive(String(it.toBytes()))) }
        // Include extension attributes
        extensionNames.forEach { name ->
            getExtension(name)?.let { value ->
                put(name, value.toJsonPrimitive())
            }
        }
    }

    private fun Any.toJsonPrimitive(): JsonNode = when (this) {
        is String -> jsonValue(this)
        is Number -> jsonValue(this)
        is Boolean -> jsonValue(this)
        else -> jsonValue(toString())
    }

    private fun parseJsonOrNull(content: String): JsonNode =
        runCatching { parseJson(content) }
            .onFailure { e -> logger.warn(e) { "Failed to parse CloudEvent data as JSON" } }
            .getOrDefault(NullNode.instance)

    private fun parseJsonOrPrimitive(content: String): JsonNode =
        runCatching { parseJson(content) }.getOrElse { jsonValue(content) }
}
