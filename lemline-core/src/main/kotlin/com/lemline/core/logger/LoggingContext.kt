// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.logger

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import org.slf4j.MDC

/**
 * Standard context keys used in MDC for consistent logging.
 */
object LoggingContext {
    const val WORKFLOW_ID = "workflowId"
    const val WORKFLOW_NAME = "workflowName"
    const val WORKFLOW_VERSION = "workflowVersion"
    const val CURRENT_POSITION = "currentPosition"
}

/**
 * Set context values for the current thread's logging context.
 * These values will be included in all log messages until cleared.
 *
 * @param block Lambda that will be executed with the context values set
 * @return The result of the block
 */
inline fun <T> withLoggingContext(vararg pairs: Pair<String, String?>, block: () -> T): T {
    // Save the current MDC context
    val previousContext = MDC.getCopyOfContextMap() ?: emptyMap()

    try {
        // Set new context values
        pairs.forEach { (key, value) ->
            if (value != null) {
                MDC.put(key, value)
            }
        }

        // Execute the block with the new context
        return block()
    } finally {
        // Restore the previous context
        MDC.clear()
        previousContext.forEach { (key, value) ->
            if (value != null) {
                MDC.put(key, value)
            } else {
                MDC.remove(key)
            }
        }
    }
}

/**
 * Set workflow context values for the current thread's logging context.
 * These values will be included in all log messages until cleared.
 *
 * @param workflowId The workflow instance ID
 * @param workflowName The workflow name
 * @param workflowVersion The workflow version
 * @param currentPosition The current node position
 * @param block Lambda that will be executed with the context values set
 * @return The result of the block
 */
inline fun <T> withWorkflowContext(
    workflowId: WorkflowId? = null,
    workflowName: WorkflowName? = null,
    workflowVersion: WorkflowVersion? = null,
    currentPosition: NodePosition? = null,
    block: () -> T,
): T = withLoggingContext(
    LoggingContext.WORKFLOW_ID to (workflowId?.toString() ?: UNKNOWN),
    LoggingContext.WORKFLOW_NAME to (workflowName?.toString() ?: UNKNOWN),
    LoggingContext.WORKFLOW_VERSION to (workflowVersion?.toString() ?: UNKNOWN),
    LoggingContext.CURRENT_POSITION to (currentPosition?.toString() ?: UNKNOWN),
    block = block,
)

/**
 * Updates a single context value in the current thread's logging context.
 * This is useful for updating dynamic values like node position during workflow execution.
 *
 * @param key The context key to update
 * @param value The new value for the context key
 */
fun updateLoggingContext(key: String, value: String?) {
    if (value != null) {
        MDC.put(key, value)
    } else {
        MDC.remove(key)
    }
}

const val UNKNOWN = "UNKNOWN"
