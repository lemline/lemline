// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import kotlin.time.ExperimentalTime

/**
 * Internal Exception thrown during the execution of a workflow.
 *
 * This exception is used to propagate errors that occur during workflow execution.
 * In the new execution model, it's caught by ExecutionOrchestrator which finds
 * the appropriate TryTask handler.
 *
 * @property error The workflow error associated with this exception.
 */
@ExperimentalTime
open class WorkflowException(
    val error: WorkflowError
) : RuntimeException() {

    override fun toString() =
        "WorkflowException(error=$error)"
}
