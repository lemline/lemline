package com.lemline.swruntime.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object Json {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Converts an object to its JSON string representation
     */
    inline fun <reified T> toJson(value: T): String = json.encodeToString(value)

    inline fun <reified T> toJsonElement(value: T): JsonElement = json.encodeToJsonElement(value)

    /**
     * Creates an object from its JSON string representation
     */
    inline fun <reified T> fromJson(jsonString: String): T = json.decodeFromString(jsonString)

    inline fun <reified T> fromJsonElement(jsonElement: JsonElement): T = json.decodeFromJsonElement(jsonElement)
}

fun JsonElement.toJackson(): JsonNode = ObjectMapper().readTree(toString())

fun JsonNode.toKotlin(): JsonElement = Json.fromJson(toString())