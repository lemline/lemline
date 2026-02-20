// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson.toJsonElement
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.CallFunctionState
import io.serverlessworkflow.api.types.CallFunction
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for CallFunction (function call task) - control-flow model.
 *
 * CallFunction enables workflows to invoke named functions defined in use.functions,
 * from URLs, or from resource catalogs. Unlike activities (HTTP, Script), function
 * calls are control-flow tasks that navigate into the function's node tree.
 *
 * ## Execution Model
 *
 * Function execution follows the normal step-by-step message flow:
 * 1. Enter CallFunction node
 * 2. Navigate to function's do block (position: `{callFuncPos}/_fn/do`)
 * 3. DoProcessor navigates through function tasks
 * 4. Execute function tasks step-by-step (normal message flow)
 * 5. When function completes, return to CallFunction
 * 6. CallFunction returns to its parent with function output
 *
 * ## Position Scheme
 *
 * Function nodes use composite positions:
 * - `/do/0/myCall` - CallFunction node
 * - `/do/0/myCall/_fn/do` - Function's do block
 * - `/do/0/myCall/_fn/do/0/step1` - First task in function
 *
 * For recursive calls, positions nest:
 * - `/do/0/fact/_fn/do/1/recurse/_fn/do/0/base`
 *
 * ## Context Sharing
 *
 * Functions share context with the calling workflow because they execute
 * in the same NodeStack with the same RootState.
 *
 * ## Example Workflow
 *
 * ```yaml
 * use:
 *   functions:
 *     validateAddress:
 *       do:
 *         - check:
 *             set:
 *               valid: true
 * do:
 *   - validate:
 *       call: validateAddress
 *       with:
 *         street: "${ .customer.address.street }"
 * ```
 *
 * @property node Immutable CallFunction definition
 */
class CallFunctionProcessor(
    node: Node<CallFunction>,
) : NodeProcessor<CallFunction, CallFunctionState>(node) {

    override val isAsync = false  // Control-flow, not async

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = CallFunctionState(
        startedAt = Clock.System.now(),
        entered = false
    )

    override fun stateWhenReEnteringFromChild(
        state: CallFunctionState,
        output: JsonElement,
        scope: Scope,
        nodeName: String?
    ): CallFunctionState = state.copy(entered = true)

    /**
     * Navigate to the function's do block or return to parent.
     *
     * - First entry (entered=false): Navigate to function's do block (/_fn/do)
     * - After function completes (entered=true): Return to parent
     *
     * Note: We navigate to the function's do block (/_fn/do), not the root (/_fn).
     * This is analogous to how workflows start at `/do` instead of `/`.
     * The function's DoProcessor handles navigation through function tasks.
     */
    override fun getNextNode(
        state: CallFunctionState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo {
        return when (state.entered) {
            false -> {
                // Navigate into the function
                // Create a stub node with the function's do block position
                // The actual node will be resolved by workflow.getNode()
                val fnPos = node.position.addToken(Token.FUN)
                val functionDoPosition = fnPos.addToken(Token.DO)

                // Evaluate the `with` arguments to pass to the function
                // If with is specified, evaluate it as the function input
                // Otherwise, pass the current dataset (transformed input) as function input
                val functionInput = evaluateWithArgs(dataset, scope)

                NavigationInfo(
                    nextNode = createStubNode(functionDoPosition),
                    nextDirective = null,
                    nextInput = functionInput
                )
            }

            true -> {
                // Function completed, return to parent
                NavigationInfo(
                    nextNode = node.parent,
                    nextDirective = getFlowDirective()
                )
            }
        }
    }

    /**
     * Evaluates the `with` arguments to produce the function input.
     *
     * The `with` block in a CallFunction contains key-value pairs that can be
     * either static values or expressions. These are evaluated and passed to
     * the function as its input.
     *
     * @param dataset The current dataset (transformed input)
     * @param scope The current execution scope
     * @return The evaluated input for the function
     */
    private fun evaluateWithArgs(dataset: JsonElement, scope: Scope): JsonElement {
        val withArgs = node.task.`with` ?: return dataset

        // Convert the with arguments to JsonElement and evaluate any expressions
        val withJson = withArgs.additionalProperties.toJsonElement()
        return eval(dataset, withJson, scope, false)
    }

    /**
     * Creates a stub node with the given position.
     *
     * This stub is used to generate a TaskScheduled event with the correct position.
     * The actual node (with correct task, parent chain, etc.) will be resolved
     * when the orchestrator calls workflow.getNode(position).
     */
    private fun createStubNode(position: NodePosition): Node<*> {
        // Create a minimal node - only position matters for TaskScheduled
        // The task type doesn't matter as it won't be used
        return Node(
            position = position,
            task = node.task,  // Reuse CallFunction task as placeholder
            name = "_fn",
            parent = node
        )
    }
}
