// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.nodes

import com.lemline.core.execution.state.DoTaskState
import com.lemline.core.execution.state.NodeState
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.RootTask
import io.serverlessworkflow.api.types.DoTask
import io.serverlessworkflow.api.types.SetTask
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node instance for DoTask (sequential execution).
 *
 * DoTask executes its children sequentially in order. Each child receives
 * the previous child's output as its input.
 *
 * ## Execution Flow
 *
 * 1. Enter: Initialize state with doSize and childNames
 * 2. Continue: Navigate to next child (childIndex = 0, 1, 2, ...)
 * 3. Re-enter: Receive child output, advance childIndex
 * 4. Exit: When childIndex >= doSize, return last child's output
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - validateOrder:
 *       call: http
 *       with:
 *         url: https://api.example.com/validate
 *   - processOrder:
 *       set:
 *         status: "processed"
 *   - notifyCustomer:
 *       emit:
 *         event: "orderProcessed"
 * ```
 *
 * Execution order:
 * 1. Enter DoTask → childIndex = -1
 * 2. Continue → childIndex = 0 → enter validateOrder
 * 3. validateOrder completes → re-enter DoTask → childIndex = 1 → enter processOrder
 * 4. processOrder completes → re-enter DoTask → childIndex = 2 → enter notifyCustomer
 * 5. notifyCustomer completes → re-enter DoTask → childIndex = 3 (>= doSize) → exit
 *
 * @property node Immutable DoTask definition
 * @property parent Parent node instance (or null for root)
 */
class DoNodeInstance(
    node: Node<DoTask>,
    parent: NodeInstance<*>?
) : NodeInstance<DoTask>(node, parent) {

    /**
     * State with immutable/mutable separation.
     */
    override var state: NodeState<*>

    init {
        // Build child names map for goto support
        val childNames = node.children?.mapIndexed { index, childNode ->
            childNode.name to index
        }?.toMap() ?: emptyMap()

        // Initialize state
        state = DoTaskState(
            doSize = node.children?.size ?: 0,
            childNames = childNames
        )

        // Build child instances recursively
        children = node.children?.map { childNode ->
            buildNodeInstance(childNode, this)
        } ?: emptyList()
    }

    /**
     * Execute action (for DoTask, this is a flow task with no action).
     *
     * DoTask is a flow control task, not an activity task.
     * It just passes the input through unchanged.
     *
     * @param input Transformed input
     * @return Input unchanged (no action for flow tasks)
     */
    override suspend fun execute(input: JsonElement): JsonElement {
        // Flow task - no action, just return input
        return input
    }
}

/**
 * Build node instance from definition.
 *
 * Factory function to create the appropriate NodeInstance subclass based on
 * the task type in the Node definition.
 *
 * This will be expanded to handle all task types as we implement them.
 *
 * @param node Immutable node definition
 * @param parent Parent node instance
 * @return Appropriate NodeInstance subclass
 */
@Suppress("UNCHECKED_CAST")
fun buildNodeInstance(node: Node<*>, parent: NodeInstance<*>?): NodeInstance<*> {
    return when (val task = node.task) {
        // RootTask is just a DoTask wrapper, treat it like DoTask
        is RootTask -> {
            // Build a temporary DoTask from the root task's do list
            val doTask = DoTask()
            doTask.setDo(task.`do`)
            // Create node with DoTask
            val doNode = Node(
                position = node.position,
                task = doTask,
                name = node.name,
                parent = node.parent
            )
            DoNodeInstance(doNode, parent)
        }

        is DoTask -> DoNodeInstance(node as Node<DoTask>, parent)
        is SetTask -> SetNodeInstance(node as Node<SetTask>, parent)

        // TODO: Add more task types as we implement them
        // is ForTask -> ForNodeInstance(node as Node<ForTask>, parent)
        // is SwitchTask -> SwitchNodeInstance(node as Node<SwitchTask>, parent)
        // is CallHTTP -> CallHttpInstance(node as Node<CallHTTP>, parent)

        else -> throw UnsupportedOperationException(
            "Task type not yet implemented: ${task::class.simpleName}"
        )
    }
}
