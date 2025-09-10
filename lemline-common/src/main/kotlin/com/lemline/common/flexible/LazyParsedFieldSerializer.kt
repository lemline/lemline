// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.flexible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class LazyParsedFieldSerializer<T>(
    private val serializer: KSerializer<T>
) : KSerializer<LazyParsedField<T>> {
    override val descriptor = PrimitiveSerialDescriptor("LazyParsedField", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LazyParsedField<T>) {
        encoder.encodeSerializableValue(serializer, value.parsed)
    }

    override fun deserialize(decoder: Decoder): LazyParsedField<T> =
        LazyParsedField(decoder.decodeSerializableValue(serializer), serializer)
}
