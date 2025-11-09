// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.nodes.Node
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.JsonElement

/**
 * Immutable execution context for a single step.
 *
 * Contains all temporal data needed for expression evaluation and scope building.
 * This replaces the mutable NodeDescriptor pattern with pure functional data flow.
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * // Created fresh at node entry
 * val context = ExecutionContext(startedAt = Clock.System.now())
 *
 * // Evolved through pure transformations
 * val withInput = context.copy(
 *     rawInput = dataset,
 *     transformedInput = transformInput(dataset)
 * )
 *
 * val withOutput = withInput.copy(
 *     rawOutput = execute(input),
 *     transformedOutput = transformOutput(output)
 * )
 * ```
 *
 * @property startedAt When the node started execution
 * @property rawInput Input dataset before transformation
 * @property transformedInput Input dataset after input.from transformation
 * @property rawOutput Output from node action before transformation
 * @property transformedOutput Output after output.as transformation
 */
@ExperimentalTime
data class TaskContext(
    val startedAt: Instant,
    val rawInput: JsonElement? = null,
    val transformedInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
    val transformedOutput: JsonElement? = null
) {
    /**
     * Build task descriptor for scope (used in JQ expressions).
     *
     * Creates the $task.* scope variables for expression evaluation.
     */
    fun toTaskDescriptor(node: Node<*>): TaskDescriptor {
        return TaskDescriptor(
            name = node.name,
            reference = node.reference,
            definition = node.definition,
            startedAt = LemlineJson.encodeToElement(
                DateTimeDescriptor.from(startedAt.toJavaInstant())
            ),
            input = rawInput,
            output = rawOutput
        )
    }
}
