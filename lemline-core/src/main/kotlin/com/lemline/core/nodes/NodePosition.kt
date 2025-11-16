// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a task initialPosition in the workflow.
 *
 * This class is used to represent the initialPosition of a task in the workflow.
 *
 * @property path The list of path components.
 */
@Serializable(with = NodePositionSerializer::class)
data class NodePosition(private val path: List<String> = listOf()) {
    /**
     * Json pointer representation. (e.g., "/do/0/do")
     */
    val positionPointer by lazy {
        when (path.isEmpty()) {
            true -> PositionPointer.root
            else -> PositionPointer("/${path.joinToString("/")}")
        }
    }

    /**
     * Gets the string representation of the JSON pointer.
     *
     * @return The JSON pointer string (e.g., "/do/0/do")
     */
    override fun toString() = positionPointer.toString()

    /**
     * Adds a name component to the JSON pointer path.
     *
     * This function ensures that the name does not contain a slash ('/'),
     * is not an integer, and is not one of the reserved tokens defined in TaskToken.
     *
     * @param name The name component to add to the path.
     * @return A new TaskPosition with the added name component.
     * @throws IllegalArgumentException if the name contains a slash, is an integer, or is a reserved token.
     */
    fun addName(name: String): NodePosition {
        require(!name.contains("/")) { "Task name $name must not contain '/'" }
        require(name.toIntOrNull() == null) { "Task name $name must not be an integer" }
        Token.entries.map { it.token }.let {
            require(!it.contains(name)) { "Task name $name must not be one of ${it.joinToString()}" }
        }
        return NodePosition(path + name)
    }

    /**
     * Adds a token property to the path.
     *
     * @param token The property name to add
     * @return A new Position with the added property
     */
    fun addToken(token: Token): NodePosition = NodePosition(path + token.token)

    /**
     * Adds an childIndex to the path.
     *
     * @param index The numeric childIndex to add
     * @return A new Position with the added childIndex
     */
    fun addIndex(index: Int): NodePosition = NodePosition(path + index.toString())

    /**
     * Gets the parent path by removing the last component.
     *
     * @return A new Position with the parent path, or null if this is the root
     */
    val parent: NodePosition?
        get() = if (path.isEmpty()) null else NodePosition(path.dropLast(1))

    companion object {
        val root = PositionPointer.root.toPosition()

        fun from(path: String) = PositionPointer(path).toPosition()

        fun fromJsonString(jsonString: String): NodePosition = LemlineJson.decodeFromString(jsonString)

    }
}

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
