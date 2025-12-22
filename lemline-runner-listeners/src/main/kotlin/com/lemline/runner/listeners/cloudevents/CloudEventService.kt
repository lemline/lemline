// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.cloudevents

import com.lemline.common.logger.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Utility for CloudEvent data extraction.
 *
 * Provides extraction of the `data` field from stored CloudEvent JSON
 * for expression evaluation and processing.
 */
object CloudEventService {
    private val logger = logger()

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
