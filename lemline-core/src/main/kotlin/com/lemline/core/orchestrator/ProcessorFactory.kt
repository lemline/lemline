// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import com.lemline.core.processors.CallHttpProcessor
import com.lemline.core.processors.DoProcessor
import com.lemline.core.processors.ForProcessor
import com.lemline.core.processors.ForkProcessor
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
import com.lemline.core.states.TaskState
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.ForTask
import io.serverlessworkflow.api.types.ForkTask
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

/**
 * Factory for creating appropriate NodeProcessor instances based on task type.
 *
 * This factory centralizes the creation logic for all task processors,
 * maintaining type safety through generic constraints.
 */
internal object ProcessorFactory {

    /**
     * Creates the appropriate NodeProcessor for the given node based on its task type.
     *
     * @param node The node for which to create a processor
     * @return A processor instance configured for the node's task type
     * @throws IllegalArgumentException if the task type is unknown
     */
    @Suppress("UNCHECKED_CAST")
    @ExperimentalTime
    fun <T : TaskBase> getProcessor(node: Node<T>): NodeProcessor<T, TaskState> {
        return when (node.task) {
            is RootTask -> RootProcessor(node as Node<RootTask>)
            is DoTask -> DoProcessor(node as Node<DoTask>)
            is ForTask -> ForProcessor(node as Node<ForTask>)
            is SetTask -> SetProcessor(node as Node<SetTask>)
            is SwitchTask -> SwitchProcessor(node as Node<SwitchTask>)
            is TryTask -> TryProcessor(node as Node<TryTask>)
            is RaiseTask -> RaiseProcessor(node as Node<RaiseTask>)
            is CallHTTP -> CallHttpProcessor(node as Node<CallHTTP>)
            is WaitTask -> WaitProcessor(node as Node<WaitTask>)
            is ForkTask -> ForkProcessor(node as Node<ForkTask>)
            is RunTask -> {
                // Dispatch to appropriate run processor based on run configuration type
                val runTask = node as Node<RunTask>
                when (runTask.task.run.get()) {
                    is RunShell -> RunShellProcessor(runTask)
                    is RunScript -> RunScriptProcessor(runTask)
                    is RunWorkflow -> RunWorkflowProcessor(runTask)
                    else -> throw IllegalArgumentException(
                        "Unknown run task type: ${runTask.task.run.get()?.javaClass?.simpleName}"
                    )
                }
            }

            else -> throw IllegalArgumentException("Unknown task type: ${node.task::class.simpleName}")
        } as NodeProcessor<T, TaskState>
    }
}
