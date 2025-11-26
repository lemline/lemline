// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token.BRANCHES
import com.lemline.common.values.Token.CATCH
import com.lemline.common.values.Token.DO
import com.lemline.common.values.Token.FOREACH
import com.lemline.common.values.Token.FORK
import com.lemline.common.values.Token.SUBSCRIPTION
import com.lemline.common.values.Token.TRY
import com.lemline.common.values.Token.WITH
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.api.types.CallGRPC
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.CallOpenAPI
import io.serverlessworkflow.api.types.CallTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TaskItem
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.WaitTask
import kotlinx.serialization.json.JsonObject

/**
 * Represents a node in the tree defining a workflow.
 *
 * @property position The initialPosition of the task in the workflow.
 * @property task The task associated with this node.
 * @property name The name of the task.
 * @property parent The parent node of this task node, or null if it is a root node.
 */
data class Node<T : TaskBase>(val position: NodePosition, val task: T, val name: String, val parent: Node<*>? = null) {
    val definition: JsonObject by lazy { LemlineJson.encodeToElement(task) }

    /**
     * The list of task nodes depending on this one
     */
    val children: List<Node<*>>? by lazy {
        when (task) {
            is RootTask -> task.parseChildren(this)
            is DoTask -> task.parseChildren(position, this)
            is ForTask -> task.parseChildren(position, this)
            is TryTask -> task.parseChildren(position, this)
            is ForkTask -> task.parseChildren(position, this)
            is ListenTask -> task.parseChildren(position, this)
            is CallAsyncAPI -> task.parseChildren(position, this)
            else -> null
        }
    }

    /**
     * Determines if the task is an activity
     * (a task that actually does something, not only control flow).
     *
     * @return `true` if the task is an activity, `false` otherwise
     */
    fun isActivity(): Boolean = when (task) {
        is RootTask,
        is DoTask,
        is ForTask,
        is TryTask,
        is ForkTask,
        is RaiseTask,
        is SetTask,
        is SwitchTask,
            -> false

        is CallAsyncAPI,
        is CallGRPC,
        is CallHTTP,
        is CallOpenAPI,
        is CallFunction,
        is EmitTask,
        is ListenTask,
        is RunTask,
        is WaitTask,
            -> true

        else -> throw IllegalArgumentException("Unknown task type: ${task.javaClass.name}")
    }

    /**
     * Generates a Mermaid graph representation of the task hierarchy.
     * The graph shows the relationships between tasks and their children.
     * Each node shows the task type and initialPosition.
     *
     * @return A string containing the Mermaid graph definition
     */
    fun toMermaidGraph(): String {
        val nodes = mutableSetOf<String>()
        val edges = mutableSetOf<String>()

        fun processNode(node: Node<*>, parentId: NodePosition? = null) {
            val taskType = node.task.javaClass.simpleName
            val nodeId = node.position

            // Add node with task type and initialPosition
            val nodeLabel = "\"${node.name}\n($taskType)\""
            val nodeDesc = when {
                taskType == SwitchTask::class.simpleName -> "{$nodeLabel}"
                node.isActivity() -> "[$nodeLabel]"
                else -> "($nodeLabel)"
            }
            nodes.add("$nodeId$nodeDesc")

            // Add edge from parent if exists
            if (parentId != null) {
                edges.add("$parentId --> $nodeId")
            }

            // Process children
            node.children?.forEach { childNode ->
                processNode(childNode, nodeId)
            }
        }

        // Start processing from this node
        processNode(this)

        // Build the Mermaid graph
        return buildString {
            appendLine("graph TD")
            appendLine("    %% Nodes")
            nodes.forEach { appendLine("    $it") }
            appendLine("    %% Edges")
            edges.forEach { appendLine("    $it") }
        }
    }
}

private fun RootTask.parseChildren(parent: Node<*>?): List<Node<*>> = listOf(
    Node(
        position = NodePosition.root.addToken(DO),
        task = DoTask(`do`),
        name = "$DO",
        parent = parent,
    ),
)

private fun DoTask.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>> =
    `do`.map { taskItem ->
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

private fun ForTask.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>> = listOf(
    Node(
        position = position.addToken(DO),
        task = DoTask(`do`),
        name = "$DO",
        parent = parent,
    ),
)

private fun TryTask.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>> = buildList {
    add(
        Node(
            position = position.addToken(TRY),
            task = DoTask(`try`),
            name = "$TRY",
            parent = parent,
        )
    )
    `catch`.`do`?.let {
        add(
            Node(
                position = position.addToken(CATCH).addToken(DO),
                task = DoTask(it),
                name = "$CATCH.$DO",
                parent = parent,
            ),
        )
    }
}

private fun ForkTask.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>>? =
    fork.branches?.map { taskItem ->
        Node(
            position = position.addToken(FORK).addToken(BRANCHES).addName(taskItem.name),
            task = taskItem.toTask(),
            name = taskItem.name,
            parent = parent,
        )
    }

private fun ListenTask.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>>? = foreach?.`do`?.let {
    listOf(
        Node(
            position = position.addToken(FOREACH).addToken(DO),
            task = DoTask(it),
            name = "$FOREACH.$DO",
            parent = parent,
        ),
    )
}

private fun CallAsyncAPI.parseChildren(position: NodePosition, parent: Node<*>?): List<Node<*>>? =
    with.subscription?.foreach?.`do`?.let {
        listOf(
            Node(
                position = position.addToken(WITH).addToken(SUBSCRIPTION).addToken(FOREACH).addToken(DO),
                task = DoTask(it),
                name = "$WITH.$SUBSCRIPTION.$FOREACH.$DO",
                parent = parent,
            ),
        )
    }

internal fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
    is TaskBase -> task
    is CallTask -> task.get() as TaskBase
    else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
}
