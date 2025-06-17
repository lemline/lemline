// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities.runners

import com.lemline.core.activities.runs.ShellRun
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun NodeInstance<*>.run(runShell: RunShell): JsonElement {
    info { "Executing run shell command: ${node.name}" }

    val shellConfig = runShell.shell
    debug { "Shell config: $shellConfig" }

    // Evaluate command through expression evaluator
    val command = evalString(
        transformedInput,
        shellConfig.command,
        "shell.command"
    )

    debug { "Evaluated command: $command" }

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

    debug { "Shell command: $command" }
    debug { "Arguments: $arguments" }
    debug { "Environment: $environment" }

    val awaitCompletion = runShell.isAwait
    val returnType = runShell.`return` ?: STDOUT  // Default to stdout return type

    debug { "Await: $awaitCompletion" }
    debug { "Return: $returnType" }

    try {
        val shellRun = ShellRun(
            command = command,
            arguments = arguments,
            environment = environment
        )

        if (!awaitCompletion) {
            val process = shellRun.executeAsync()
            debug { "Launched shell command asynchronously with PID: ${process.pid()}" }
            // As per DSL, output for await: false is the transformed input
            return transformedInput
        }

        val result = shellRun.execute()

        debug { "Shell execution completed with exit code: ${result.code}" }
        debug { "stdout: ${result.stdout}" }
        if (result.stderr.isNotEmpty()) {
            debug { "stderr: ${result.stderr}" }
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

        return output
    } catch (e: Exception) {
        error(e) { "Failed to execute shell command" }
        val errorMsg = "Shell command execution failed: ${e.message}"
        error(COMMUNICATION, errorMsg)
    }
}
