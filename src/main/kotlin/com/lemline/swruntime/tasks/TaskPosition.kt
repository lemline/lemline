package com.lemline.swruntime.tasks

/**
 * A WorkflowPosition implementation that represents a JSON pointer path.
 * This class allows for building and manipulating JSON pointer paths in a type-safe way.
 */
data class TaskPosition(
    private val path: List<String> = listOf()
) {

    companion object {

        /**
         * Creates a new Position from a string path.
         * The path should be a valid JSON pointer string (e.g., "/do/0/do").
         *
         * @param path The JSON pointer string
         */
        fun fromString(path: String): TaskPosition =
            path.trim()
                .split("/")
                .filter { it.isNotEmpty() }
                .let { TaskPosition(it) }
    }

    /**
     * Adds a named property to the path.
     *
     * @param name The property name to add
     * @return A new Position with the added property
     */
    fun addName(name: String): TaskPosition =
        TaskPosition(path + name)

    /**
     * Adds a token property to the path.
     *
     * @param token The property name to add
     * @return A new Position with the added property
     */
    fun addToken(token: TaskToken): TaskPosition =
        TaskPosition(path + token.token)

    /**
     * Adds an index to the path.
     *
     * @param index The numeric index to add
     * @return A new Position with the added index
     */
    fun addIndex(index: Int): TaskPosition =
        TaskPosition(path + index.toString())

    /**
     * Gets the JSON pointer string representation.
     *
     * @return The JSON pointer string (e.g., "/do/0/do")
     */
    fun jsonPointer(): String = if (path.isEmpty()) "" else "/${path.joinToString("/")}"

    /**
     * Gets the string representation of the JSON pointer.
     *
     * @return The JSON pointer string (e.g., "/do/0/do")
     */
    override fun toString(): String = jsonPointer()

    /**
     * Gets the last component of the path.
     *
     * @return The last path component, or null if the path is empty
     */
    val last: String
        get() = path.last()

    /**
     * Checks if the path is empty.
     */
    val isEmpty: Boolean
        get() = path.isEmpty()

    /**
     * Gets the parent path by removing the last component.
     *
     * @return A new Position with the parent path, or null if this is the root
     */
    val parent: TaskPosition?
        get() = if (path.isEmpty()) null else TaskPosition(path.dropLast(1))

    /**
     * Gets the depth of the path (number of components).
     */
    val depth: Int
        get() = path.size

    /**
     * Gets the component at the specified index.
     *
     * @param index The index of the component to get
     * @return The component at the specified index, or null if the index is out of bounds
     */
    fun getComponent(index: Int): String? = path.getOrNull(index)

    /**
     * Checks if this path is a child of the given parent path.
     *
     * @param parent The potential parent path
     * @return true if this path is a child of the given parent path
     */
    fun isChildOf(parent: TaskPosition): Boolean =
        path.size > parent.path.size && path.take(parent.path.size) == parent.path

    /**
     * Gets the relative path from the given parent path.
     *
     * @param parent The parent path
     * @return The relative path, or null if this path is not a child of the given parent
     */
    fun getRelativePath(parent: TaskPosition): TaskPosition? =
        if (isChildOf(parent)) {
            TaskPosition(path.drop(parent.path.size))
        } else null

    /**
     * Returns the previous position in the workflow.
     *
     * @return The previous position, or null if this is the root position
     */
    fun back(): TaskPosition? = parent
}