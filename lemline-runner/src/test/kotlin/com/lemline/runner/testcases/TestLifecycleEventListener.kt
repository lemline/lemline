// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.lifecycleevents.LifecycleEventData
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.core.lifecycleevents.LifecycleEventType
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.reactive.messaging.Incoming

/**
 * A listener for capturing and managing lifecycle events during tests.
 *
 * This class listens to events published to the "lifecycleevents-in" topic and provides
 * mechanisms to capture, query, and wait for specific events based on workflows and event types.
 * It is primarily intended for use in test environments to verify lifecycle-related behavior.
 *
 * Annotated with `@ExperimentalTime`, `@Startup`, and `@ApplicationScoped` to enable proper initialization
 * and scope handling.
 */
@ExperimentalTime
@Startup
@ApplicationScoped
class TestLifecycleEventListener {
    private val logger = logger()

    private val json = Json { encodeDefaults = true }
    private val cloudEventFormat = JsonFormat()

    private val _events = ConcurrentLinkedQueue<CloudEvent>()

    /**
     * A thread-safe mapping between workflow IDs and channels used for awaiting specific lifecycle events.
     *
     * This data structure allows suspending functions to wait for particular events related to workflows.
     * Each entry maps a workflow ID (as a String) to a [Channel] of [CloudEvent]s.
     *
     * Typically used to coordinate asynchronous event processing workflows for testing lifecycle scenarios.
     */
    private val waiters = ConcurrentHashMap<String, Channel<CloudEvent>>()

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

    /**
     * Incoming handler for lifecycle events from the broker.
     */
    @Incoming("lifecycleevents-in")
    fun onLifecycleEvent(payload: String) {
        try {
            val cloudEvent = cloudEventFormat.deserialize(payload.toByteArray())
            val workflowId = cloudEvent.getExtension("lemlineworkflowid")?.toString()

            logger.debug {
                "Test captured lifecycle event from broker: id=${cloudEvent.id}, " +
                    "type=${cloudEvent.type}, workflowId=$workflowId"
            }

            _events.add(cloudEvent)

            // Notify any waiters for this workflow
            workflowId?.let { id ->
                waiters[id]?.trySend(cloudEvent)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to deserialize lifecycle event: ${payload.take(200)}" }
        }
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
     * @param timeout Maximum time to wait for completion
     * @return [WorkflowTestResult.Success] with output, or [WorkflowTestResult.Failure] with error
     * @throws TimeoutException if the workflow doesn't complete within the timeout
     */
    suspend fun awaitWorkflowResult(
        workflowId: WorkflowId,
        timeout: Duration = 30.seconds
    ): WorkflowTestResult {
        val workflowIdStr = workflowId.toString()
        val channel = Channel<CloudEvent>(Channel.UNLIMITED)
        waiters[workflowIdStr] = channel

        try {
            val startTime = System.currentTimeMillis()
            val timeoutMillis = timeout.inWholeMilliseconds

            // Check existing events first
            checkExistingEvents(workflowId)?.let { return it }

            // Wait for new events
            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                val remainingMs = timeoutMillis - (System.currentTimeMillis() - startTime)
                val event = withTimeoutOrNull(remainingMs) {
                    channel.receive()
                }

                if (event != null) {
                    toWorkflowResult(event, workflowId)?.let { return it }
                }

                // Also check events that might have arrived
                checkExistingEvents(workflowId)?.let { return it }
            }

            throw TimeoutException(
                "Workflow $workflowId did not complete within $timeout. " +
                    "Captured events: ${summary()}"
            )
        } finally {
            waiters.remove(workflowIdStr)
            channel.close()
        }
    }

    private fun checkExistingEvents(workflowId: WorkflowId): WorkflowTestResult? {
        // Check for workflow.completed event
        val completedEvent = findByWorkflowIdAndType(workflowId, LifecycleEventType.WORKFLOW_COMPLETED)
            .firstOrNull()
        if (completedEvent != null) {
            return toWorkflowResult(completedEvent, workflowId)
        }

        // Check for workflow.faulted event
        val faultedEvent = findByWorkflowIdAndType(workflowId, LifecycleEventType.WORKFLOW_FAULTED)
            .firstOrNull()
        if (faultedEvent != null) {
            return toWorkflowResult(faultedEvent, workflowId)
        }

        return null
    }

    private fun toWorkflowResult(event: CloudEvent, workflowId: WorkflowId): WorkflowTestResult? {
        return when (event.type) {
            LifecycleEventType.WORKFLOW_COMPLETED.type -> {
                val eventData = event.data?.toBytes()?.let { bytes ->
                    json.decodeFromString<LifecycleEventData>(String(bytes, Charsets.UTF_8))
                }
                val data = eventData as? LifecycleEventData.WorkflowCompletedData
                WorkflowTestResult.Success(
                    output = data?.output ?: error("WorkflowCompletedData missing output")
                )
            }

            LifecycleEventType.WORKFLOW_FAULTED.type -> {
                val jsonStr = event.data?.toBytes()?.let { String(it, Charsets.UTF_8) }
                val eventData = try {
                    jsonStr?.let { json.decodeFromString<LifecycleEventData>(it) }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to deserialize workflow.faulted event data: $jsonStr" }
                    null
                }
                val data = eventData as? LifecycleEventData.WorkflowFaultedData
                val errorMsg = data?.error?.let { err ->
                    listOfNotNull(err.type, err.title).joinToString(": ").ifEmpty { "Unknown error" }
                } ?: "Unknown error (data=$eventData, json=$jsonStr)"
                WorkflowTestResult.Failure(
                    error = errorMsg,
                    exception = null
                )
            }

            else -> null
        }
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
