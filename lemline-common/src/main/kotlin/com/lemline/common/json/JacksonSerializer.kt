// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.serializer

/**
 * Base class for KSerializers that delegate JSON handling to Jackson's ObjectMapper.
 * Uses kotlinx.serialization.json.JsonElement as the intermediate representation.
 */
abstract class JacksonSerializer<T : Any>(private val targetClassJava: Class<T>) : KSerializer<T> {
    // Use the correctly configured instances from the Json object
    internal open val jacksonMapper: ObjectMapper by lazy { LemlineJson.jacksonMapper }
    internal open val kotlinxJson by lazy { LemlineJson.json } // Use the configured instance

    // Use the descriptor for JsonElement from the configured instance
    override val descriptor: SerialDescriptor by lazy {
        kotlinxJson.serializersModule.serializer<JsonElement>().descriptor
    }

    override fun serialize(encoder: Encoder, value: T) {
        require(encoder is JsonEncoder) { "This serializer can only be used with Json format" }
        encoder.encodeJsonElement(jacksonMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value).toKotlinTree())
    }

    override fun deserialize(decoder: Decoder): T {
        require(decoder is JsonDecoder) { "This serializer can only be used with Json format" }
        return jacksonMapper.treeToValue(decoder.decodeJsonElement().toJacksonNode(), targetClassJava)
    }
}
