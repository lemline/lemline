// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.core.errors.InternalException
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.RaiseState
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RaiseTaskError
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * Node processor for RaiseTask (raise errors) - throws ExecutionWorkflowException.
 *
 * RaiseTask is an activity task that raises a workflow error, which can be
 * caught by a TryTask ancestor's catch block.
 *
 * ## Example Workflow
 *
 * ```yaml
 * - raiseError:
 *     raise:
 *       error:
 *         type: https://serverlessworkflow.io/spec/1.0.0/errors/validation
 *         status: 400
 * ```
 *
 * @property node Immutable RaiseTask definition
 */
class RaiseProcessor(
    node: Node<RaiseTask>
) : NodeProcessor<RaiseTask, RaiseState>(node) {

    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope) = RaiseState(
        startedAt = Clock.System.now()
    )

    override suspend fun execute(transformedInput: JsonElement, scope: Scope, state: RaiseState): JsonElement {
        val errorDef = node.task.raise.error.getErrorDef()

        // Create WorkflowError from error definition
        val error = InternalException.Error(
            type = errorDef.getErrorType(),
            status = errorDef.status,
            position = node.position.toString(),
            title = errorDef.title,
            details = errorDef.detail
        )

        // Throw WorkflowException - this will be caught by CompleteOrchestrator
        // and handled by the nearest TryTask with matching catch block
        throw InternalException(error)
    }

    private fun RaiseTaskError.getErrorDef(): io.serverlessworkflow.api.types.Error {
        return when (val error = get()) {
            // Reference to named error in workflow.use.errors - not supported yet in new execution model
            is String -> throw IllegalArgumentException("Named error references not yet supported: $error")
            is io.serverlessworkflow.api.types.Error -> error
            else -> throw IllegalArgumentException("Unknown Error '$error'")
        }
    }

    private fun io.serverlessworkflow.api.types.Error.getErrorType(): String {
        return when (val errorType = type.get()) {
            is io.serverlessworkflow.api.types.UriTemplate -> {
                when (val uri = errorType.get()) {
                    is java.net.URI -> uri.toString()
                    is String -> uri
                    else -> throw IllegalArgumentException("Unknown Uri Template '$uri'")
                }
            }

            is String -> errorType
            else -> raiseError(RUNTIME, "Unknown Error type '$errorType'")
        }
    }
}
