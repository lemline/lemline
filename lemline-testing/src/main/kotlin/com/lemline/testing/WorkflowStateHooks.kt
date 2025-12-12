// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import io.cloudevents.CloudEvent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration

/**
 * Event-based synchronization for deterministic test assertions.
 *
 * WorkflowStateHooks wraps CloudEventCapture to provide convenient await methods
 * for common synchronization patterns. Under the hood, it waits for specific
 * CloudEvents emitted by the standard LifecycleEventHookImpl.
 *
 * ## Design Principle: No Thread.sleep()
 *
 * This interface eliminates the need for `Thread.sleep()` or fixed delays.
 * Tests wait for specific CloudEvents using coroutine suspension.
 */
interface WorkflowStateHooks {

    /**
     * Wait for a workflow to complete (success or failure).
     *
     * @param workflowId The workflow to wait for
     * @param timeout Maximum wait duration
     * @return WorkflowResult with status and output
     * @throws TimeoutCancellationException if timeout exceeded
     */
    suspend fun awaitCompletion(workflowId: String, timeout: Duration): WorkflowResult

    /**
     * Wait for a specific task to complete.
     *
     * @param workflowId The workflow instance
     * @param taskName Name of the task to wait for
     * @param timeout Maximum wait duration
     * @return Task output (extracted from CloudEvent data)
     * @throws TimeoutCancellationException if timeout exceeded
     * @throws TaskFailedException if task.faulted event received
     */
    suspend fun awaitTaskCompleted(
        workflowId: String,
        taskName: String,
        timeout: Duration
    ): JsonElement

    /**
     * Wait for a task to start.
     *
     * Useful for synchronizing event delivery to listen tasks.
     *
     * @param workflowId The workflow instance
     * @param taskName Name of the task
     * @param timeout Maximum wait duration
     */
    suspend fun awaitTaskStarted(
        workflowId: String,
        taskName: String,
        timeout: Duration
    )

    /**
     * Wait for any of multiple CloudEvent types to occur.
     *
     * @param workflowId The workflow instance
     * @param eventTypes CloudEvent types to wait for (any of)
     * @param timeout Maximum wait duration
     * @return The CloudEvent that occurred
     */
    suspend fun awaitAnyEvent(
        workflowId: String,
        eventTypes: Set<String>,
        timeout: Duration
    ): CloudEvent

    /**
     * Get the underlying CloudEventCapture for direct event access.
     */
    fun getEventCapture(): CloudEventCapture

    /**
     * Clear all tracked state.
     */
    fun reset()
}

/**
 * Default implementation of WorkflowStateHooks.
 */
class WorkflowStateHooksImpl(
    private val capture: CloudEventCapture
) : WorkflowStateHooks {

    override suspend fun awaitCompletion(workflowId: String, timeout: Duration): WorkflowResult {
        val event = withTimeout(timeout) {
            capture.awaitEvent(timeout) { event ->
                isWorkflowEvent(event, workflowId) &&
                    LemlineEventTypes.isWorkflowTerminal(event.type)
            }
        } ?: throw WorkflowTimeoutException("Timeout waiting for workflow $workflowId to complete")

        return when (event.type) {
            LemlineEventTypes.WORKFLOW_COMPLETED -> {
                val output = extractEventData(event)
                WorkflowResult(
                    status = WorkflowStatus.COMPLETED,
                    output = output,
                    error = null,
                    workflowId = workflowId
                )
            }
            LemlineEventTypes.WORKFLOW_FAULTED -> {
                val data = extractEventData(event)?.jsonObject
                WorkflowResult(
                    status = WorkflowStatus.FAULTED,
                    output = null,
                    error = WorkflowError(
                        type = data?.get("errorType")?.jsonPrimitive?.content ?: "Unknown",
                        title = data?.get("errorTitle")?.jsonPrimitive?.content ?: "Workflow failed",
                        detail = data?.get("errorDetail")?.jsonPrimitive?.content
                    ),
                    workflowId = workflowId
                )
            }
            else -> throw IllegalStateException("Unexpected event type: ${event.type}")
        }
    }

    override suspend fun awaitTaskCompleted(
        workflowId: String,
        taskName: String,
        timeout: Duration
    ): JsonElement {
        val event = withTimeout(timeout) {
            capture.awaitEvent(timeout) { event ->
                isWorkflowEvent(event, workflowId) &&
                    event.type == LemlineEventTypes.TASK_COMPLETED &&
                    extractTaskName(event) == taskName
            }
        } ?: throw WorkflowTimeoutException("Timeout waiting for task $taskName to complete")

        // Check if we got a faulted event instead
        if (event.type == LemlineEventTypes.TASK_FAULTED) {
            val data = extractEventData(event)?.jsonObject
            throw TaskFailedException(
                taskName = taskName,
                errorType = data?.get("errorType")?.jsonPrimitive?.content ?: "Unknown",
                errorMessage = data?.get("errorMessage")?.jsonPrimitive?.content ?: "Task failed"
            )
        }

        return extractEventData(event) ?: JsonObject(emptyMap())
    }

    override suspend fun awaitTaskStarted(
        workflowId: String,
        taskName: String,
        timeout: Duration
    ) {
        withTimeout(timeout) {
            capture.awaitEvent(timeout) { event ->
                isWorkflowEvent(event, workflowId) &&
                    event.type == LemlineEventTypes.TASK_STARTED &&
                    extractTaskName(event) == taskName
            }
        } ?: throw WorkflowTimeoutException("Timeout waiting for task $taskName to start")
    }

    override suspend fun awaitAnyEvent(
        workflowId: String,
        eventTypes: Set<String>,
        timeout: Duration
    ): CloudEvent {
        return withTimeout(timeout) {
            capture.awaitEvent(timeout) { event ->
                isWorkflowEvent(event, workflowId) && event.type in eventTypes
            }
        } ?: throw WorkflowTimeoutException("Timeout waiting for any of $eventTypes")
    }

    override fun getEventCapture(): CloudEventCapture = capture

    override fun reset() {
        capture.clear()
    }

    private fun isWorkflowEvent(event: CloudEvent, workflowId: String): Boolean {
        // Check source contains workflowId
        val source = event.source?.toString() ?: return false
        if (source.contains(workflowId)) return true

        // Check extension attribute
        val eventWorkflowId = event.getExtension("workflowid")?.toString()
        return eventWorkflowId == workflowId
    }

    private fun extractTaskName(event: CloudEvent): String? {
        // Try extension attribute first
        event.getExtension("taskname")?.toString()?.let { return it }

        // Try from data
        val data = extractEventData(event)?.jsonObject ?: return null
        return data["taskName"]?.jsonPrimitive?.content
    }

    private fun extractEventData(event: CloudEvent): JsonElement? {
        val bytes = event.data?.toBytes() ?: return null
        return try {
            Json.parseToJsonElement(String(bytes))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Exception thrown when waiting for task completion but task fails.
 */
class TaskFailedException(
    val taskName: String,
    val errorType: String,
    val errorMessage: String
) : RuntimeException("Task '$taskName' failed: $errorMessage")

/**
 * Exception thrown when waiting for workflow/task times out.
 */
class WorkflowTimeoutException(message: String) : RuntimeException(message)
