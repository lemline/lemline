// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

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
    fun toPosition() = NodePosition(
        path.trim()
            .split("/")
            .filter { it.isNotEmpty() }
    )

    companion object {
        val root = JsonPointer("")
    }
}
