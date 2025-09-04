// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.logger

import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Standard context keys used in logging for consistent logging.
 */
object LoggingContext {
    const val WORKFLOW_ID = "workflowId"
    const val WORKFLOW_NAME = "workflowName"
    const val WORKFLOW_VERSION = "workflowVersion"
    const val CURRENT_POSITION = "currentPosition"
}

/**
 * Coroutine context element that holds workflow-specific logging context.
 * This context is propagated across coroutine boundaries and automatically
 * applies MDC values when the coroutine runs.
 */
data class WorkflowLoggingContext(
    val workflowId: String? = null,
    val workflowName: String? = null,
    val workflowVersion: String? = null,
    val currentPosition: String? = null,
    val additionalContext: Map<String, String?> = emptyMap()
) : AbstractCoroutineContextElement(WorkflowLoggingContext) {
    companion object Key : CoroutineContext.Key<WorkflowLoggingContext>

    /**
     * Creates an MDC context map from this workflow context.
     */
    fun toMDCContextMap(): Map<String, String?> = buildMap {
        workflowId?.let { put(LoggingContext.WORKFLOW_ID, it) }
        workflowName?.let { put(LoggingContext.WORKFLOW_NAME, it) }
        workflowVersion?.let { put(LoggingContext.WORKFLOW_VERSION, it) }
        currentPosition?.let { put(LoggingContext.CURRENT_POSITION, it) }
        putAll(additionalContext)
    }

    /**
     * Applies this context to MDC for the current thread.
     */
    fun applyToMDC() {
        toMDCContextMap().forEach { (key, value) ->
            if (value != null) MDC.put(key, value)
        }
    }
}

/**
 * Set context values for the current thread's logging context.
 * These values will be included in all log messages until cleared.
 * This function is compatible with both regular code and coroutines.
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
 * This function is for backward compatibility with non-coroutine code.
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
    LoggingContext.WORKFLOW_ID to workflowId?.toString(),
    LoggingContext.WORKFLOW_NAME to workflowName?.toString(),
    LoggingContext.WORKFLOW_VERSION to workflowVersion?.toString(),
    LoggingContext.CURRENT_POSITION to currentPosition?.toString(),
    block = block,
)

/**
 * Set workflow context values for coroutines with proper context propagation.
 * This is the coroutine-aware version that properly handles thread switching.
 *
 * @param workflowId The workflow instance ID
 * @param workflowName The workflow name
 * @param workflowVersion The workflow version
 * @param currentPosition The current node position
 * @param block Suspending lambda that will be executed with the context values set
 * @return The result of the block
 */
suspend inline fun <T> withWorkflowContext(
    workflowId: WorkflowId? = null,
    workflowName: WorkflowName? = null,
    workflowVersion: WorkflowVersion? = null,
    currentPosition: NodePosition? = null,
    crossinline block: suspend () -> T,
): T {
    val currentContext = currentCoroutineContext()[WorkflowLoggingContext] ?: WorkflowLoggingContext()
    val newContext = WorkflowLoggingContext(
        workflowId = workflowId?.toString() ?: currentContext.workflowId,
        workflowName = workflowName?.toString() ?: currentContext.workflowName,
        workflowVersion = workflowVersion?.toString() ?: currentContext.workflowVersion,
        currentPosition = currentPosition?.toString() ?: currentContext.currentPosition,
        additionalContext = currentContext.additionalContext
    )

    // Filter out null values for MDCContext
    val mdcContextMap = newContext.toMDCContextMap().filterValues { it != null }.mapValues { it.value!! }

    return withContext(MDCContext(mdcContextMap) + newContext) {
        // Apply context to MDC for the current thread
        newContext.applyToMDC()
        block()
    }
}
