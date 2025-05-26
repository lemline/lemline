// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.activities.execution.execute
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

    override suspend fun execute() {
        info { "Executing run task: ${node.name}" }
        rawOutput = when (runConfig) {
            is RunShell -> execute(runConfig)
            is RunScript -> execute(runConfig)
            is RunContainer -> error(COMMUNICATION, "Container execution not yet implemented")
            is RunWorkflow -> error(COMMUNICATION, "Workflow execution not yet implemented")
            else -> error(RUNTIME, "Unknown run task configuration: ${runConfig.javaClass.simpleName}")
        }
        info { "Run task completed successfully: ${node.name}" }
    }
}
