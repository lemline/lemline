// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.activities.execution.ScriptExecutor
import com.lemline.core.nodes.activities.execution.ShellExecutor
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask

class RunInstance(
    override val node: Node<RunTask>,
    override val parent: NodeInstance<*>
) : NodeInstance<RunTask>(node, parent) {

    private val runConfig = node.task.run

    override suspend fun execute() {
        info { "Executing run task: ${node.name}" }
        try {
            when {
                runConfig.runShell != null -> executeShell(runConfig.runShell)
                runConfig.runContainer != null -> error(COMMUNICATION, "Container execution not yet implemented")
                runConfig.runScript != null -> executeScript(runConfig.runScript)
                runConfig.runWorkflow != null -> error(COMMUNICATION, "Workflow execution not yet implemented")
                else -> error(COMMUNICATION, "No valid run configuration found")
            }
            info { "Run task completed successfully: ${node.name}" }
        } catch (e: Exception) {
            error(e) { "Run task failed: ${node.name}" }
            throw e
        }
    }

    private fun executeShell(runShell: RunShell) {
        try {
            rawOutput = ShellExecutor(this).execute(runShell)
        } catch (e: Exception) {
            error(e) { "Failed to execute shell command" }
            error(COMMUNICATION, "Shell execution failed: ${e.message}")
        }
    }

    private fun executeScript(runScript: RunScript) {
        try {
            rawOutput = ScriptExecutor(this).execute(runScript)
        } catch (e: Exception) {
            error(e) { "Failed to execute script" }
            error(COMMUNICATION, "Script execution failed: ${e.message}")
        }
    }
}
