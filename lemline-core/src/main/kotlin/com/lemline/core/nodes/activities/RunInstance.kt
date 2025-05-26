// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.activities.execution.execute
import io.serverlessworkflow.api.types.RunTask

class RunInstance(
    override val node: Node<RunTask>,
    override val parent: NodeInstance<*>
) : NodeInstance<RunTask>(node, parent) {

    private val runConfig = node.task.run

    override suspend fun execute() {
        info { "Executing run task: ${node.name}" }
        rawOutput = when {
            runConfig.runShell != null -> execute(runConfig.runShell)
            runConfig.runContainer != null -> error(COMMUNICATION, "Container execution not yet implemented")
            runConfig.runScript != null -> execute(runConfig.runScript)
            runConfig.runWorkflow != null -> error(COMMUNICATION, "Workflow execution not yet implemented")
            else -> error(COMMUNICATION, "No valid run configuration found")
        }
        info { "Run task completed successfully: ${node.name}" }
    }
}
