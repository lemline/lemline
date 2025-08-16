package com.lemline.common.flexible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class FlexibleFieldSerializer<T>(
    private val serializer: KSerializer<T>
) : KSerializer<FlexibleField<T>> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleField", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FlexibleField<T>) {
        encoder.encodeString(value.serialized)
    }

    override fun deserialize(decoder: Decoder): FlexibleField<T> {
        return FlexibleField(decoder.decodeString(), serializer)
    }
}
