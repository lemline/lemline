// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.activities.runs.ShellRun
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT

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
                runConfig.runScript != null -> error(COMMUNICATION, "Script execution not yet implemented")
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
        info { "Executing shell command: ${node.name}" }

        val shellConfig = runShell.shell
        debug { "Shell config: $shellConfig" }

        // Evaluate command through expression evaluator
        debug { "About to evaluate command: ${shellConfig.command}" }
        val command = evalString(transformedInput, shellConfig.command, "shell.command")
        debug { "Evaluated command: $command" }

        // Evaluate arguments if present
        val arguments = shellConfig.arguments?.additionalProperties?.mapValues { (_, value) ->
            evalString(transformedInput, value.toString(), "shell.arguments")
        }

        // Evaluate environment variables if present
        val environment = shellConfig.environment?.additionalProperties?.mapValues { (_, value) ->
            evalString(transformedInput, value.toString(), "shell.environment")
        }

        debug { "Shell command: $command" }
        debug { "Arguments: $arguments" }
        debug { "Environment: $environment" }
        val awaitCompletion = true  // Default to await completion
        val returnType = STDOUT  // Default to stdout return type

        debug { "Await: $awaitCompletion" }
        debug { "Return: $returnType" }

        try {
            val shellRun = ShellRun(
                command = command,
                arguments = arguments,
                environment = environment
            )

            val result = shellRun.execute()

            debug { "Shell execution completed with exit code: ${result.code}" }
            debug { "stdout: ${result.stdout}" }
            if (result.stderr.isNotEmpty()) {
                debug { "stderr: ${result.stderr}" }
            }

            // Configure output based on return type
            rawOutput = LemlineJson.encodeToElement(
                when (returnType) {
                    STDOUT -> result.stdout
                    STDERR -> result.stderr
                    CODE -> result.code
                    ALL -> mapOf(
                        "code" to result.code,
                        "stdout" to result.stdout,
                        "stderr" to result.stderr
                    )

                    NONE -> null
                    else -> error(
                        RUNTIME,
                        "Unsupported return type: $returnType"
                    )
                }
            )

            // Throw exception if command failed (non-zero exit code) and we're awaiting completion
            if (awaitCompletion && result.code != 0) {
                error(
                    COMMUNICATION,
                    "Shell command failed with exit code ${result.code}: ${result.stderr}"
                )
            }

        } catch (e: Exception) {
            error(e) { "Failed to execute shell command" }
            error(COMMUNICATION, "Shell command execution failed: ${e.message}")
        }
    }
}
