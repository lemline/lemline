// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities.execution

import com.lemline.core.activities.runs.ScriptRun
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.ExternalScript
import io.serverlessworkflow.api.types.InlineScript
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ScriptExecutor(private val nodeInstance: NodeInstance<*>) {

    fun execute(runScript: RunScript): JsonElement {
        nodeInstance.info { "Executing script: ${nodeInstance.node.name}" }

        val scriptUnion = runScript.script
        val script = scriptUnion.get()

        // Get script content based on the script type (inline or external)
        val scriptContent = when (val scriptValue = script) {
            is InlineScript -> scriptValue.code
            is ExternalScript -> {
                // For external scripts, resolve the URI from the source endpoint
                val endpoint = scriptValue.source?.endpoint
                val uri = when (val endpointValue = endpoint?.get()) {
                    is URI -> endpointValue.toString()
                    is String -> endpointValue
                    else -> {
                        val errorMsg =
                            "Unsupported endpoint type for external script: ${endpoint?.javaClass?.simpleName}"
                        nodeInstance.error(com.lemline.core.errors.WorkflowErrorType.COMMUNICATION, errorMsg)
                    }
                }

                try {
                    // Try to read from file system first
                    val filePath = Paths.get(uri)
                    if (Files.exists(filePath)) {
                        Files.readString(filePath)
                    } else {
                        // If not found as a file path, try to load as a resource
                        javaClass.getResourceAsStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: {
                                val errorMsg = "Script file not found: $uri"
                                nodeInstance.error(com.lemline.core.errors.WorkflowErrorType.COMMUNICATION, errorMsg)
                            }()
                    }
                } catch (e: Exception) {
                    val errorMsg = "Failed to read script from $uri: ${e.message}"
                    nodeInstance.error(com.lemline.core.errors.WorkflowErrorType.COMMUNICATION, errorMsg)
                }
            }

            else -> {
                val errorMsg = "Unsupported script type: ${scriptValue?.javaClass?.simpleName}"
                nodeInstance.error(com.lemline.core.errors.WorkflowErrorType.COMMUNICATION, errorMsg)
            }
        } as String

        // Get script language
        val language = script.language.lowercase()

        // Evaluate arguments if present
        val arguments = script.arguments?.additionalProperties
            ?.mapValues { (_, value) ->
                nodeInstance.evalString(
                    nodeInstance.transformedInput,
                    value.toString(),
                    "script.arguments.value"
                )
            }
            ?.mapKeys { (key, _) ->
                nodeInstance.evalString(
                    nodeInstance.transformedInput,
                    key,
                    "script.arguments.key"
                )
            }

        // Evaluate environment variables if present
        val environment = script.environment?.additionalProperties?.mapValues { (_, value) ->
            nodeInstance.evalString(
                nodeInstance.transformedInput,
                value.toString(),
                "script.environment"
            )
        }

        nodeInstance.debug { "Script language: $language" }
        nodeInstance.debug { "Script content length: ${scriptContent.length} characters" }
        nodeInstance.debug { "Arguments: $arguments" }
        nodeInstance.debug { "Environment: $environment" }

        val returnType = runScript.`return` ?: STDOUT  // Default to stdout return type
        nodeInstance.debug { "Return: $returnType" }

        try {
            val scriptRun = ScriptRun(
                script = scriptContent,
                language = language,
                arguments = arguments,
                environment = environment,
                workingDir = File(".").toPath()
            )

            val processResult = scriptRun.execute()

            nodeInstance.debug { "Script execution completed with exit code: ${processResult.code}" }
            nodeInstance.debug { "stdout: ${processResult.stdout}" }
            if (processResult.stderr.isNotEmpty()) {
                nodeInstance.debug { "stderr: ${processResult.stderr}" }
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
            nodeInstance.error(e) { "Failed to execute script" }
            val errorMsg = "Script execution failed: ${e.message}"
            nodeInstance.error(com.lemline.core.errors.WorkflowErrorType.COMMUNICATION, errorMsg)
        }
    }
}
