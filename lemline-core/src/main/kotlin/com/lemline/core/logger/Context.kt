// SPDX-License-Identifier: BUSL-1.1
@file:Suppress("unused")

package com.lemline.core.logger

import com.lemline.core.workflows.WorkflowId
import com.lemline.core.workflows.WorkflowName
import com.lemline.core.workflows.WorkflowVersion
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import org.slf4j.MDC

/**
 * Standard context keys used in logging for consistent logging.
 */
private const val WORKFLOW_ID = "workflowId"
private const val WORKFLOW_NAME = "workflowName"
private const val WORKFLOW_VERSION = "workflowVersion"

/**
 * Returns a copy of the current thread's MDC map, or an empty map if none.
 */
private fun currentMDC(): Map<String, String> = MDC.getCopyOfContextMap() ?: emptyMap()

/**
 * Utility to filter out null values from a Map<String, String?>.
 */
private fun Map<String, String?>.compact(): Map<String, String> =
    buildMap {
        for ((k, v) in this@compact) if (v != null) put(k, v)
    }

/**
 * A coroutine context element that carries workflow-related logging context and
 * applies/restores it to the thread's MDC on dispatch boundaries.
 *
 * This implementation MERGES its MDC entries into the current MDC instead of overwriting
 * everything, and restores the previous MDC when the coroutine leaves the dispatcher thread.
 */
data class WorkflowLoggingContext(
    val workflowId: String,
    val workflowName: String,
    val workflowVersion: String,
    val additionalContext: Map<String, String?> = emptyMap()
) : ThreadContextElement<Map<String, String>?>,
    AbstractCoroutineContextElement(WorkflowLoggingContext) {

    companion object Key : CoroutineContext.Key<WorkflowLoggingContext>

    /**
     * Exposes all desired MDC entries, possibly containing nulls (which are ignored on apply).
     */
    fun toMDCEntries(): Map<String, String?> = buildMap {
        put(WORKFLOW_ID, workflowId)
        put(WORKFLOW_NAME, workflowName)
        put(WORKFLOW_VERSION, workflowVersion)
        putAll(additionalContext)
    }

    /**
     * Called when the coroutine resumes on a thread: merge our entries into the current MDC,
     * and return the PREVIOUS snapshot to be restored on suspension.
     */
    override fun updateThreadContext(context: CoroutineContext): Map<String, String>? {
        val previous = MDC.getCopyOfContextMap()
        val ourEntries = toMDCEntries().compact()

        if (ourEntries.isEmpty()) return previous

        val base = previous ?: emptyMap()
        val merged = base + ourEntries
        MDC.setContextMap(merged)

        return previous
    }

    /**
     * Restore the previous MDC snapshot captured in updateThreadContext.
     */
    override fun restoreThreadContext(context: CoroutineContext, oldState: Map<String, String>?) {
        if (oldState != null) {
            MDC.setContextMap(oldState)
        } else {
            MDC.clear()
        }
    }

    /**
     * Create a new context by adding arbitrary pairs (nullable values are skipped).
     */
    fun plusPairs(vararg pairs: Pair<String, String?>): WorkflowLoggingContext =
        copy(additionalContext = additionalContext + pairs.toMap())
}

/* -------------------------------------------------------------------------------------------------
 * Blocking helpers (thread-only)
 * ------------------------------------------------------------------------------------------------*/

/**
 * Runs [block] with the given MDC pairs temporarily applied to the current thread.
 * Previous MDC is restored afterwards.
 */
inline fun <T> withThreadMDC(vararg pairs: Pair<String, String?>, block: () -> T): T {
    val previous = MDC.getCopyOfContextMap()
    try {
        for ((k, v) in pairs) if (v != null) MDC.put(k, v)
        return block()
    } finally {
        if (previous != null) {
            MDC.setContextMap(previous)
        } else {
            MDC.clear()
        }
    }
}

/**
 * Runs [block] with workflow-specific MDC keys on the current thread (non-suspend).
 */
fun <T> withThreadLoggingContext(
    workflowId: WorkflowId? = null,
    workflowName: WorkflowName? = null,
    workflowVersion: WorkflowVersion? = null,
    additional: Map<String, String?> = emptyMap(),
    block: () -> T
): T = withThreadMDC(
    WORKFLOW_ID to (workflowId?.toString() ?: "unknown"),
    WORKFLOW_NAME to (workflowName?.toString() ?: "unknown"),
    WORKFLOW_VERSION to (workflowVersion?.toString() ?: "unknown"),
    *additional.entries.map { it.key to it.value }.toTypedArray(),
    block = block
)

/* -------------------------------------------------------------------------------------------------
 * Suspending helpers (coroutine-friendly)
 * ------------------------------------------------------------------------------------------------*/

/**
 * Runs [block] inside a coroutine with workflow logging context propagated across suspension.
 * This does NOT overwrite the entire MDC: it merges the provided keys into the current MDC
 * on each thread the coroutine runs on, and restores the previous MDC automatically.
 */
suspend inline fun <T> withSuspendLoggingContext(
    workflowId: WorkflowId? = null,
    workflowName: WorkflowName? = null,
    workflowVersion: WorkflowVersion? = null,
    additional: Map<String, String?> = emptyMap(),
    crossinline block: suspend () -> T
): T {
    val element = WorkflowLoggingContext(
        workflowId = workflowId?.toString() ?: "unknow",
        workflowName = workflowName?.toString() ?: "unknow",
        workflowVersion = workflowVersion?.toString() ?: "unknow",
        additionalContext = additional
    )
    return withContext(element) { block() }
}
