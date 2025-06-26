// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.instances.runners.run
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunContainer
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunTaskConfiguration
import io.serverlessworkflow.api.types.RunWorkflow

class RunInstance(
    override val node: Node<RunTask>,
    override val parent: NodeInstance<*>
) : NodeInstance<RunTask>(node, parent) {

    private val runConfig: RunTaskConfiguration = node.task.run.get()

    override suspend fun run() {
        logInfo { "Executing run task: ${node.name}" }
        rawOutput = when (runConfig) {
            is RunShell -> run(runConfig)
            is RunScript -> run(runConfig)
            is RunWorkflow -> onError(COMMUNICATION, "Workflow execution not yet implemented")
            is RunContainer -> onError(COMMUNICATION, "Container execution not yet implemented")
            else -> onError(RUNTIME, "Unknown run task configuration: ${runConfig.javaClass.simpleName}")
        }
        logInfo { "Run task completed successfully: ${node.name}" }
    }
}
