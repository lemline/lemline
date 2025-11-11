// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.nodes.Node
import java.util.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Exception thrown when a RunWorkflow task needs to start a child workflow.
 *
 * This exception is part of the control flow mechanism (similar to WorkflowException for try/catch).
 * It signals to the ExecutionOrchestrator that a child workflow should be started.
 *
 * The orchestrator handles this differently based on its type:
 * - CompleteOrchestrator: Executes child inline (synchronous)
 * - PausableOrchestrator: Pauses and persists parent state for distributed execution
 *
 * @property childId Unique identifier for the child workflow instance
 * @property namespace Namespace of the child workflow to execute
 * @property name Name of the child workflow to execute
 * @property version Version of the child workflow to execute
 * @property input Transformed input for the child workflow
 * @property childNode The node definition for the child workflow
 * @property awaitCompletion Whether parent should wait for child completion (true) or fire-and-forget (false)
 */
class ChildWorkflowStartedException(
    val childId: UUID,
    val namespace: String,
    val name: String,
    val version: String,
    val input: JsonElement,
    val childNode: Node<*>,
    val awaitCompletion: Boolean
) : RuntimeException() {

    override fun toString() =
        "ChildWorkflowStartedException(childId=$childId, namespace=$namespace, name=$name, version=$version, await=$awaitCompletion)"
}
