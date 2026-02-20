// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
/**
 * Enum representing different types of workflow errors.
 *
 * @property type The string representation of the error type.
 * @property defaultStatus The default HTTP status code associated with the error type.
 *
 * cf. https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#standard-error-types
 */
enum class WorkflowErrorType(val type: String, val defaultStatus: Int) {
    CONFIGURATION("configuration", 400),
    VALIDATION("validation", 400),
    EXPRESSION("expression", 400),
    AUTHENTICATION("authentication", 401),
    AUTHORIZATION("authorization", 403),
    TIMEOUT("timeout", 408),
    COMMUNICATION("communication", 500),
    RUNTIME("runtime", 500),
}
/**
 * Custom kotlinx.serialization serializer for [WorkflowErrorType].
 * Serializes the enum based on its 'type' property (e.g., "configuration")
 * and deserializes by matching the string to the 'type' property.
 */
private object WorkflowErrorTypeSerializer : KSerializer<WorkflowErrorType> {
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
