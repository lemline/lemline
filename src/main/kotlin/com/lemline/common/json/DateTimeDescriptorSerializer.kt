package com.lemline.common.json

import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Custom kotlinx.serialization serializer for [DateTimeDescriptor].
 * Serializes to/from ISO 8601 string format (e.g., "2023-10-27T10:15:30Z").
 */
object DateTimeDescriptorSerializer : KSerializer<DateTimeDescriptor> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DateTimeDescriptor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DateTimeDescriptor) {
        encoder.encodeString(value.iso8601())
    }

    override fun deserialize(decoder: Decoder): DateTimeDescriptor {
        val dateString = decoder.decodeString()
        // Parse the string back to Instant and create DateTimeDescriptor
        val instant = Instant.parse(dateString)
        // Use the known factory method
        return DateTimeDescriptor.from(instant)
    }
} 