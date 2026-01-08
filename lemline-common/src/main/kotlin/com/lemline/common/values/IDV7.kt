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

    /**
     * Derives a new IDV7 from this ID with the given suffix.
     * Useful for creating related IDs (e.g., model ID → message ID).
     *
     * @param suffix A string to differentiate derived IDs (e.g., "-resume", "-child")
     * @return A deterministic IDV7 derived from this ID and the suffix
     */
    fun derive(suffix: String): IDV7 = IDV7(IdGenerator.deriveUuidV7FromV7(value, suffix))

    companion object {
        fun random(): IDV7 = IDV7(IdGenerator.generateV7())

        fun from(id: IDV7): IDV7 = IDV7(IdGenerator.deriveUuidV7FromV7(id.value))

        fun from(bytes: ByteArray): IDV7 = IDV7(bytes.toUUID())

        fun from(str: String): IDV7 = IDV7(UUID.fromString(str))

        /**
         * Derives a deterministic IDV7 from a base ID, position, execution key, and optional suffix.
         * Used for generating idempotent message and database IDs.
         *
         * The derivation uses SHA-256 to create a unique but reproducible ID from:
         * - baseId: The workflow instance ID (provides workflow-level uniqueness)
         * - position: The node position in the workflow tree (provides task-level uniqueness)
         * - executionKey: Concatenated execution indices from the stack (e.g., "0-0-2-0")
         * - suffix: Optional type discriminator (e.g., "-wait", "-retry", "-parent")
         *
         * @param baseId The source IDV7 (typically workflowId)
         * @param position The node position in the workflow
         * @param executionKey The execution key from NodeStack.deriveExecutionKey()
         * @param suffix Optional suffix for type differentiation
         * @return A deterministic IDV7 unique to this execution context
         */
        fun deriveIdempotentId(
            baseId: IDV7,
            position: NodePosition,
            executionKey: String,
            suffix: String = ""
        ): IDV7 {
            val salt = "$position:$executionKey$suffix"
            return IDV7(IdGenerator.deriveUuidV7FromV7(baseId.value, salt))
        }

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
