// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventData
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.messaging.lifecycle.LifecycleEventEmitter
import com.lemline.runner.messaging.lifecycle.LifecycleEventType
import io.cloudevents.CloudEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Test implementation of [LifecycleEventEmitter] to capture and inspect emitted lifecycle events
 * during testing. This implementation is an alternative to the production event emitter and
 * allows detailed inspection of captured events, providing utilities for querying and analyzing
 * workflow lifecycle state.
 *
 * This class is marked with `@ApplicationScoped` for use in dependency injection frameworks,
 * `@ExperimentalTime` for features relying on Kotlin's time API, and `@Alternative` and `@Priority(1)`
 * to override the default [LifecycleEventEmitter] during tests.
 */
@ExperimentalTime
@ApplicationScoped
@Alternative
@Priority(1)
class TestLifecycleEventEmitter : LifecycleEventEmitter {
    private val logger = logger()

    // Use the same Json configuration as LifecycleEventHookImpl for consistent serialization
    private val json = Json { encodeDefaults = true }

    private val _events = ConcurrentLinkedQueue<CloudEvent>()

    /**
     * All captured CloudEvents.
     */
    val events: List<CloudEvent> get() = _events.toList()

    /**
     * Clears all captured events.
     * Call this before each test to ensure isolation.
     */
    fun clear() {
        _events.clear()
    }

    override suspend fun emit(cloudEvent: CloudEvent) {
        logger.debug {
            "Test captured lifecycle event: id=${cloudEvent.id}, type=${cloudEvent.type}, " +
                "workflowId=${cloudEvent.getExtension("lemlineworkflowid")}"
        }
        _events.add(cloudEvent)
    }

    /**
     * Finds all events for a specific workflow.
     */
    fun findByWorkflowId(workflowId: WorkflowId): List<CloudEvent> {
        return events.filter {
            it.getExtension("lemlineworkflowid") == workflowId.toString()
        }
    }

    /**
     * Finds all events of a specific type.
     */
    fun findByType(type: LifecycleEventType): List<CloudEvent> {
        return events.filter { it.type == type.type }
    }

    /**
     * Finds all events of a specific type for a specific workflow.
     */
    fun findByWorkflowIdAndType(workflowId: WorkflowId, type: LifecycleEventType): List<CloudEvent> {
        return events.filter {
            it.getExtension("lemlineworkflowid") == workflowId.toString() &&
                it.type == type.type
        }
    }

    /**
     * Waits for a workflow to complete or fail by observing lifecycle CloudEvents.
     *
     * This method polls for `workflow.completed` or `workflow.faulted` events
     * and returns the appropriate [WorkflowTestResult].
     *
     * @param workflowId The workflow ID to wait for
     * @param timeout Maximum time to wait for completion (use Duration.ZERO for immediate check)
     * @return [WorkflowTestResult.Success] with output, or [WorkflowTestResult.Failure] with error
     * @throws TimeoutException if the workflow doesn't complete within the timeout
     */
    suspend fun awaitWorkflowResult(
        workflowId: WorkflowId,
        timeout: Duration = 30.seconds
    ): WorkflowTestResult {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeout.inWholeMilliseconds

        // Check at least once, even with zero timeout
        do {
            // Check for workflow.completed event
            val completedEvent = findByWorkflowIdAndType(workflowId, LifecycleEventType.WORKFLOW_COMPLETED)
                .firstOrNull()

            if (completedEvent != null) {
                val eventData = completedEvent.data?.toBytes()?.let { bytes ->
                    json.decodeFromString<LifecycleEventData>(String(bytes, Charsets.UTF_8))
                }
                val data = eventData as? LifecycleEventData.WorkflowCompletedData
                return WorkflowTestResult.Success(
                    output = data?.output ?: error("WorkflowCompletedData missing output")
                )
            }

            // Check for workflow.faulted event
            val faultedEvent = findByWorkflowIdAndType(workflowId, LifecycleEventType.WORKFLOW_FAULTED)
                .firstOrNull()

            if (faultedEvent != null) {
                val jsonStr = faultedEvent.data?.toBytes()?.let { String(it, Charsets.UTF_8) }
                val eventData = try {
                    jsonStr?.let { json.decodeFromString<LifecycleEventData>(it) }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to deserialize workflow.faulted event data: $jsonStr" }
                    null
                }
                val data = eventData as? LifecycleEventData.WorkflowFaultedData
                val errorMsg = data?.error?.let { err ->
                    // Prefer type for error matching (e.g., "runtime")
                    listOfNotNull(err.type, err.title).joinToString(": ").ifEmpty { "Unknown error" }
                } ?: "Unknown error (data=$eventData, json=$jsonStr)"
                return WorkflowTestResult.Failure(
                    error = errorMsg,
                    exception = null
                )
            }

            // Only delay if we have time remaining
            if (System.currentTimeMillis() - startTime < timeoutMillis) {
                delay(50)
            }
        } while (System.currentTimeMillis() - startTime < timeoutMillis)

        throw TimeoutException("Workflow $workflowId did not complete within $timeout")
    }

    /**
     * Returns a human-readable summary of captured events for debugging.
     */
    fun summary(): String {
        return events.joinToString("\n") { event ->
            val workflowId = event.getExtension("lemlineworkflowid")
            "${event.type} [workflowId=$workflowId, id=${event.id}]"
        }
    }
}
