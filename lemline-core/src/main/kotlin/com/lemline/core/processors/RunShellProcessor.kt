// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.RunState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.RunShellStarted
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
) : NodeProcessor<RunTask, RunState>(node) {

    override val isAsync = true

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope): RunState = RunState()

    /**
     * Build the shell command execution configuration.
     *
     * Extracts and resolves all shell parameters from the task definition,
     * including command, arguments, and environment.
     *
     * @param nodeStack The stack of nodes currently being processed
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return RunShellStarted event with the resolved configuration
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonElement,
        scope: Scope,
    ): WorkflowEvent {
        logger.debug { "Building shell config for task: ${node.name}" }

        // Extract shell configuration
        val runConfig = node.task.run.runShell
        val shellConfig = runConfig.shell

        // Evaluate command through expression evaluator
        val command = evaluateString(transformedInput, shellConfig.command, "shell.command", scope)

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

        val await = runConfig.isAwait
        val returnType = runConfig.`return` ?: ProcessReturnType.STDOUT

        val config = RunShellConfig(
            command = command,
            arguments = arguments,
            environment = environment,
            await = await,
            returnType = returnType
        )

        return RunShellStarted(
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
}
