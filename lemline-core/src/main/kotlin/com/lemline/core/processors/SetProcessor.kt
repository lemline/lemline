// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.SetState
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.api.types.SetTaskConfiguration
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
) : NodeProcessor<SetTask, SetState>(node) {

    override fun stateEnterFromParent(transformedInput: JsonElement, scope: Scope) = SetState()

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
        state: SetState,
    ): JsonElement {

        return eval(transformedInput, LemlineJson.encodeToElement(getSet()), scope)
    }

    private fun getSet(): SetTaskConfiguration = node.task.set ?: throw NoSuchElementException("SetTask has no set")
}
