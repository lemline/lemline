// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.execution.state.ExprArgs
import com.lemline.core.execution.state.NoState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.SwitchItem
import io.serverlessworkflow.api.types.SwitchTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for SwitchTask (conditional branching) - pure functional model.
 *
 * SwitchTask evaluates case conditions and returns a flow directive to navigate
 * to a sibling task. It has **no children** - the target tasks are siblings in
 * the parent's `do` block.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - processOrder:
 *       switch:
 *         - case1:
 *             when: .orderType == "electronic"
 *             then: processElectronicOrder
 *         - case2:
 *             when: .orderType == "physical"
 *             then: processPhysicalOrder
 *         - default:
 *             then: handleUnknownOrderType
 *   - processElectronicOrder:
 *       do:
 *         - validatePayment: ...
 *   - processPhysicalOrder:
 *       do:
 *         - checkInventory: ...
 *   - handleUnknownOrderType:
 *       do:
 *         - logWarning: ...
 * ```
 *
 * ## Case Selection
 *
 * Cases are evaluated in order. The first case that matches is selected:
 * - If `when` is null → always matches (default case)
 * - If `when` evaluates to true → matches
 *
 * ## Flow Directive
 *
 * The selected case's `then` directive is returned to the parent
 *
 * @property node Immutable SwitchTask definition
 * @property parent Parent node instance
 */
class SwitchProcessor(
    node: Node<SwitchTask>,
) : NodeProcessor<SwitchTask, NoState>(node) {

    override fun createState(dataset: JsonElement, exprArgs: ExprArgs): NoState = NoState()

    override fun getNextStepInfo(
        state: NoState,
        dataset: JsonElement,
        nodeName: String?,
        exprArgs: ExprArgs,
        context: TaskContext
    ): Triple<NodeState?, Node<*>?, FlowDirective?> {

        var directive: FlowDirective? = null

        // Build scope for evaluating when conditions
        val scope = buildScope(exprArgs, context, input = dataset)

        // Evaluate the different cases in order
        for (item: SwitchItem in node.task.switch) {
            val whenCondition = item.switchCase.`when`

            // If no when condition, this is a default case - always matches
            if (whenCondition == null) {
                directive = item.switchCase.then
                break
            }

            // Evaluate the when condition
            if (evalBoolean(dataset, whenCondition, "switch.when", scope)) {
                directive = item.switchCase.then
                break
            }
        }

        if (directive == null) {
            raiseError(WorkflowErrorType.EXPRESSION, "No case matches in switch statement")
        }

        return Triple(null, node.parent, directive)
    }
}
