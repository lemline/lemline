package com.lemline.sw.nodes

/**
 * Represents a task position in the workflow.
 *
 * This class is used to represent the position of a task in the workflow.
 *
 * @property path The list of path components.
 */
data class NodePosition(
    private val path: List<String> = listOf(),
) {
    /**
     * Json pointer representation. (e.g., "/do/0/do")
     */
    val jsonPointer = when (path.isEmpty()) {
        true -> JsonPointer.root
        else -> JsonPointer("/${path.joinToString("/")}")
    }

    /**
     * Gets the string representation of the JSON pointer.
     *
     * @return The JSON pointer string (e.g., "/do/0/do")
     */
    override fun toString() = jsonPointer.toString()

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
    fun addToken(token: com.lemline.sw.nodes.Token): NodePosition =
        NodePosition(path + token.token)

    /**
     * Adds an childIndex to the path.
     *
     * @param index The numeric childIndex to add
     * @return A new Position with the added childIndex
     */
    fun addIndex(index: Int): NodePosition =
        NodePosition(path + index.toString())

    /**
     * Gets the parent path by removing the last component.
     *
     * @return A new Position with the parent path, or null if this is the root
     */
    val parent: NodePosition?
        get() = if (path.isEmpty()) null else NodePosition(path.dropLast(1))

    /**
     * Checks if this path is a child of the given parent path.
     *
     * @param parent The potential parent path
     * @return true if this path is a child of the given parent path
     */
    fun isChildOf(parent: NodePosition): Boolean =
        path.size > parent.path.size && path.take(parent.path.size) == parent.path

    companion object {
        val root = com.lemline.sw.nodes.JsonPointer.root.toPosition()
    }
}