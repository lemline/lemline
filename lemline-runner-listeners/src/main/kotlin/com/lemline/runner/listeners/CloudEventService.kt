// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Service for CloudEvent serialization, deserialization, and transformation.
 *
 * Provides utilities to:
 * - Serialize CloudEvents to JSON strings
 * - Deserialize JSON strings to CloudEvents
 * - Parse CloudEvent data as JSON
 * - Transform CloudEvents based on ListenAndReadAs configuration
 */
object CloudEventService {
    private val logger = logger()
    private val jsonFormat = JsonFormat()

    /**
     * Serializes a CloudEvent to a JSON string.
     *
     * Uses the official CloudEvents Jackson format for serialization,
     * producing a valid CloudEvents v1.0 JSON representation.
     *
     * @param event The CloudEvent to serialize
     * @return JSON string representation of the CloudEvent
     */
    fun serialize(event: CloudEvent): String {
        return String(jsonFormat.serialize(event), Charsets.UTF_8)
    }

    /**
     * Deserializes a JSON string to a CloudEvent.
     *
     * Parses a CloudEvents v1.0 JSON representation back into a CloudEvent object.
     *
     * @param json The JSON string to deserialize
     * @return The deserialized CloudEvent
     * @throws IllegalArgumentException if the JSON is not a valid CloudEvent
     */
    fun deserialize(json: String): CloudEvent {
        return jsonFormat.deserialize(json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Parses a CloudEvent JSON string according to the specified ListenAndReadAs mode.
     *
     * Depending on the `readAs` parameter, extracts and returns:
     * - DATA: The data portion of the CloudEvent as JSON
     * - ENVELOPE: The entire CloudEvent as a JSON object
     * - RAW: The raw data as a base64-encoded string
     *
     * @param str The CloudEvent JSON string to parse
     * @param readAs The ListenAndReadAs mode for parsing
     * @return The parsed CloudEvent data as JsonElement
     */
    fun parseStringAsData(str: String, readAs: ListenAndReadAs = ListenAndReadAs.DATA): JsonElement {
        val cloudEvent = deserialize(str)

        return when (readAs) {
            ListenAndReadAs.RAW -> parseRaw(cloudEvent)
            ListenAndReadAs.DATA -> parseData(cloudEvent)
            ListenAndReadAs.ENVELOPE -> parseEnvelope(cloudEvent)

        }
    }

    /** Parses the raw data of a CloudEvent as a base64-encoded string.
     *
     * @param event The CloudEvent from which to parse raw data
     * @return The raw data as a base64-encoded JsonPrimitive, or JsonNull if no data
     */
    fun parseRaw(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull

        return JsonPrimitive(Base64.getEncoder().encodeToString(data.toBytes()))
    }

    /**
     * Parses the data portion of a CloudEvent as JSON.
     *
     * Attempts to parse the `data` field of the CloudEvent as a JSON element.
     * If parsing fails, returns the raw data as a base64-encoded string.
     *
     * @param event The CloudEvent from which to parse data
     * @return The parsed data as JsonElement, or JsonNull if no data
     */
    fun parseData(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull

        return try {
            Json.parseToJsonElement(String(data.toBytes(), Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn { "Failed to parse CloudEvent data as JSON, returning as base64: id=${event.id}, type=${event.type}, source=${event.source}, contentType=${event.dataContentType}, error=${e.message}" }
            parseRaw(event)
        }
    }

    /**
     * Parses the entire CloudEvent as a JsonObject envelope.
     *
     * Constructs a JsonObject containing all standard CloudEvent attributes,
     * extension attributes, and the data field.
     *
     * @param cloudEvent The CloudEvent to parse
     * @return The CloudEvent represented as a JsonObject
     */
    fun parseEnvelope(cloudEvent: CloudEvent): JsonElement = buildJsonObject {
        // Required attributes
        put("specversion", cloudEvent.specVersion.toString())
        put("id", cloudEvent.id)
        put("source", cloudEvent.source.toString())
        put("type", cloudEvent.type)
        // Optional attributes
        cloudEvent.time?.let { put("time", it.toString()) }
        cloudEvent.subject?.let { put("subject", it) }
        cloudEvent.dataContentType?.let { put("datacontenttype", it) }
        cloudEvent.dataSchema?.let { put("dataschema", it.toString()) }
        // Extension attributes (custom)
        for (name in cloudEvent.extensionNames) {
            when (val value = cloudEvent.getExtension(name)) {
                is String -> put(name, value)
                is Number -> put(name, value)
                is Boolean -> put(name, value)
                else -> value?.let { put(name, it.toString()) }
            }
        }
        // Data
        put("data", parseData(cloudEvent))
    }
}
