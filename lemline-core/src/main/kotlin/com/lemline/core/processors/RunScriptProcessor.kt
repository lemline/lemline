// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RunState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import io.serverlessworkflow.api.types.ExternalScript
import io.serverlessworkflow.api.types.InlineScript
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
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
) : NodeProcessor<RunTask, RunState>(node) {

    override val isAsync = true

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope): RunState = RunState()

    /**
     * Build the script execution configuration.
     *
     * Extracts and resolves all script parameters from the task definition,
     * including code content, language, arguments, and environment.
     *
     * @param nodeStack The stack of nodes currently being processed
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return RunScriptStarted event with the resolved configuration
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        logger.debug { "Building script config for task: ${node.name}" }

        // Extract script configuration
        val runConfig = node.task.run.runScript
        val scriptUnion = runConfig.script
        val scriptConfig = scriptUnion.get()

        // Get script content based on the script type (inline or external)
        val code: String = when (scriptConfig) {
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

        val await = runConfig.isAwait
        val returnType = runConfig.`return` ?: ProcessReturnType.STDOUT

        val config = RunScriptConfig(
            language = language,
            code = code,
            arguments = arguments,
            environment = environment,
            await = await,
            returnType = returnType
        )

        return RunScriptStarted(
            nodeStack = nodeStack,
            input = transformedInput,
            config = config
        )
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
