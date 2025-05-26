// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities.execution

import com.lemline.core.activities.runs.ShellRun
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ShellExecutor(private val nodeInstance: NodeInstance<*>) {

    fun execute(runShell: RunShell): kotlinx.serialization.json.JsonElement {
        nodeInstance.info { "Executing shell command: ${nodeInstance.node.name}" }

        val shellConfig = runShell.shell
        nodeInstance.debug { "Shell config: $shellConfig" }

        // Evaluate command through expression evaluator
        val command = nodeInstance.evalString(
            nodeInstance.transformedInput,
            shellConfig.command,
            "shell.command"
        )

        nodeInstance.debug { "Evaluated command: $command" }

        // Evaluate arguments if present
        val arguments = shellConfig.arguments?.additionalProperties
            ?.mapValues { (_, value) ->
                nodeInstance.evalString(
                    nodeInstance.transformedInput,
                    value.toString(),
                    "shell.arguments.value"
                )
            }
            ?.mapKeys { (key, _) ->
                nodeInstance.evalString(
                    nodeInstance.transformedInput,
                    key.toString(),
                    "shell.arguments.key"
                )
            }

        // Evaluate environment variables if present
        val environment = shellConfig.environment?.additionalProperties?.mapValues { (_, value) ->
            nodeInstance.evalString(
                nodeInstance.transformedInput,
                value.toString(),
                "shell.environment"
            )
        }

        nodeInstance.debug { "Shell command: $command" }
        nodeInstance.debug { "Arguments: $arguments" }
        nodeInstance.debug { "Environment: $environment" }

        val awaitCompletion = runShell.isAwait
        val returnType = runShell.`return` ?: STDOUT  // Default to stdout return type

        nodeInstance.debug { "Await: $awaitCompletion" }
        nodeInstance.debug { "Return: $returnType" }

        try {
            val shellRun = ShellRun(
                command = command,
                arguments = arguments,
                environment = environment
            )

            val result = shellRun.execute()

            nodeInstance.debug { "Shell execution completed with exit code: ${result.code}" }
            nodeInstance.debug { "stdout: ${result.stdout}" }
            if (result.stderr.isNotEmpty()) {
                nodeInstance.debug { "stderr: ${result.stderr}" }
            }

            // Configure output based on the return type
            val output = when (returnType) {
                STDOUT -> JsonPrimitive(result.stdout)
                STDERR -> JsonPrimitive(result.stderr)
                CODE -> JsonPrimitive(result.code)
                ALL -> JsonObject(
                    mapOf(
                        "code" to JsonPrimitive(result.code),
                        "stdout" to JsonPrimitive(result.stdout),
                        "stderr" to JsonPrimitive(result.stderr)
                    )
                )

                NONE -> JsonNull
            }

            // Throw exception if command failed (non-zero exit code) and we're awaiting completion
            if (awaitCompletion && result.code != 0) {
                nodeInstance.error(
                    COMMUNICATION,
                    "Shell command failed with exit code ${result.code}: ${result.stderr}"
                )
            }

            // output is already non-null as we handle all cases in the when expression
            return output
        } catch (e: Exception) {
            nodeInstance.error(e) { "Failed to execute shell command" }
            val errorMsg = "Shell command execution failed: ${e.message}"
            nodeInstance.error(COMMUNICATION, errorMsg)
        }
    }
}
