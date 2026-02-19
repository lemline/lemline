// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal fun JsonElement.toProtoJsonValue(): Any? =
    when (this) {
        JsonNull -> null
        is JsonObject -> toProtoJsonStruct()
        is JsonArray -> toProtoJsonListValue()
        is JsonPrimitive -> toProtoJsonValue()
    }

internal fun JsonObject.toProtoJsonStruct(): Map<String, *> =
    mapValues { (_, value) -> value.toProtoJsonValue() }

internal fun List<JsonElement>.toProtoJsonListValue(): List<*> =
    map { it.toProtoJsonValue() }

internal fun JsonArray.toProtoJsonListValue(): List<*> = this.toList().toProtoJsonListValue()

internal fun Any?.toKotlinJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> toJsonPrimitivePreferIntegralNumber()
    is Map<*, *> -> toKotlinJsonObjectFromUntypedMap()
    is List<*> -> JsonArray(map { it.toKotlinJsonElement() })
    else -> JsonPrimitive(toString())
}

internal fun Map<String, *>.toKotlinJsonObject(): JsonObject =
    JsonObject(mapValues { (_, value) -> value.toKotlinJsonElement() })

internal fun List<*>.toKotlinJsonElementList(): List<JsonElement> =
    map { it.toKotlinJsonElement() }

private fun JsonPrimitive.toProtoJsonValue(): Any? {
    if (isString) {
        return content
    }
    booleanOrNull?.let { return it }
    doubleOrNull?.let { return it }
    return content
}

private fun Map<*, *>.toKotlinJsonObjectFromUntypedMap(): JsonObject =
    JsonObject(
        entries.associate { (key, value) ->
            val stringKey = key as? String
                ?: error("Expected struct key to be String but found ${key?.javaClass?.name ?: "null"}")
            stringKey to value.toKotlinJsonElement()
        }
    )

private fun Number.toJsonPrimitivePreferIntegralNumber(): JsonPrimitive {
    val doubleValue = toDouble()
    if (doubleValue.isFinite() && doubleValue >= Long.MIN_VALUE.toDouble() && doubleValue <= Long.MAX_VALUE.toDouble()) {
        val longValue = doubleValue.toLong()
        if (doubleValue == longValue.toDouble()) {
            return JsonPrimitive(longValue)
        }
    }
    return JsonPrimitive(doubleValue)
}
