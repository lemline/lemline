// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.flows.TryInstance

/**
 * Internal Exception thrown during the execution of a workflow.
 *
 * This exception is used to propagate errors that occur during the execution of a workflow.
 * It's caught inside the WorkflowInstance::run method and is used to determine the next step in the workflow.
 *
 * @property raising The node instance that raised the exception.
 * @property catching The try instance that is catching the exception, if any.
 * @property error The workflow error associated with this exception.
 */
class WorkflowException(val raising: NodeInstance<*>, val catching: TryInstance?, val error: WorkflowError) :
    RuntimeException() {

    override fun toString() =
        "WorkflowException(raising=${raising.node.name}:${raising.node.position}, catching=${catching?.node?.name}:${catching?.node?.position}, error=$error)"
}
