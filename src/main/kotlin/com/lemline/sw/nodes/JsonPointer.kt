package com.lemline.sw.nodes

import kotlinx.serialization.Serializable

/**
 * Represents a json pointer targeting a node in the workflow,
 * e.g. "/do/1/do"
 *
 * @property path The path string representing the task pointer.
 */
@Serializable
@JvmInline
value class JsonPointer(private val path: String) {

    /**
     * Returns the path string.
     *
     * @return The path string.
     */
    override fun toString(): String = path

    /**
     * Converts to a {@link Position}.
     *
     * @return A Position object representing the task pointer.
     */
    fun toPosition() = com.lemline.sw.nodes.NodePosition(
        path.trim()
            .split("/")
            .filter { it.isNotEmpty() }
    )

    companion object {
        val root = com.lemline.sw.nodes.JsonPointer("")
    }
}