// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.instances.RunInstance
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import kotlinx.serialization.json.JsonElement

internal suspend fun RunInstance.runShell(runShell: RunShell): JsonElement {
    logInfo { "Executing run shell command: ${node.name}" }

    val shellConfig = runShell.shell
    logDebug { "Shell config: $shellConfig" }

    // Evaluate command through expression evaluator
    val command = evalString(
        transformedInput,
        shellConfig.command,
        "shell.command"
    )

    logDebug { "Evaluated command: $command" }

    // Evaluate arguments if present
    val arguments = shellConfig.arguments?.additionalProperties
        ?.mapValues { (_, value) ->
            evalString(
                transformedInput,
                value.toString(),
                "shell.arguments.value"
            )
        }
        ?.mapKeys { (key, _) ->
            evalString(
                transformedInput,
                key.toString(),
                "shell.arguments.key"
            )
        }

    // Evaluate environment variables if present
    val environment = shellConfig.environment?.additionalProperties?.mapValues { (_, value) ->
        evalString(
            transformedInput,
            value.toString(),
            "shell.environment"
        )
    }

    logDebug { "Shell command: $command" }
    logDebug { "Arguments: $arguments" }
    logDebug { "Environment: $environment" }

    val awaitCompletion = runShell.isAwait
    val returnType = runShell.`return` ?: STDOUT  // Default to stdout return type

    logDebug { "Await: $awaitCompletion" }
    logDebug { "Return: $returnType" }

    try {
        val shellRun = Shell(
            command = command,
            arguments = arguments,
            environment = environment
        )

        if (!awaitCompletion) {
            val process = shellRun.executeAsync()
            logDebug { "Launched shell command asynchronously with PID: ${process.pid()}" }
            // As per DSL, output for await: false is the transformed input
            return transformedInput
        }

        val processResult = shellRun.execute()

        logDebug { "Shell execution completed with exit code: ${processResult.code}" }
        logDebug { "stdout: ${processResult.stdout}" }
        logDebug { "stderr: ${processResult.stderr}" }

        // Configure output based on the return type
        return processResult.get(returnType)
    } catch (e: Exception) {
        logError(e) { "Failed to execute shell command" }
        val errorMsg = "Shell command execution failed: ${e.message}"
        onError(COMMUNICATION, errorMsg)
    }
}
