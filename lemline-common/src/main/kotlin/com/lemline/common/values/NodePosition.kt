// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents a static position in the workflow definition tree.
 *
 * Uses JSON Pointer notation (RFC 6901) to identify nodes.
 * Examples: "/do/taskA", "/for/do/processItem", "/try/failing"
 *
 * This is the STATIC position in the definition tree, without execution context.
 * For dynamic execution tracking with visit counts, see WorkflowStep (to be implemented).
 *
 * @property path The path string representing the position in JSON Pointer format.
 */
@Serializable(with = NodePositionSerializer::class)
data class NodePosition(private val path: String) {

    init {
        require(path.startsWith("/")) {
            "NodePosition path must start with '/', got: '$path'"
        }
    }

    /**
     * Check if this is the root position.
     */
    val isRoot: Boolean by lazy { path == root.path }

    /**
     * Get the node name (last segment of path).
     * Example: "/do/taskA" → "taskA", "/" → ""
     */
    val nodeName: String by lazy { path.substringAfterLast('/') }

    /**
     * Adds a name component to the JSON pointer path.
     *
     * This function ensures that the name does not contain a slash ('/'),
     * is not an integer, and is not one of the reserved tokens defined in Token.
     *
     * @param name The name component to add to the path.
     * @return A new NodePosition with the added name component.
     * @throws IllegalArgumentException if the name contains a slash, is an integer, or is a reserved token.
     */
    fun addName(name: String): NodePosition {
        require(!name.contains("/")) { "Task name $name must not contain '/'" }
        require(name.toIntOrNull() == null) { "Task name $name must not be an integer" }
        Token.entries.map { it.token }.let {
            require(!it.contains(name)) { "Task name $name must not be one of ${it.joinToString()}" }
        }
        return NodePosition(if (isRoot) "/$name" else "$path/$name")
    }

    /**
     * Adds a token to the path.
     * Example: "/do" + Token.TRY → "/do/try"
     *
     * @param token The token to add
     * @return A new NodePosition with the added token
     */
    fun addToken(token: Token): NodePosition =
        NodePosition(if (isRoot) "/${token.token}" else "$path/${token.token}")

    /**
     * Gets the string representation of the JSON pointer.
     *
     * @return The JSON pointer string (e.g., "/do/taskA", or "/" for root)
     */
    override fun toString(): String = path

    companion object {
        /**
         * Root position ("/").
         */
        val root = NodePosition("/")

        /**
         * Common position: /do
         */
        val doRoot = NodePosition(root.path + Token.DO.token)
    }
}

/**
 * Custom kotlinx.serialization serializer for [NodePosition].
 * Serializes to/from the string representation of the JSON Pointer.
 */
internal object NodePositionSerializer : KSerializer<NodePosition> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NodePosition", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NodePosition) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): NodePosition {
        return NodePosition(decoder.decodeString())
    }
}
