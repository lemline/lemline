package com.lemline.swruntime.tasks.nodes

import com.lemline.swruntime.tasks.TaskPosition
import com.lemline.swruntime.tasks.TaskToken.*
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.TaskContext
import io.serverlessworkflow.impl.WorkflowContext

data class TaskNode(
    val position: TaskPosition,
    val task: TaskBase,
    val name: String,
    val parent: TaskNode?,
) {

    /**
     * The list of task nodes depending on this one
     */
    internal val children: List<TaskNode>? = when (task) {
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
     * Returns the next task node in the current sequence.
     *
     * @return The next `TaskNode` if it exists, or `null` if there is no next node.
     */
    fun nextTaskNode(taskContext: TaskContext, workflowContext: WorkflowContext): TaskNode? {
        TODO()
    }

    /**
     * Returns the next task node, while exiting the current sequence.
     *
     * @return The next `TaskNode` if it exists, or `null` if there is no next node.
     */
    fun exitTaskNode(taskContext: TaskContext, workflowContext: WorkflowContext): TaskNode? {
        TODO()
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
        val nodeCounter = mutableMapOf<String, Int>()

        fun generateNodeId(taskType: String): String {
            val count = nodeCounter.getOrDefault(taskType, 0) + 1
            nodeCounter[taskType] = count
            return "${taskType}_$count"
        }

        fun processNode(node: TaskNode, parentId: String? = null) {
            val taskType = node.task.javaClass.simpleName
            val nodeId = node.position.jsonPointer()

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

private fun DoTask.parseChildren(
    position: TaskPosition,
    parent: TaskNode?,
): List<TaskNode> = `do`.mapIndexed { index, taskItem ->
    val child = taskItem.toTask()
    val childPosition = position.addIndex(index).addName(taskItem.name).let {
        if (child is DoTask) it.addToken(DO) else it
    }

    TaskNode(
        childPosition,
        child,
        taskItem.name,
        parent,
    )
}

private fun ForTask.parseChildren(
    position: TaskPosition,
    parent: TaskNode?
): List<TaskNode> = listOf(
    TaskNode(
        position.addToken(DO),
        DoTask(`do`),
        "$DO",
        parent,
    )
)

private fun TryTask.parseChildren(
    position: TaskPosition,
    parent: TaskNode?,
): List<TaskNode> = mutableListOf(
    TaskNode(
        position.addToken(TRY),
        DoTask(`try`),
        "$TRY",
        parent,
    ),
).also { list ->
    `catch`.`do`?.let {
        list.add(
            TaskNode(
                position.addToken(CATCH).addToken(DO),
                DoTask(it),
                "$CATCH.$DO",
                parent,
            )
        )
    }
}


private fun ForkTask.parseChildren(
    position: TaskPosition,
    parent: TaskNode?,
): List<TaskNode>? = fork.branches?.mapIndexed { index, taskItem ->
    TaskNode(
        position.addToken(FORK).addToken(BRANCHES).addIndex(index).addName(taskItem.name),
        taskItem.toTask(),
        taskItem.name,
        parent,
    )
}

private fun ListenTask.parseChildren(
    position: TaskPosition,
    parent: TaskNode?,
): List<TaskNode>? = foreach?.`do`?.let {
    listOf(
        TaskNode(
            position.addToken(FOREACH).addToken(DO),
            DoTask(it),
            "$FOREACH.$DO",
            parent,
        )
    )
}

private fun CallAsyncAPI.parseChildren(
    position: TaskPosition,
    parent: TaskNode?,
): List<TaskNode>? = with.subscription?.foreach?.`do`?.let {
    listOf(
        TaskNode(
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