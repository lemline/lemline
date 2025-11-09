// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.core.execution.state.ActivityTaskState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.SetTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for SetTask (data manipulation).
 *
 * SetTask evaluates expressions and merges the results into the dataset.
 * It's an activity task (leaf node) with no children.
 *
 * ## Execution Flow
 *
 * 1. Enter: Initialize state
 * 2. Execute: Evaluate set expressions and merge into input
 * 3. Exit: Return merged data to parent
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
 * @property parent Parent node instance
 */
class SetNodeInstance(
    node: Node<SetTask>,
    parent: NodeInstance<*>?
) : NodeInstance<SetTask>(node, parent) {

    /**
     * State for activity task (minimal, just hasExecuted flag).
     */
    override var state: NodeState<*> = ActivityTaskState()

    init {
        // Activity tasks have no children
        children = emptyList()
    }

    /**
     * Execute SetTask action.
     *
     * Evaluates the set expressions and merges them into the input dataset.
     *
     * @param input Transformed input from parent
     * @return Input with set values merged
     */
    override suspend fun execute(input: JsonElement): JsonElement {
        // Evaluate set expressions using scope
        // The `set` field is a map of property names to expressions
        val setExpressions = LemlineJson.encodeToElement(node.task.set)

        // Evaluate and merge into input
        return eval(input, setExpressions, scope, force = true)
    }
}
