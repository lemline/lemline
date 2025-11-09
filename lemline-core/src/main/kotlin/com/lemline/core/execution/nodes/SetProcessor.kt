// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.core.execution.state.NoState
import com.lemline.core.execution.state.Scope
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.SetTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for SetTask (data manipulation) - pure functional model.
 *
 * SetTask evaluates expressions and merges the results into the dataset.
 * It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - setStatus:
 *       set:
 *         status: "processed"
 *         timestamp: $now
 *   - setResult:
 *       set:
 *         result:
 *           status: .status
 *           time: .timestamp
 * ```
 *
 * Input: `{}`
 * After setStatus: `{ status: "processed", timestamp: "2025-01-08T..." }`
 * After setResult: `{ status: "processed", timestamp: "...", result: { status: "processed", time: "..." } }`
 *
 * ## Set Expression Evaluation
 *
 * The `set` field contains key-value pairs where:
 * - Keys: Property names to set
 * - Values: JQ expressions to evaluate
 *
 * All expressions are evaluated with the current scope (task context + input).
 *
 * @property node Immutable SetTask definition
 */
class SetProcessor(
    node: Node<SetTask>,
) : NodeProcessor<SetTask, NoState>(node) {

    override fun createState(transformedInput: JsonElement, scope: Scope): NoState = NoState()

    /**
     * Execute SetTask action.
     *
     * Evaluates the set expressions and merges them into the input dataset.
     *
     * @param transformedInput Transformed input from parent
     * @param scope Expression arguments
     * @param context Execution context
     * @return Input with set values merged
     */
    override suspend fun execute(
        transformedInput: JsonElement,
        scope: Scope,
    ): JsonElement {
        // Evaluate set expressions using scope
        // The `set` field is a map of property names to expressions
        val setExpressions = LemlineJson.encodeToElement(node.task.set)

        // Evaluate expressions and merge into input
        return eval(transformedInput, setExpressions, scope, force = true)
    }
}
