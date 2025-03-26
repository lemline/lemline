package com.lemline.swruntime.serialization

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object ObjectNodeSerializer : KSerializer<ObjectNode> {
    private val mapper = ObjectMapper()

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ObjectNodeAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ObjectNode) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ObjectNode {
        val json = decoder.decodeString()
        return mapper.readTree(json) as ObjectNode
    }
}