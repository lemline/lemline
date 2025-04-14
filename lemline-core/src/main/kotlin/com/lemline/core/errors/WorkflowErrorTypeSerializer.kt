package com.lemline.core.errors

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom kotlinx.serialization serializer for [WorkflowErrorType].
 * Serializes the enum based on its 'type' property (e.g., "configuration")
 * and deserializes by matching the string to the 'type' property.
 */
object WorkflowErrorTypeSerializer : KSerializer<WorkflowErrorType> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WorkflowErrorType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: WorkflowErrorType) {
        // Encode the string value of the 'type' property
        encoder.encodeString(value.type)
    }

    override fun deserialize(decoder: Decoder): WorkflowErrorType {
        // Decode the string
        val typeString = decoder.decodeString()
        // Find the enum constant whose 'type' matches the decoded string
        return WorkflowErrorType.entries.firstOrNull { it.type == typeString }
            ?: throw IllegalArgumentException("Unknown WorkflowErrorType type: $typeString")
    }
} 