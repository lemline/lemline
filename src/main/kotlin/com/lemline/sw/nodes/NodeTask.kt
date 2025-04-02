package com.lemline.sw.nodes

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.sw.nodes.Token.*
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.json.JsonUtils

/**
 * Represents a node in the tree defining a workflow.
 *
 * @property position The position of the task in the workflow.
 * @property task The task associated with this node.
 * @property name The name of the task.
 * @property parent The parent node of this task node, or null if it is a root node.
 */
data class NodeTask<T : TaskBase>(
    val position: NodePosition,
    val task: T,
    val name: String,
    val parent: NodeTask<*>? = null
) {
    val definition: JsonNode = JsonUtils.fromValue(task)
    val reference: String = position.jsonPointer.toString()

    /**
     * The list of task nodes depending on this one
     */
    internal val children: List<NodeTask<*>>? = when (task) {
        is RootTask -> task.parseChildren()
        is DoTask -> task.parseChildren(position, this)
        is ForTask -> task.parseChildren(position, this)
        is TryTask -> task.parseChildren(position, this)
        is ForkTask -> task.parseChildren(position, this)
        is ListenTask -> task.parseChildren(position, this)
        is CallAsyncAPI -> task.parseChildren(position, this)
        else -> null
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
        is SwitchTask -> false

        is CallAsyncAPI,
        is CallGRPC,
        is CallHTTP,
        is CallOpenAPI,
        is CallFunction,
        is EmitTask,
        is ListenTask,
        is RunTask,
        is WaitTask -> true

        else -> throw IllegalArgumentException("Unknown task type: ${task.javaClass.name}")
    }

    /**
     * Generates a Mermaid graph representation of the task hierarchy.
     * The graph shows the relationships between tasks and their children.
     * Each node shows the task type and position.
     *
     * @return A string containing the Mermaid graph definition
     */
    fun toMermaidGraph(): String {
        val nodes = mutableSetOf<String>()
        val edges = mutableSetOf<String>()

        fun processNode(node: NodeTask<*>, parentId: com.lemline.sw.nodes.JsonPointer? = null) {
            val taskType = node.task.javaClass.simpleName
            val nodeId = node.position.jsonPointer

            // Add node with task type and position
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

private fun RootTask.parseChildren(): List<NodeTask<*>> = listOf(
    NodeTask(
        NodePosition.root.addToken(DO),
        DoTask(`do`),
        "$DO",
        null,
    )
)

private fun DoTask.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?,
): List<NodeTask<*>> = `do`.mapIndexed { index, taskItem ->
    val child = taskItem.toTask()
    val childPosition = position.addIndex(index).addName(taskItem.name).let {
        if (child is DoTask) it.addToken(DO) else it
    }

    NodeTask(
        childPosition,
        child,
        taskItem.name,
        parent,
    )
}

private fun ForTask.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?
): List<NodeTask<*>> = listOf(
    NodeTask(
        position.addToken(DO),
        DoTask(`do`),
        "$DO",
        parent,
    )
)

private fun TryTask.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?,
): List<NodeTask<*>> = mutableListOf(
    NodeTask(
        position.addToken(TRY),
        DoTask(`try`),
        "$TRY",
        parent,
    ),
).also { list ->
    `catch`.`do`?.let {
        list.add(
            NodeTask(
                position.addToken(CATCH).addToken(DO),
                DoTask(it),
                "$CATCH.$DO",
                parent,
            )
        )
    }
}


private fun ForkTask.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?,
): List<NodeTask<*>>? = fork.branches?.mapIndexed { index, taskItem ->
    NodeTask(
        position.addToken(FORK).addToken(BRANCHES).addIndex(index).addName(taskItem.name),
        taskItem.toTask(),
        taskItem.name,
        parent,
    )
}

private fun ListenTask.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?,
): List<NodeTask<*>>? = foreach?.`do`?.let {
    listOf(
        NodeTask(
            position.addToken(FOREACH).addToken(DO),
            DoTask(it),
            "$FOREACH.$DO",
            parent,
        )
    )
}

private fun CallAsyncAPI.parseChildren(
    position: NodePosition,
    parent: NodeTask<*>?,
): List<NodeTask<*>>? = with.subscription?.foreach?.`do`?.let {
    listOf(
        NodeTask(
            position.addToken(WITH).addToken(SUBSCRIPTION).addToken(FOREACH).addToken(DO),
            DoTask(it),
            "$WITH.$SUBSCRIPTION.$FOREACH.$DO",
            parent,
        )
    )
}

internal fun TaskItem.toTask(): TaskBase = when (val task = task.get()) {
    is TaskBase -> task
    is CallTask -> task.get() as TaskBase
    else -> throw IllegalArgumentException("Unsupported task type: ${task.javaClass.canonicalName}")
}