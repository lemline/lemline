// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Centralized service for CloudEvent serialization, deserialization, and data extraction.
 *
 * This object provides a single point of access for all CloudEvent JSON operations,
 * ensuring consistent handling across the codebase.
 *
 * ## Operations
 *
 * - **Serialization**: Convert CloudEvent to JSON string for storage or messaging
 * - **Deserialization**: Parse JSON string back to CloudEvent
 * - **Data extraction**: Extract the `data` field from CloudEvent for expression evaluation
 *
 * ## Usage
 *
 * ```kotlin
 * // Serialize
 * val json = CloudEventService.serialize(cloudEvent)
 *
 * // Deserialize
 * val event = CloudEventService.deserialize(jsonString)
 *
 * // Parse data from CloudEvent object
 * val data = CloudEventService.parseData(cloudEvent)
 *
 * // Extract data from stored JSON string
 * val data = CloudEventService.extractData(storedJson)
 * ```
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
     * Parses the data payload from a CloudEvent object.
     *
     * Extracts and parses the `data` field from the CloudEvent as a JsonElement.
     * Returns [JsonNull] if the data is null, empty, or cannot be parsed as JSON.
     *
     * @param event The CloudEvent to extract data from
     * @return The parsed data as JsonElement, or JsonNull if unavailable
     */
    fun parseData(event: CloudEvent): JsonElement {
        val data = event.data ?: return JsonNull

        return try {
            val bytes = data.toBytes()
            if (bytes.isEmpty()) {
                JsonNull
            } else {
                Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse CloudEvent data as JSON" }
            JsonNull
        }
    }

    /**
     * Extracts the data portion from a stored CloudEvent JSON string.
     *
     * Parses the stored JSON and extracts only the `data` field.
     * This is useful for expression evaluation where only the event payload is needed.
     *
     * @param storedJson The stored CloudEvent as JSON string
     * @return The extracted data as JsonElement, or JsonNull if unavailable
     */
    fun extractData(storedJson: String): JsonElement = try {
        val cloudEvent = Json.parseToJsonElement(storedJson)
        if (cloudEvent is JsonObject) {
            cloudEvent["data"] ?: JsonNull
        } else {
            cloudEvent
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to extract data from stored CloudEvent" }
        JsonNull
    }
}
