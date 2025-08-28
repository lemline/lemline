// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.instances.TryInstance
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.processor.Processor
import kotlin.time.ExperimentalTime

/**
 * Internal Exception thrown during the execution of a workflow.
 *
 * This exception is used to propagate errors that occur during the execution of a workflow.
 * it's sent by the [NodeInstance]::raiseError method,
 * and caught inside the [Processor]::run method.
 *
 * @property raising The node instance that raised the exception.
 * @property error The workflow error associated with this exception.
 */
@ExperimentalTime
open class WorkflowException(
    val raising: NodeInstance<*>,
    val error: WorkflowError
) : RuntimeException() {

    /**
     * Returns the try instance catching the exception, if any.
     */
    fun getTry(): TryInstance? = raising.getTry(error)

    override fun toString() =
        "WorkflowException(raising=${raising.node.name}:${raising.node.position}, error=$error)"
}
