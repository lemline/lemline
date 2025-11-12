// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

/**
 * Utility to navigate the workflow node tree and find nodes by their position reference.
 *
 * The workflow is represented as a tree of Node objects. Each node has a unique position
 * reference string (e.g., "/do/0/taskName"). This utility helps find the Node object
 * that corresponds to a given position string.
 */
object NodeNavigator {

    /**
     * Find a node in the tree by its position reference.
     *
     * Performs a depth-first search of the node tree to find the node whose reference
     * matches the given position string.
     *
     * @param position The target position to find
     * @param rootNode The workflow root node to start searching from
     * @return The node at that position
     * @throws IllegalStateException if the node is not found in the tree
     */
    fun findNodeAtPosition(position: NodePosition, node: Node<*>): Node<*> {
        val targetReference = position.positionPointer.toString()

        return findNodeByReference(targetReference, node)
    }

    /**
     * Finds a node in the tree with the specified reference starting from the root node.
     *
     * This method recursively searches through the entire tree structure of nodes and
     * returns the node that matches the given reference. If the node with the specified
     * reference is not found, an exception is thrown.
     *
     * @param reference The reference string used to identify the desired node.
     * @param node A node of the graph.
     * @return The node that matches the specified reference.
     * @throws IllegalStateException if the node with the given reference is not found.
     */
    fun findNodeByReference(reference: String, node: Node<*>): Node<*> {
        // find root
        var root = node
        while (root.parent != null) root = root.parent

        // search recursively from root
        return findNodeByReferenceFromRoot(reference, root)
    }

    private fun findNodeByReferenceFromRoot(reference: String, root: Node<*>): Node<*> {
        // search recursively from root
        if (root.reference == reference) return root

        root.children?.forEach { child ->
            try {
                return findNodeByReferenceFromRoot(reference, child)
            } catch (_: IllegalStateException) {
                // Do nothing - continue searching for other children
            }
        }

        throw IllegalStateException("Node not found: reference=$reference")
    }
}
