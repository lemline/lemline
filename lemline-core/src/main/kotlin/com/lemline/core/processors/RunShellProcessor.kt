// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.runs.Shell
import com.lemline.core.states.NoState
import io.serverlessworkflow.api.types.RunShell
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node processor for RunTask with Shell configuration - pure functional model.
 *
 * RunShell enables workflows to execute shell commands on the underlying operating system.
 * It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - listFiles:
 *       run:
 *         shell:
 *           command: ls -la
 *   - echo:
 *       run:
 *         shell:
 *           command: echo
 *           arguments:
 *             -n: "Hello World"
 *           environment:
 *             MY_VAR: test
 *   - withExpression:
 *       run:
 *         shell:
 *           command: ${ "echo " + .message }
 * ```
 *
 * ## Shell Command Configuration
 *
 * The `shell` field contains shell-specific arguments:
 * - **command**: The shell command to execute (required)
 * - **arguments**: Optional map of arguments (keys with optional values)
 * - **environment**: Optional map of environment variables
 * - **await**: Whether to wait for command completion (defaults to true)
 * - **return**: What to return (stdout, stderr, code, all, none - defaults to stdout)
 *
 * @property node Immutable RunTask definition with shell configuration
 */
class RunShellProcessor(
    node: Node<RunTask>,
) : NodeProcessor<RunTask, NoState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): NoState = NoState()

    /**
     * Execute shell command action.
     *
     * Executes a shell command with the configured parameters and returns the result
     * according to the specified return type.
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return Shell execution result as JsonElement based on return type
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement {
        logger.debug { "Executing shell command: ${node.name}" }

        // Extract shell configuration
        val runConfig = node.task.run.get()
        if (runConfig !is RunShell) {
            raiseError(
                WorkflowErrorType.RUNTIME,
                "Expected RunShell configuration but got: ${runConfig?.javaClass?.simpleName}"
            )
        }

        val shellConfig = runConfig.shell

        // Evaluate command through expression evaluator
        val command = evaluateString(transformedInput, shellConfig.command, "shell.command", scope)

        logger.debug { "Evaluated command: $command" }

        // Evaluate arguments if present
        val arguments = shellConfig.arguments?.additionalProperties
            ?.mapValues { (_, value) ->
                evaluateString(transformedInput, value.toString(), "shell.arguments.value", scope)
            }
            ?.mapKeys { (key, _) ->
                evaluateString(transformedInput, key.toString(), "shell.arguments.key", scope)
            }

        // Evaluate environment variables if present
        val environment = shellConfig.environment?.additionalProperties?.mapValues { (_, value) ->
            evaluateString(transformedInput, value.toString(), "shell.environment", scope)
        }

        logger.debug { "Shell command: $command" }
        logger.debug { "Arguments: $arguments" }
        logger.debug { "Environment: $environment" }

        val awaitCompletion = runConfig.isAwait
        val returnType = runConfig.`return` ?: ProcessReturnType.STDOUT  // Default to stdout return type

        logger.debug { "Await: $awaitCompletion" }
        logger.debug { "Return: $returnType" }

        return try {
            val shell = Shell(
                command = command,
                arguments = arguments,
                environment = environment
            )

            if (!awaitCompletion) {
                val process = shell.executeAsync()
                logger.debug { "Launched shell command asynchronously with PID: ${process.pid()}" }
                // As per DSL, output for await: false is the transformed input
                return transformedInput
            }

            val processResult = shell.execute()

            logger.debug { "Shell execution completed with exit code: ${processResult.code}" }
            logger.debug { "stdout: ${processResult.stdout}" }
            logger.debug { "stderr: ${processResult.stderr}" }

            // Configure output based on the return type
            processResult.get(returnType)
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute shell command" }
            val errorMsg = "Shell command execution failed: ${e.message}"
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
}
