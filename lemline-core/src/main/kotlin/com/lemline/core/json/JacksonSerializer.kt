// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.json

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
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
        // 1. Convert the object to a JSON string using Jackson
        val jsonString = jacksonMapper.writeValueAsString(value)
        // 2. Parse the JSON string into a kotlinx.serialization JsonElement
        val jsonElement = kotlinxJson.parseToJsonElement(jsonString)
        // 3. Encode the JsonElement using the provided encoder
        encoder.encodeSerializableValue(kotlinxJson.serializersModule.serializer<JsonElement>(), jsonElement)
    }

    override fun deserialize(decoder: Decoder): T {
        require(decoder is JsonDecoder) { "This serializer can only be used with Json format" }
        // 1. Decode the input into a kotlinx.serialization JsonElement
        val jsonElement = decoder.decodeSerializableValue(kotlinxJson.serializersModule.serializer<JsonElement>())
        // 2. Convert the JsonElement back to a JSON string
        val jsonString = Json.encodeToString(kotlinxJson.serializersModule.serializer<JsonElement>(), jsonElement)
        // 3. Use Jackson to parse the JSON string back into the target object type
        return jacksonMapper.readValue(jsonString, targetClassJava)
    }
}
