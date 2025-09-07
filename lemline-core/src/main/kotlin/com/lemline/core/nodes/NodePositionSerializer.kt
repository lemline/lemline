// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom kotlinx.serialization serializer for [com.lemline.core.nodes.NodePosition].
 * Serializes to/from the string representation of its JsonPointer.
 */
internal object NodePositionSerializer : KSerializer<NodePosition> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NodePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NodePosition) {
        // Use the jsonPointer's string representation for serialization
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): NodePosition {
        // Read the string, create a JsonPointer, then convert to NodePosition
        return NodePosition.from(decoder.decodeString())
    }
}
