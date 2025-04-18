package com.lemline.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Logging levels used throughout the application.
 * - TRACE: Very detailed information, useful for debugging complex issues
 * - DEBUG: Detailed information on the flow through the system
 * - INFO: Interesting runtime events (startup/shutdown, configuration changes)
 * - WARN: Potentially harmful situations that might lead to errors
 * - ERROR: Error events that might still allow the application to continue running
 */
enum class LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR
}

/**
 * Standard context keys used in MDC for consistent logging.
 */
object LogContext {
    const val WORKFLOW_ID = "workflowId"
    const val WORKFLOW_NAME = "workflowName"
    const val WORKFLOW_VERSION = "workflowVersion"
    const val NODE_POSITION = "nodePosition"
    const val CORRELATION_ID = "correlationId"
    const val REQUEST_ID = "requestId"
    const val USER_ID = "userId"
}

/**
 * Extension function to get a Logger for any class.
 */
fun <T : Any> T.logger(): Logger = LoggerFactory.getLogger(this::class.java)

/**
 * Log a message at TRACE level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.trace(e: Throwable? = null, message: () -> String) {
    if (isTraceEnabled) {
        trace(message(), e)
    }
}

/**
 * Log a message at DEBUG level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.debug(e: Throwable? = null, message: () -> String) {
    if (isDebugEnabled) {
        debug(message(), e)
    }
}

/**
 * Log a message at INFO level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.info(e: Throwable? = null, message: () -> String) {
    if (isInfoEnabled) {
        info(message(), e)
    }
}

/**
 * Log a message at WARN level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.warn(e: Throwable? = null, message: () -> String) {
    if (isWarnEnabled) {
        warn(message(), e)
    }
}

/**
 * Log a message at ERROR level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.error(e: Throwable? = null, message: () -> String) {
    if (isErrorEnabled) {
        error(message(), e)
    }
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
 * @param nodePosition The current node position
 * @param block Lambda that will be executed with the context values set
 * @return The result of the block
 */
inline fun <T> withWorkflowContext(
    workflowId: String? = null,
    workflowName: String? = null,
    workflowVersion: String? = null,
    nodePosition: String? = null,
    block: () -> T
): T = withLoggingContext(
    LogContext.WORKFLOW_ID to workflowId,
    LogContext.WORKFLOW_NAME to workflowName,
    LogContext.WORKFLOW_VERSION to workflowVersion,
    LogContext.NODE_POSITION to nodePosition,
    block = block
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

/**
 * Updates the node position in the current thread's logging context.
 * This is useful for tracking the current position in a workflow as it evolves.
 *
 * @param nodePosition The current node position
 */
fun updateNodePosition(nodePosition: String?) {
    updateLoggingContext(LogContext.NODE_POSITION, nodePosition)
}
