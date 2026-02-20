// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.states.NodeState
import io.serverlessworkflow.api.types.CallFunction
import io.serverlessworkflow.api.types.CallHTTP
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

/**
 * Factory for creating NodeProcessor instances based on task type.
 *
 * This centralizes the mapping between task types and their corresponding processors,
 * keeping the Node class focused on representing the workflow tree structure.
 */
object NodeProcessors {

    /**
     * Creates the appropriate processor for a given node based on its task type.
     *
     * @param node The node to create a processor for
     * @return The processor instance for the node's task type
     * @throws IllegalArgumentException if the task type is unknown
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : TaskBase> createProcessor(node: Node<T>): NodeProcessor<T, NodeState> = when (node.task) {
        is RootTask -> RootProcessor(node as Node<RootTask>)
        is DoTask -> DoProcessor(node as Node<DoTask>)
        is ForTask -> ForProcessor(node as Node<ForTask>)
        is ForeachTask -> ForeachProcessor(node as Node<ForeachTask>)
        is SetTask -> SetProcessor(node as Node<SetTask>)
        is SwitchTask -> SwitchProcessor(node as Node<SwitchTask>)
        is TryTask -> TryProcessor(node as Node<TryTask>)
        is RaiseTask -> RaiseProcessor(node as Node<RaiseTask>)
        is CallHTTP -> CallHttpProcessor(node as Node<CallHTTP>)
        is CallFunction -> CallFunctionProcessor(node as Node<CallFunction>)
        is WaitTask -> WaitProcessor(node as Node<WaitTask>)
        is ForkTask -> ForkProcessor(node as Node<ForkTask>)
        is EmitTask -> EmitProcessor(node as Node<EmitTask>)
        is ListenTask -> ListenProcessor(node as Node<ListenTask>)
        is RunTask -> createRunProcessor(node as Node<RunTask>)
        else -> throw IllegalArgumentException("Unknown task type: ${node.task::class.simpleName}")
    } as NodeProcessor<T, NodeState>

    /**
     * Creates the appropriate processor for a RunTask based on its run type.
     */
    private fun createRunProcessor(node: Node<RunTask>): NodeProcessor<*, *> = when (node.task.run.get()) {
        is RunShell -> RunShellProcessor(node)
        is RunScript -> RunScriptProcessor(node)
        is RunWorkflow -> RunWorkflowProcessor(node)
        else -> throw IllegalArgumentException(
            "Unknown run task type: ${node.task.run.get()?.javaClass?.simpleName}"
        )
    }
}
