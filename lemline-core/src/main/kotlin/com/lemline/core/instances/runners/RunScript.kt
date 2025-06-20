// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances.runners

import com.lemline.core.activities.runs.ScriptRun
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.utils.toUrl
import io.serverlessworkflow.api.types.ExternalScript
import io.serverlessworkflow.api.types.InlineScript
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import io.serverlessworkflow.api.types.Script
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun NodeInstance<*>.run(runScript: RunScript): JsonElement {
    logInfo { "Executing run script: ${node.name}" }

    val scriptUnion = runScript.script
    val script: Script? = scriptUnion.get()

    // Get script content based on the script type (inline or external)
    val scriptContent: String = when (script) {
        is InlineScript -> script.code
        is ExternalScript -> {
            // For external scripts, resolve the URI from the source endpoint
            val endpoint = script.source.endpoint
            val uri = toUrl(endpoint)


            // Try to read from the file system first
            val filePath = Paths.get(URI.create(uri))
            if (!Files.exists(filePath)) {
                val errorMsg = "File not found from $uri"
                onError(CONFIGURATION, errorMsg)
            }
            try {
                Files.readString(filePath)
            } catch (e: Exception) {
                val errorMsg = "Failed to read script from $uri: ${e.message}"
                onError(COMMUNICATION, errorMsg)
            }
        }

        else -> {
            val errorMsg = "Unsupported script type: ${script?.javaClass?.simpleName}"
            onError(RUNTIME, errorMsg)
        }
    }

    // Get script language
    val language = script.language.lowercase()

    // Evaluate arguments if present
    val arguments = script.arguments?.additionalProperties
        ?.mapValues { (_, value) ->
            evalString(
                transformedInput,
                value.toString(),
                "script.arguments.value"
            )
        }
        ?.mapKeys { (key, _) ->
            evalString(
                transformedInput,
                key,
                "script.arguments.key"
            )
        }

    // Evaluate environment variables if present
    val environment = script.environment?.additionalProperties?.mapValues { (_, value) ->
        evalString(
            transformedInput,
            value.toString(),
            "script.environment"
        )
    }

    logDebug { "Script language: $language" }
    logDebug { "Script content length: ${scriptContent.length} characters" }
    logDebug { "Arguments: $arguments" }
    logDebug { "Environment: $environment" }

    val returnType = runScript.`return` ?: STDOUT  // Default to stdout return type
    logDebug { "Return: $returnType" }

    try {
        val scriptRun = ScriptRun(
            script = scriptContent,
            language = language,
            arguments = arguments,
            environment = environment,
            workingDir = File(".").toPath()
        )

        val await = runScript.isAwait
        if (!await) {
            // Launch process and return immediately (do not wait for completion)
            val process = scriptRun.executeAsync()
            logDebug { "Launched script asynchronously with PID: ${process.pid()}" }
            // As per DSL, output for await: false is the transformed input
            return transformedInput
        }

        val processResult = scriptRun.execute()

        logDebug { "Script execution completed with exit code: ${processResult.code}" }
        logDebug { "stdout: ${processResult.stdout}" }
        if (processResult.stderr.isNotEmpty()) {
            logDebug { "stderr: ${processResult.stderr}" }
        }

        // Configure output based on the return type
        return when (returnType) {
            STDOUT -> JsonPrimitive(processResult.stdout)
            STDERR -> JsonPrimitive(processResult.stderr)
            CODE -> JsonPrimitive(processResult.code)
            ALL -> JsonObject(
                mapOf(
                    "code" to JsonPrimitive(processResult.code),
                    "stdout" to JsonPrimitive(processResult.stdout),
                    "stderr" to JsonPrimitive(processResult.stderr)
                )
            )

            NONE -> JsonNull
        }
    } catch (e: Exception) {
        logError(e) { "Failed to execute script" }
        val errorMsg = "Script execution failed: ${e.message}"
        onError(COMMUNICATION, errorMsg)
    }
}
