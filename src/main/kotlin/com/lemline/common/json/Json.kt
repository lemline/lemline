package com.lemline.common.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal object Json {
    val jsonPretty = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Converts an object to its JSON string representation
     */
    inline fun <reified T> toJson(value: T): String = json.encodeToString(value)

    /**
     * Converts an object to its pretty JSON string representation
     */
    inline fun <reified T> toPrettyJson(value: T): String = jsonPretty.encodeToString(value)

    /**
     * Converts an object to a JSON element
     */
    inline fun <reified T> toJsonElement(value: T): JsonElement = json.encodeToJsonElement(value)

    /**
     * Creates an object from its JSON string representation
     */
    inline fun <reified T> fromJson(jsonString: String): T = json.decodeFromString(jsonString)
}

fun JsonElement.toJackson(): JsonNode = ObjectMapper().readTree(toString())

fun JsonNode.toKotlin(): JsonElement = Json.fromJson(toString())