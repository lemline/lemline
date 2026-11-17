// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states.protobuf

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*

internal fun JsonNode.toProtoJsonValue(): Any? =
    when (this) {
        NullNode.instance -> null
        is ObjectNode -> toProtoJsonStruct()
        is ArrayNode -> toProtoJsonListValue()
        is ValueNode -> toProtoJsonValue()
        else -> error("Unsupported JSON node: $nodeType")
    }

internal fun ObjectNode.toProtoJsonStruct(): Map<String, *> =
    mapValues { (_, value) -> value.toProtoJsonValue() }

internal fun List<JsonNode>.toProtoJsonListValue(): List<*> =
    map { it.toProtoJsonValue() }

internal fun ArrayNode.toProtoJsonListValue(): List<*> = this.toList().toProtoJsonListValue()

internal fun Any?.toRuntimeJsonNode(): JsonNode = when (this) {
    null -> NullNode.instance
    is String -> jsonValue(this)
    is Boolean -> jsonValue(this)
    is Number -> toJsonPrimitivePreferIntegralNumber()
    is Map<*, *> -> toRuntimeObjectNodeFromUntypedMap()
    is List<*> -> ArrayNode(map { it.toRuntimeJsonNode() })
    else -> jsonValue(toString())
}

internal fun Map<String, *>.toRuntimeObjectNode(): ObjectNode =
    ObjectNode(mapValues { (_, value) -> value.toRuntimeJsonNode() })

internal fun List<*>.toRuntimeJsonNodeList(): List<JsonNode> =
    map { it.toRuntimeJsonNode() }

private fun ValueNode.toProtoJsonValue(): Any? = when {
    isNull -> null
    isTextual -> textValue()
    isBinary -> asText()
    isBoolean -> booleanValue()
    isNumber -> doubleValue() // google.protobuf.Value numbers retain the existing double contract.
    else -> error("Unsupported JSON scalar: $nodeType")
}

private fun Map<*, *>.toRuntimeObjectNodeFromUntypedMap(): ObjectNode =
    ObjectNode(
        entries.associate { (key, value) ->
            val stringKey = key as? String
                ?: error("Expected struct key to be String but found ${key?.javaClass?.name ?: "null"}")
            stringKey to value.toRuntimeJsonNode()
        }
    )

private fun Number.toJsonPrimitivePreferIntegralNumber(): JsonNode {
    val doubleValue = toDouble()
    if (doubleValue.isFinite() && doubleValue >= Long.MIN_VALUE.toDouble() && doubleValue <= Long.MAX_VALUE.toDouble()) {
        val longValue = doubleValue.toLong()
        if (doubleValue == longValue.toDouble()) {
            return jsonValue(longValue)
        }
    }
    return jsonValue(doubleValue)
}
