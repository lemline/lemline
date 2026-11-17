// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Only typed Kotlin DTO codecs use this bridge; workflow data stays in Jackson trees. */
object JsonNodeSerializer : KSerializer<JsonNode> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: JsonNode) = encoder.encodeSerializableValue(JsonElement.serializer(), value.toKotlinTree())
    override fun deserialize(decoder: Decoder): JsonNode = decoder.decodeSerializableValue(JsonElement.serializer()).toJacksonNode()
}
object ObjectNodeSerializer : KSerializer<ObjectNode> {
    override val descriptor: SerialDescriptor = JsonNodeSerializer.descriptor
    override fun serialize(encoder: Encoder, value: ObjectNode) = JsonNodeSerializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): ObjectNode = JsonNodeSerializer.deserialize(decoder).jsonObject
}
object ArrayNodeSerializer : KSerializer<ArrayNode> {
    override val descriptor: SerialDescriptor = JsonNodeSerializer.descriptor
    override fun serialize(encoder: Encoder, value: ArrayNode) = JsonNodeSerializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): ArrayNode = JsonNodeSerializer.deserialize(decoder).jsonArray
}
fun JsonElement.toJacksonNode(): JsonNode = when (this) {
    JsonNull -> com.fasterxml.jackson.databind.node.NullNode.instance
    is JsonObject -> ObjectNode(mapValues { it.value.toJacksonNode() })
    is JsonArray -> ArrayNode(map { it.toJacksonNode() })
    is JsonPrimitive -> if (isString) jsonValue(content) else parseJson(content)
}
fun JsonNode.toKotlinTree(): JsonElement = when {
    isNull -> JsonNull
    isObject -> JsonObject(properties().associate { it.key to it.value.toKotlinTree() })
    isArray -> JsonArray(map { it.toKotlinTree() })
    isTextual -> JsonPrimitive(textValue())
    isBinary -> JsonPrimitive(asText())
    isBoolean -> JsonPrimitive(booleanValue())
    (isDouble || isFloat) && !doubleValue().isFinite() -> JsonPrimitive(asText())
    isNumber -> JsonUnquotedLiteral(asText())
    else -> error("Unsupported JSON node: $nodeType")
}
