// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.execution.context.Scope
import com.lemline.core.execution.states.NoState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SetTaskConfiguration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
        // Evaluate set expressions sequentially, merging each result
        // so later expressions can reference earlier values
        var currentDataset = transformedInput

        // Convert set to JsonObject to iterate over entries
        val setExpressions = LemlineJson.encodeToElement(getSet()) as JsonObject

        // Iterate through each set expression
        for ((key, valueExpr) in setExpressions) {
            // Evaluate the expression against the current dataset
            val evaluatedValue = eval(currentDataset, valueExpr, scope, force = false)

            // Merge into current dataset
            currentDataset = when (currentDataset) {
                is JsonObject -> JsonObject(currentDataset + (key to evaluatedValue))
                else -> JsonObject(mapOf(key to evaluatedValue))
            }
        }

        return currentDataset
    }

    private fun getSet(): SetTaskConfiguration = node.task.set ?: throw NoSuchElementException("SetTask has no set")
}
