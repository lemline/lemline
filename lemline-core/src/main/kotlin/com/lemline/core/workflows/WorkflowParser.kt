// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token
import com.lemline.common.values.Token.CATCH
import com.lemline.common.values.Token.DO
import com.lemline.common.values.Token.FOREACH
import com.lemline.common.values.Token.FORK
import com.lemline.common.values.Token.SUBSCRIPTION
import com.lemline.common.values.Token.TRY
import com.lemline.common.values.Token.WITH
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
     * Each task type has specific rules for what constitutes its children:
     * - RootTask: Has a single DoTask child containing the workflow's do block
     * - DoTask: Children are the task items in the do block
     * - ForTask: Has a single DoTask child for the loop body
     * - TryTask: Has try and optionally catch DoTask children
     * - ForkTask: Children are the parallel branches
     * - ListenTask: Has optional foreach DoTask child
     * - CallAsyncAPI: Has optional subscription foreach DoTask child
     *
     * @param node The node to parse children for
     * @return List of child nodes, or null if the task type has no children
     */
    fun parseChildren(node: Node<*>): List<Node<*>>? {
        val position = node.position

        return when (val task = node.task) {
            is RootTask -> parseRootChildren(task, node)
            is DoTask -> parseDoChildren(task, position, node)
            is ForTask -> parseForChildren(task, position, node)
            is TryTask -> parseTryChildren(task, position, node)
            is ForkTask -> parseForkChildren(task, position, node)
            is ListenTask -> parseListenChildren(task, position, node)
            is CallAsyncAPI -> parseCallAsyncAPIChildren(task, position, node)
            else -> null
        }
    }

    private fun parseRootChildren(task: RootTask, parent: Node<*>): List<Node<*>> = listOf(
        Node(
            position = NodePosition.root.addToken(DO),
            task = DoTask(task.`do`),
            name = "$DO",
            parent = parent,
        ),
    )

    private fun parseDoChildren(task: DoTask, position: NodePosition, parent: Node<*>): List<Node<*>> =
        task.`do`.map { taskItem ->
            val child = taskItem.toTask()
            val childPosition = position.addName(taskItem.name).let {
                if (child is DoTask) it.addToken(DO) else it
            }

            Node(
                position = childPosition,
                task = child,
                name = taskItem.name,
                parent = parent,
            )
        }

    private fun parseForChildren(task: ForTask, position: NodePosition, parent: Node<*>): List<Node<*>> = listOf(
        Node(
            position = position.addToken(DO),
            task = DoTask(task.`do`),
            name = "$DO",
            parent = parent,
        ),
    )

    private fun parseTryChildren(task: TryTask, position: NodePosition, parent: Node<*>): List<Node<*>> = buildList {
        add(
            Node(
                position = position.addToken(TRY),
                task = DoTask(task.`try`),
                name = "$TRY",
                parent = parent,
            )
        )
        task.`catch`.`do`?.let {
            add(
                Node(
                    position = position.addToken(CATCH),
                    task = DoTask(it),
                    name = "$CATCH",
                    parent = parent,
                ),
            )
        }
    }

    private fun parseForkChildren(task: ForkTask, position: NodePosition, parent: Node<*>): List<Node<*>>? =
        task.fork.branches?.map { taskItem ->
            Node(
                position = position.addToken(FORK).addName(taskItem.name),
                task = taskItem.toTask(),
                name = taskItem.name,
                parent = parent,
            )
        }

    private fun parseListenChildren(task: ListenTask, position: NodePosition, parent: Node<*>): List<Node<*>>? =
        task.foreach?.`do`?.let {
            listOf(
                Node(
                    position = position.addToken(Token.FOR),
                    task = DoTask(it),
                    name = "${Token.FOR}",
                    parent = parent,
                ),
            )
        }

    private fun parseCallAsyncAPIChildren(task: CallAsyncAPI, position: NodePosition, parent: Node<*>): List<Node<*>>? =
        task.with.subscription?.foreach?.`do`?.let {
            listOf(
                Node(
                    position = position.addToken(WITH).addToken(SUBSCRIPTION).addToken(FOREACH).addToken(DO),
                    task = DoTask(it),
                    name = "$WITH.$SUBSCRIPTION.$FOREACH.$DO",
                    parent = parent,
                ),
            )
        }

    /**
     * Converts a TaskItem to its underlying TaskBase.
     */
    private fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
        is TaskBase -> task
        is CallTask -> task.get() as TaskBase
        else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
    }
}

// ============================================================
// Typed Children Accessors
// ============================================================
// These accessors mirror the parsing logic in WorkflowParser.
// When modifying parse*Children methods, update corresponding accessor.

/**
 * DoTask children: sequential task nodes.
 * @see WorkflowParser.parseDoChildren
 */
val Node<DoTask>.doBlock: List<Node<*>>
    get() = children ?: emptyList()

/**
 * ForTask children: single DoTask for loop body.
 * @see WorkflowParser.parseForChildren
 */
val Node<ForTask>.forBlock: Node<DoTask>
    @Suppress("UNCHECKED_CAST")
    get() = children!![0] as Node<DoTask>

/**
 * TryTask children: try block (always present).
 * Structure: [0] = try DoTask, [1] = catch DoTask (if catch.do exists)
 * @see WorkflowParser.parseTryChildren
 */
val Node<TryTask>.tryBlock: Node<DoTask>
    @Suppress("UNCHECKED_CAST")
    get() = children!![0] as Node<DoTask>

/**
 * TryTask children: catch block (optional, only if catch.do is defined).
 * @see WorkflowParser.parseTryChildren
 */
val Node<TryTask>.catchBlock: Node<DoTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children?.getOrNull(1) as? Node<DoTask>

/**
 * ForkTask children: parallel branch nodes.
 * @see WorkflowParser.parseForkChildren
 */
val Node<ForkTask>.branches: List<Node<*>>
    get() = children ?: emptyList()

/**
 * ListenTask children: optional foreach DoTask.
 * @see WorkflowParser.parseListenChildren
 */
val Node<ListenTask>.foreachBlock: Node<DoTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children?.firstOrNull() as? Node<DoTask>

/**
 * CallAsyncAPI children: optional subscription foreach DoTask.
 * @see WorkflowParser.parseCallAsyncAPIChildren
 */
val Node<CallAsyncAPI>.subscriptionForeach: Node<DoTask>?
    @Suppress("UNCHECKED_CAST")
    get() = children?.firstOrNull() as? Node<DoTask>
