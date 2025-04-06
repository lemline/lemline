package com.lemline.common.json

import com.lemline.sw.nodes.JsonPointer
import com.lemline.sw.nodes.NodePosition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom kotlinx.serialization serializer for [NodePosition].
 * Serializes to/from the string representation of its JsonPointer.
 */
object NodePositionSerializer : KSerializer<NodePosition> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NodePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NodePosition) {
        // Use the jsonPointer's string representation for serialization
        encoder.encodeString(value.jsonPointer.toString())
    }

    override fun deserialize(decoder: Decoder): NodePosition {
        // Read the string, create a JsonPointer, then convert to NodePosition
        val jsonPointerString = decoder.decodeString()
        return JsonPointer(jsonPointerString).toPosition()
    }
} 