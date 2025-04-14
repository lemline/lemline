package com.lemline.core.json

import com.lemline.core.nodes.JsonPointer
import com.lemline.core.nodes.NodePosition
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
internal object NodePositionSerializer : KSerializer<NodePosition> {

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