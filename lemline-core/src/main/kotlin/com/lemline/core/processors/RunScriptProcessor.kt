// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.SimpleState
import com.lemline.core.tasks.runs.Script
import io.serverlessworkflow.api.types.ExternalScript
import io.serverlessworkflow.api.types.InlineScript
import io.serverlessworkflow.api.types.RunScript
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node processor for RunTask with Script configuration - pure functional model.
 *
 * RunScript enables workflows to execute custom scripts in supported programming languages.
 * It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - runInlineScript:
 *       run:
 *         script:
 *           language: js
 *           code: |
 *             console.log('Hello, World!');
 *   - runExternalScript:
 *       run:
 *         script:
 *           language: python
 *           source:
 *             endpoint:
 *               uri: file:///path/to/script.py
 *           arguments:
 *             --name: Alice
 *           environment:
 *             MY_VAR: test
 *   - runWithExpression:
 *       run:
 *         script:
 *           language: js
 *           code: ${ "console.log('" + .message + "');" }
 * ```
 *
 * ## Script Configuration
 *
 * The `script` field contains script-specific arguments:
 * - **language**: The programming language (required) - supports 'js' (JavaScript) or 'python'
 * - **code**: Inline script source code (required if source not set)
 * - **source**: External script resource (required if code not set)
 * - **arguments**: Optional map of arguments (keys with optional values)
 * - **environment**: Optional map of environment variables
 * - **await**: Whether to wait for script completion (defaults to true)
 * - **return**: What to return (stdout, stderr, code, all, none - defaults to stdout)
 *
 * @property node Immutable RunTask definition with script configuration
 */
class RunScriptProcessor(
    node: Node<RunTask>,
) : NodeProcessor<RunTask, SimpleState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): SimpleState = SimpleState()

    /**
     * Execute script action.
     *
     * Executes a script with the configured parameters and returns the result
     * according to the specified return type.
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return Script execution result as JsonElement based on return type
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement {
        logger.debug { "Executing script: ${node.name}" }

        // Extract script configuration
        val runConfig = node.task.run.get()
        if (runConfig !is RunScript) {
            raiseError(
                WorkflowErrorType.RUNTIME,
                "Expected RunScript configuration but got: ${runConfig?.javaClass?.simpleName}"
            )
        }

        val scriptUnion = runConfig.script
        val scriptConfig = scriptUnion.get()

        // Get script content based on the script type (inline or external)
        val scriptContent: String = when (scriptConfig) {
            is InlineScript -> {
                // Evaluate code expression
                evaluateString(transformedInput, scriptConfig.code, "script.code", scope)
            }

            is ExternalScript -> {
                // For external scripts, resolve the URI from the source endpoint
                val endpoint = scriptConfig.source.endpoint
                val uri = toUrl(endpoint, transformedInput, scope)

                logger.debug { "Loading external script from: $uri" }

                // Try to read from the file system
                val filePath = Paths.get(URI.create(uri))
                if (!Files.exists(filePath)) {
                    raiseError(
                        WorkflowErrorType.CONFIGURATION,
                        "Script file not found: $uri"
                    )
                }
                try {
                    Files.readString(filePath)
                } catch (e: Exception) {
                    raiseError(
                        WorkflowErrorType.COMMUNICATION,
                        "Failed to read script from $uri: ${e.message}",
                        e.stackTraceToString()
                    )
                }
            }

            else -> {
                raiseError(
                    WorkflowErrorType.RUNTIME,
                    "Unsupported script type: ${scriptConfig?.javaClass?.simpleName}"
                )
            }
        }

        // Get script language
        val language = scriptConfig.language.lowercase()

        logger.debug { "Script language: $language" }
        logger.debug { "Script content length: ${scriptContent.length} characters" }

        // Evaluate arguments if present
        val arguments = scriptConfig.arguments?.additionalProperties
            ?.mapValues { (_, value) ->
                evaluateString(transformedInput, value.toString(), "script.arguments.value", scope)
            }
            ?.mapKeys { (key, _) ->
                evaluateString(transformedInput, key.toString(), "script.arguments.key", scope)
            }

        // Evaluate environment variables if present
        val environment = scriptConfig.environment?.additionalProperties?.mapValues { (_, value) ->
            evaluateString(transformedInput, value.toString(), "script.environment", scope)
        }

        logger.debug { "Arguments: $arguments" }
        logger.debug { "Environment: $environment" }

        val awaitCompletion = runConfig.isAwait
        val returnType = runConfig.`return` ?: ProcessReturnType.STDOUT  // Default to stdout return type

        logger.debug { "Await: $awaitCompletion" }
        logger.debug { "Return: $returnType" }

        return try {
            val script = Script(
                script = scriptContent,
                language = language,
                arguments = arguments,
                environment = environment,
                workingDir = File(".").toPath()
            )

            if (!awaitCompletion) {
                val process = script.executeAsync()
                logger.debug { "Launched script asynchronously with PID: ${process.pid()}" }
                // As per DSL, output for await: false is the transformed input
                return transformedInput
            }

            val processResult = script.execute()

            logger.debug { "Script execution completed with exit code: ${processResult.code}" }
            logger.debug { "stdout: ${processResult.stdout}" }
            logger.debug { "stderr: ${processResult.stderr}" }

            // Configure output based on the return type
            processResult.get(returnType)
        } catch (e: IllegalArgumentException) {
            // Handle unsupported language error specifically
            logger.error(e) { "Unsupported script language" }
            raiseError(WorkflowErrorType.CONFIGURATION, "Script language error: ${e.message}", e.stackTraceToString())
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute script" }
            val errorMsg = "Script execution failed: ${e.message}"
            raiseError(WorkflowErrorType.COMMUNICATION, errorMsg, e.stackTraceToString())
        }
    }

    /**
     * Evaluates a string expression.
     *
     * If the string contains JQ expression syntax, it evaluates it against the data.
     * Otherwise, returns the string as-is.
     */
    private fun evaluateString(
        data: JsonElement,
        expression: String,
        name: String,
        scope: Scope
    ): String {
        return when (val result = eval(data, JsonPrimitive(expression), scope, false)) {
            is JsonPrimitive -> result.content
            else -> raiseError(
                WorkflowErrorType.EXPRESSION,
                "'$name' expression must evaluate to a string, but got: $result"
            )
        }
    }

    /**
     * Converts endpoint to URL string.
     * Supports simple string URIs, URI templates, and endpoint configurations.
     */
    private fun toUrl(
        endpoint: io.serverlessworkflow.api.types.Endpoint,
        data: JsonElement,
        scope: Scope
    ): String {
        return when (endpoint.get()) {
            is String -> {
                val uri = endpoint.get() as String
                evaluateString(data, uri, "endpoint", scope)
            }

            else -> raiseError(
                WorkflowErrorType.RUNTIME,
                "Unsupported endpoint type for script source: ${endpoint.get()?.javaClass?.simpleName}"
            )
        }
    }
}
