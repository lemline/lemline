// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.core.processors.CallHttpProcessor
import com.lemline.core.processors.DoProcessor
import com.lemline.core.processors.EmitProcessor
import com.lemline.core.processors.ForProcessor
import com.lemline.core.processors.ForkProcessor
import com.lemline.core.processors.ListenProcessor
import com.lemline.core.processors.NodeProcessor
import com.lemline.core.processors.RaiseProcessor
import com.lemline.core.processors.RootProcessor
import com.lemline.core.processors.RunScriptProcessor
import com.lemline.core.processors.RunShellProcessor
import com.lemline.core.processors.RunWorkflowProcessor
import com.lemline.core.processors.SetProcessor
import com.lemline.core.processors.SwitchProcessor
import com.lemline.core.processors.TryProcessor
import com.lemline.core.processors.WaitProcessor
import com.lemline.core.states.NodeState
import com.lemline.core.workflows.WorkflowParser
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.api.types.CallGRPC
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.CallOpenAPI
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunWorkflow
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SwitchTask
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.WaitTask
import kotlin.time.ExperimentalTime
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

    @Suppress("UNCHECKED_CAST")
    @ExperimentalTime
    val processor by lazy {
        when (task) {
            is RootTask -> RootProcessor(this as Node<RootTask>)
            is DoTask -> DoProcessor(this as Node<DoTask>)
            is ForTask -> ForProcessor(this as Node<ForTask>)
            is SetTask -> SetProcessor(this as Node<SetTask>)
            is SwitchTask -> SwitchProcessor(this as Node<SwitchTask>)
            is TryTask -> TryProcessor(this as Node<TryTask>)
            is RaiseTask -> RaiseProcessor(this as Node<RaiseTask>)
            is CallHTTP -> CallHttpProcessor(this as Node<CallHTTP>)
            is WaitTask -> WaitProcessor(this as Node<WaitTask>)
            is ForkTask -> ForkProcessor(this as Node<ForkTask>)
            is EmitTask -> EmitProcessor(this as Node<EmitTask>)
            is ListenTask -> ListenProcessor(this as Node<ListenTask>)
            is RunTask -> {
                val runTask = this as Node<RunTask>
                when (runTask.task.run.get()) {
                    is RunShell -> RunShellProcessor(runTask)
                    is RunScript -> RunScriptProcessor(runTask)
                    is RunWorkflow -> RunWorkflowProcessor(runTask)
                    else -> throw IllegalArgumentException(
                        "Unknown run task type: ${runTask.task.run.get()?.javaClass?.simpleName}"
                    )
                }
            }

            else -> throw IllegalArgumentException("Unknown task type: ${task::class.simpleName}")
        } as NodeProcessor<T, NodeState>
    }

    /**
     * The list of task nodes depending on this one.
     * Parsing is delegated to [WorkflowParser.parseChildren].
     */
    val children: List<Node<*>>? by lazy { WorkflowParser.parseChildren(this) }

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
    @Suppress("unused")
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
