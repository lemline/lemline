// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import com.lemline.common.ids.IdGenerator
import java.nio.ByteBuffer
import java.util.*
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class IDV7(val value: @Contextual UUID) {
    override fun toString(): String = value.toString()

    init {
        require(value.version() == 7) { "Invalid UUID version" }
    }

    fun toBytes(): ByteArray = value.toBytes()

    companion object {
        fun random(): IDV7 = IDV7(IdGenerator.generateV7())

        fun from(id: IDV7): IDV7 = IDV7(IdGenerator.deriveUuidV7FromV7(id.value))

        fun from(bytes: ByteArray): IDV7 = IDV7(bytes.toUUID())

        fun from(str: String): IDV7 = IDV7(UUID.fromString(str))

        private fun ByteArray.toUUID(): UUID {
            require(this.size == 16) { "ByteArray must be exactly 16 bytes for UUID conversion, but was ${this.size}" }
            val bb = ByteBuffer.wrap(this)
            val mostSignificantBits = bb.long
            val leastSignificantBits = bb.long
            return UUID(mostSignificantBits, leastSignificantBits)
        }

        private fun UUID.toBytes(): ByteArray =
            ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

    }

}
