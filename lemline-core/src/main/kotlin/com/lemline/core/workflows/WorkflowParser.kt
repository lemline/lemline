// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token.BRANCHES
import com.lemline.common.values.Token.CATCH
import com.lemline.common.values.Token.DO
import com.lemline.common.values.Token.FOREACH
import com.lemline.common.values.Token.FORK
import com.lemline.common.values.Token.SUBSCRIPTION
import com.lemline.common.values.Token.TRY
import com.lemline.common.values.Token.WITH
import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.workflows.WorkflowParser.parseChildren
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow

/**
 * Parses a Workflow DSL definition into a tree of Node objects.
 *
 * This parser is responsible for:
 * - Creating the root node from a Workflow
 * - Parsing child nodes for each task type
 * - Building a flattened map of all nodes indexed by position
 *
 * The node tree is built lazily - each Node's `children` property
 * calls [parseChildren] on first access.
 *
 * ## Structure
 *
 * Each task type that has children has:
 * 1. A parsing function (e.g., [parseDoChildren]) that creates child nodes
 * 2. A typed accessor extension (e.g., [doBlock]) for type-safe access
 *
 * These are grouped together in the file for easy consistency verification.
 */
object WorkflowParser {

    /**
     * Parses a Workflow into a root Node and a map of all nodes.
     *
     * @param workflow The workflow to parse
     * @return A pair of (rootNode, nodesMap) where nodesMap contains all nodes indexed by position
     */
    fun parse(workflow: Workflow): Pair<Node<RootTask>, Map<NodePosition, Node<*>>> {
        val rootNode = createRootNode(workflow)
        val nodesMap = buildNodesMap(rootNode)
        return rootNode to nodesMap
    }

    /**
     * Creates the root node from a Workflow.
     *
     * The root node wraps the workflow's document metadata, do block, and use declarations
     * into a RootTask that serves as the entry point for execution.
     *
     * @param workflow The workflow to create the root node from
     * @return The root node representing the workflow entry point
     */
    fun createRootNode(workflow: Workflow): Node<RootTask> = Node(
        position = NodePosition.root,
        task = RootTask(workflow.document, workflow.`do`, workflow.use).also {
            it.output = workflow.output
            it.input = workflow.input
        },
        name = NodePosition.root.toString(),
        parent = null,
    )

    /**
     * Builds a flattened map of all nodes in the tree starting from the root.
     *
     * Recursively traverses the node tree (triggering lazy child creation)
     * and collects all nodes into a map indexed by their position.
     *
     * @param rootNode The root node to start traversal from
     * @return A map of all nodes indexed by their NodePosition
     */
    fun buildNodesMap(rootNode: Node<RootTask>): Map<NodePosition, Node<*>> {
        val map = mutableMapOf<NodePosition, Node<*>>()

        fun addNodeAndChildren(node: Node<*>) {
            // Add the current node to the map
            map[node.position] = node

            // Recursively add all children (triggers lazy child creation)
            node.children?.forEach { child ->
                addNodeAndChildren(child)
            }
        }

        // Start the recursive traversal from the root
        addNodeAndChildren(rootNode)

        return map
    }

    /**
     * Parses children for a given node based on its task type.
     *
     * Each task type has specific rules for what constitutes its children.
     * See individual parse functions for details.
     *
     * @param node The node to parse children for
     * @return List of child nodes, or null if the task type has no children
     */
    fun parseChildren(node: Node<*>): List<Node<*>>? {
        return when (val task = node.task) {
            is RootTask -> parseRootChildren(task, node)
            is DoTask -> parseDoChildren(node)
            is ForTask -> parseForChildren(node)
            is TryTask -> parseTryChildren(node)
            is ForkTask -> parseForkChildren(node)
            is ListenTask -> parseListenChildren(node)
            is ForeachTask -> parseForeachChildren(node)
            is CallAsyncAPI -> parseCallAsyncAPIChildren(node)
            else -> null
        }
    }
}

/**
 * Converts a TaskItem to its underlying TaskBase.
 */
private fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
    is TaskBase -> task
    is CallTask -> task.get() as TaskBase
    else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
}

// ============================================================
// RootTask Children
// ============================================================

/**
 * RootTask has a single DoTask child containing the workflow's do block.
 * No accessor needed - navigation starts at NodePosition.doRoot.
 */
private fun parseRootChildren(task: RootTask, parent: Node<*>): List<Node<*>> = listOf(
    Node(
        position = NodePosition.root.addToken(DO),
        task = DoTask(task.`do`),
        name = "$DO",
        parent = parent,
    ),
)

// ============================================================
// DoTask Children
// ============================================================

/**
 * DoTask children are the sequential task items in the do block.
 * Position format per RFC 6901: /do/{index}/{taskName}
 */
private fun parseDoChildren(node: Node<*>): List<Node<*>> {
    val task = node.task as DoTask
    return task.`do`.mapIndexed { index, taskItem ->
        val child = taskItem.toTask()
        val childPosition = node.position
            .addIndex(index)
            .addName(taskItem.name)
            .let { if (child is DoTask) it.addToken(DO) else it }

        Node(
            position = childPosition,
            task = child,
            name = taskItem.name,
            parent = node,
        )
    }
}

/**
 * DoTask children: sequential task nodes.
 */
val Node<DoTask>.doBlock: List<Node<*>>
    get() = children!!

// ============================================================
// ForTask Children
// ============================================================

/**
 * ForTask has a single DoTask child for the loop body.
 */
private fun parseForChildren(node: Node<*>): List<Node<*>> {
    val task = node.task as ForTask
    return listOf(
        Node(
            position = node.position.addToken(DO),
            task = DoTask(task.`do`),
            name = "$DO",
            parent = node,
        ),
    )
}

/**
 * ForTask children: single DoTask for loop body.
 */
val Node<ForTask>.forBlock: Node<DoTask>
    @Suppress("UNCHECKED_CAST")
    get() = children!![0] as Node<DoTask>

// ============================================================
// TryTask Children
// ============================================================

/**
 * TryTask has try block (always present) and catch block (optional).
 * Structure: [0] = try DoTask, [1] = catch DoTask (if catch.do exists)
 */
private fun parseTryChildren(node: Node<*>): List<Node<*>> {
    val task = node.task as TryTask
    return buildList {
        add(
            Node(
                position = node.position.addToken(TRY),
                task = DoTask(task.`try`),
                name = "$TRY",
                parent = node,
            )
        )
        task.`catch`.`do`?.let {
            add(
                Node(
                    position = node.position.addToken(CATCH),
                    task = DoTask(it),
                    name = "$CATCH",
                    parent = node,
                ),
            )
        }
    }
}

/**
 * TryTask children: try block (always present).
 */
val Node<TryTask>.tryBlock: Node<DoTask>
    @Suppress("UNCHECKED_CAST")
    get() = children!![0] as Node<DoTask>

/**
 * TryTask children: catch block (optional, only if catch.do is defined).
 */
val Node<TryTask>.catchBlock: Node<DoTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children!!.getOrNull(1) as? Node<DoTask>

// ============================================================
// ForkTask Children
// ============================================================

/**
 * ForkTask children are the parallel branch nodes.
 * Position format per RFC 6901: /fork/branches/{index}/{branchName}
 */
private fun parseForkChildren(node: Node<*>): List<Node<*>>? {
    val task = node.task as ForkTask
    return task.fork.branches?.mapIndexed { index, taskItem ->
        Node(
            position = node.position.addToken(FORK).addToken(BRANCHES).addIndex(index).addName(taskItem.name),
            task = taskItem.toTask(),
            name = taskItem.name,
            parent = node,
        )
    }
}

/**
 * ForkTask children: parallel branch nodes.
 */
val Node<ForkTask>.branches: List<Node<*>>
    get() = children ?: emptyList()

// ============================================================
// ListenTask Children
// ============================================================

private fun parseListenChildren(node: Node<*>): List<Node<*>>? {
    val task = node.task as ListenTask
    val foreach = task.foreach
    val doTasks = foreach?.`do` ?: return null

    return listOf(
        Node(
            position = node.position.addToken(FOREACH),
            task = ForeachTask(
                itemVar = foreach.item ?: "item",
                indexVar = foreach.at ?: "index",
                doTask = DoTask(doTasks),
                foreachOutput = foreach.output,
                foreachExport = foreach.export,
            ),
            name = "$FOREACH",
            parent = node,
        ),
    )
}

@get:JvmName("getForeachBlockForListenTask")
val Node<ListenTask>.foreachBlock: Node<ForeachTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children?.firstOrNull() as? Node<ForeachTask>

// ============================================================
// ForeachTask Children
// ============================================================

private fun parseForeachChildren(node: Node<*>): List<Node<*>> {
    val task = node.task as ForeachTask
    return listOf(
        Node(
            position = node.position.addToken(DO),
            task = task.doTask,
            name = "$DO",
            parent = node,
        ),
    )
}

val Node<ForeachTask>.foreachDoBlock: Node<DoTask>
    @Suppress("UNCHECKED_CAST")
    get() = children!!.first() as Node<DoTask>

// ============================================================
// CallAsyncAPI Children
// ============================================================

/**
 * CallAsyncAPI has optional subscription foreach DoTask child.
 */
private fun parseCallAsyncAPIChildren(node: Node<*>): List<Node<*>>? {
    val task = node.task as CallAsyncAPI
    return task.with.subscription?.foreach?.`do`?.let {
        listOf(
            Node(
                position = node.position.addToken(WITH).addToken(SUBSCRIPTION).addToken(FOREACH).addToken(DO),
                task = DoTask(it),
                name = "$WITH.$SUBSCRIPTION.$FOREACH.$DO",
                parent = node,
            ),
        )
    }
}

/**
 * CallAsyncAPI children: optional subscription foreach DoTask.
 */
@get:JvmName("getForeachBlockForCallAsyncAPI")
val Node<CallAsyncAPI>.foreachBlock: Node<DoTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children?.firstOrNull() as? Node<DoTask>
