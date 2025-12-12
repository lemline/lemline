// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.common.logger.logger
import io.cloudevents.CloudEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized dispatcher that routes CloudEvents to appropriate test captures.
 *
 * This singleton subscribes once to the lifecycle events channel and routes
 * incoming events to CloudEventCapture instances based on workflow ID.
 *
 * ## Test Isolation
 *
 * Each test registers its CloudEventCapture for the workflow IDs it cares about.
 * The dispatcher routes events only to registered captures.
 */
class CloudEventDispatcher {

    private val logger = logger()

    // Map: workflowId -> Set<CloudEventCaptureImpl>
    private val registry = ConcurrentHashMap<String, MutableSet<CloudEventCaptureImpl>>()

    // Map: CloudEventCaptureImpl -> Set<workflowId>
    private val captureToWorkflows = ConcurrentHashMap<CloudEventCaptureImpl, MutableSet<String>>()

    /**
     * Register a capture to receive events for a workflow.
     */
    fun register(workflowId: String, capture: CloudEventCaptureImpl) {
        registry.computeIfAbsent(workflowId) { ConcurrentHashMap.newKeySet() }.add(capture)
        captureToWorkflows.computeIfAbsent(capture) { ConcurrentHashMap.newKeySet() }.add(workflowId)
        logger.debug { "Registered capture for workflow $workflowId" }
    }

    /**
     * Unregister a capture from all workflows.
     */
    fun unregister(capture: CloudEventCaptureImpl) {
        val workflowIds = captureToWorkflows.remove(capture) ?: return

        for (workflowId in workflowIds) {
            registry[workflowId]?.remove(capture)
            if (registry[workflowId]?.isEmpty() == true) {
                registry.remove(workflowId)
            }
        }

        logger.debug { "Unregistered capture from ${workflowIds.size} workflows" }
    }

    /**
     * Dispatch an incoming CloudEvent to registered captures.
     *
     * Extracts workflow ID from event source and routes to all
     * captures registered for that workflow.
     */
    fun dispatch(event: CloudEvent) {
        val workflowId = extractWorkflowId(event)
        if (workflowId == null) {
            logger.debug { "Could not extract workflowId from event: ${event.type}" }
            return
        }

        val captures = registry[workflowId]
        if (captures.isNullOrEmpty()) {
            logger.debug { "No captures registered for workflow $workflowId" }
            return
        }

        for (capture in captures) {
            capture.onEvent(event)
        }

        logger.debug { "Dispatched ${event.type} to ${captures.size} capture(s) for workflow $workflowId" }
    }

    /**
     * Get number of registered workflow IDs.
     */
    fun registeredWorkflowCount(): Int = registry.size

    /**
     * Clear all registrations (for cleanup).
     */
    fun clear() {
        registry.clear()
        captureToWorkflows.clear()
    }

    private fun extractWorkflowId(event: CloudEvent): String? {
        // Try to extract from source: /lemline/workflows/{workflowId}
        val source = event.source?.toString() ?: return null

        val workflowPrefix = "/lemline/workflows/"
        if (source.startsWith(workflowPrefix)) {
            val remaining = source.removePrefix(workflowPrefix)
            // workflowId is the next segment
            return remaining.split("/").firstOrNull()
        }

        // Try to extract from extension attribute
        return event.getExtension("workflowid")?.toString()
    }

    companion object {
        // Global singleton instance
        private val instance = CloudEventDispatcher()

        fun getInstance(): CloudEventDispatcher = instance
    }
}
