// SPDX-License-Identifier: BUSL-1.1
/**
 * CloudEvent Capture and Delivery Contracts
 *
 * This file defines contracts for CloudEvent verification and delivery in tests.
 * Implementation will be in lemline-testing module.
 *
 * @feature 001-testing-architecture
 */
package com.lemline.testing

import com.lemline.common.values.WorkflowId
import io.cloudevents.CloudEvent
import kotlinx.serialization.json.JsonElement
import java.net.URI
import kotlin.time.Duration

// ─────────────────────────────────────────────────────────────────────────────
// CloudEventCapture
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Captures and queries CloudEvents emitted during workflow execution.
 *
 * Use this to verify:
 * - Lifecycle events (WorkflowStarted, TaskCompleted, etc.)
 * - Custom events emitted via `emit` tasks
 * - Event ordering and attributes
 *
 * ## Test Isolation
 *
 * CloudEventCapture is **scoped to specific workflow IDs** to ensure test isolation.
 * When multiple tests run concurrently, each test's capture only sees events from
 * its own workflow(s). Events from other tests are automatically filtered out.
 *
 * The scoping is established when the capture is created:
 * - `executor.execute()` creates a capture scoped to the executed workflow
 * - `executor.startWorkflowAsync()` returns a capture scoped to that workflow
 * - Additional workflows (dependencies) are automatically included in scope
 *
 * ## Usage
 *
 * ```kotlin
 * val result = executor.execute(workflowYaml, input)
 * val capture = executor.getEventCapture()
 *
 * // Only sees events from the workflow that was just executed
 * val completedEvents = capture.filterByType("com.lemline.workflow.completed")
 * completedEvents.size shouldBe 1
 *
 * // Verify custom events (only from this test's workflow)
 * val customEvents = capture.customEvents()
 * customEvents.size shouldBe 2
 * customEvents[0].type shouldBe "com.myapp.order.created"
 * ```
 *
 * ## Thread Safety
 *
 * CloudEventCapture is thread-safe and captures events in order of emission.
 */
interface CloudEventCapture {

    // ─────────────────────────────────────────────────────────────────────────
    // Event Access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get all captured events in order of emission.
     *
     * @return Immutable list of CloudEvents
     */
    fun events(): List<CloudEvent>

    /**
     * Get the number of captured events.
     */
    fun size(): Int = events().size

    /**
     * Check if any events were captured.
     */
    fun isEmpty(): Boolean = events().isEmpty()

    /**
     * Check if events were captured.
     */
    fun isNotEmpty(): Boolean = events().isNotEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // Filtering
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Filter events by CloudEvent type.
     *
     * @param type CloudEvent type (e.g., "com.lemline.task.completed")
     * @return Matching events
     */
    fun filterByType(type: String): List<CloudEvent>

    /**
     * Filter events by source URI.
     *
     * @param source CloudEvent source (e.g., "/lemline/workflows/abc123")
     * @return Matching events
     */
    fun filterBySource(source: URI): List<CloudEvent>

    /**
     * Filter events by source prefix.
     *
     * @param sourcePrefix Source URI prefix (e.g., "/lemline/workflows/")
     * @return Matching events
     */
    fun filterBySourcePrefix(sourcePrefix: String): List<CloudEvent>

    /**
     * Find events matching a predicate.
     *
     * @param predicate Filter function
     * @return Matching events
     */
    fun find(predicate: (CloudEvent) -> Boolean): List<CloudEvent>

    /**
     * Find the first event matching a predicate.
     *
     * @param predicate Filter function
     * @return First matching event or null
     */
    fun findFirst(predicate: (CloudEvent) -> Boolean): CloudEvent? =
        find(predicate).firstOrNull()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle vs Custom Events
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get Lemline lifecycle events only.
     *
     * Lifecycle events include:
     * - com.lemline.workflow.created
     * - com.lemline.workflow.started
     * - com.lemline.workflow.completed
     * - com.lemline.workflow.faulted
     * - com.lemline.task.created
     * - com.lemline.task.started
     * - com.lemline.task.completed
     * - com.lemline.task.faulted
     * - com.lemline.task.retried
     *
     * @return Lifecycle events in order
     */
    fun lifecycleEvents(): List<CloudEvent>

    /**
     * Get custom events only (emitted via emit task).
     *
     * Custom events are any events NOT matching the com.lemline.* prefix.
     *
     * @return Custom events in order
     */
    fun customEvents(): List<CloudEvent>

    // ─────────────────────────────────────────────────────────────────────────
    // Async Waiting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wait for an event matching the predicate to be captured.
     *
     * Use this when you need to wait for an event that will be emitted
     * during async workflow execution.
     *
     * @param timeout Maximum wait duration
     * @param predicate Filter function
     * @return Matching event or null if timeout
     */
    suspend fun awaitEvent(
        timeout: Duration,
        predicate: (CloudEvent) -> Boolean
    ): CloudEvent?

    /**
     * Wait for an event of a specific type.
     *
     * @param timeout Maximum wait duration
     * @param type CloudEvent type to wait for
     * @return Matching event or null if timeout
     */
    suspend fun awaitEventOfType(
        timeout: Duration,
        type: String
    ): CloudEvent? = awaitEvent(timeout) { it.type == type }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoping (Test Isolation)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get the workflow IDs this capture is scoped to.
     *
     * Only events from these workflows are captured and returned by query methods.
     *
     * @return Set of workflow IDs in scope
     */
    fun scopedWorkflowIds(): Set<WorkflowId>

    /**
     * Add a workflow ID to the capture scope.
     *
     * Use this when starting additional workflows (e.g., child workflows)
     * that should be included in event capture.
     *
     * @param workflowId Workflow ID to add to scope
     */
    fun addToScope(workflowId: WorkflowId)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clear all captured events.
     *
     * Called automatically between test executions.
     */
    fun clear()
}

// ─────────────────────────────────────────────────────────────────────────────
// CloudEventDelivery
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Programmatically delivers CloudEvents to trigger listen tasks.
 *
 * Use this when testing workflows with `listen` tasks that wait for
 * external events. The delivery mechanism ensures events reach the
 * correct waiting workflow instance.
 *
 * ## Usage
 *
 * ```kotlin
 * // Start workflow in background (will block at listen task)
 * val workflowId = executor.startWorkflow(yaml, input)
 *
 * // Wait for listen task to be ready
 * executor.getStateHooks().awaitListenStarted(workflowId, listenPosition, 5.seconds)
 *
 * // Deliver event to trigger the listen task
 * executor.getEventDelivery().deliver(
 *     type = "com.myapp.payment.completed",
 *     source = "/payments/123",
 *     data = buildJsonObject { put("amount", 99.99) },
 *     targetWorkflowId = workflowId
 * )
 *
 * // Wait for workflow completion
 * val result = executor.awaitCompletion(workflowId, 10.seconds)
 * ```
 *
 * ## Thread Safety
 *
 * CloudEventDelivery is thread-safe and supports concurrent event delivery.
 */
interface CloudEventDelivery {

    /**
     * Deliver a CloudEvent to trigger a waiting listen task.
     *
     * @param event The CloudEvent to deliver
     * @param targetWorkflowId Optional: target specific workflow instance.
     *                         If null, event is delivered to all matching listeners.
     */
    suspend fun deliver(event: CloudEvent, targetWorkflowId: WorkflowId? = null)

    /**
     * Deliver a CloudEvent built from parameters.
     *
     * This is a convenience method that constructs a CloudEvent from
     * individual attributes.
     *
     * @param type CloudEvent type (e.g., "com.myapp.order.created")
     * @param source CloudEvent source URI (e.g., "/orders/123")
     * @param data Event payload as JSON
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliver(
        type: String,
        source: String,
        data: JsonElement,
        targetWorkflowId: WorkflowId? = null
    )

    /**
     * Deliver multiple events at once.
     *
     * Use this for testing listen tasks with "all" strategy that
     * require multiple events to complete.
     *
     * @param events Events to deliver
     * @param targetWorkflowId Optional: target specific workflow instance
     */
    suspend fun deliverAll(events: List<CloudEvent>, targetWorkflowId: WorkflowId? = null)

    /**
     * Build a CloudEvent using a DSL.
     *
     * ```kotlin
     * val event = delivery.buildEvent {
     *     type = "com.myapp.order.created"
     *     source = "/orders/123"
     *     data = buildJsonObject { put("orderId", "123") }
     * }
     * delivery.deliver(event)
     * ```
     */
    fun buildEvent(block: CloudEventBuilder.() -> Unit): CloudEvent
}

/**
 * Builder for constructing CloudEvents in tests.
 */
interface CloudEventBuilder {
    /**
     * CloudEvent type (required).
     */
    var type: String

    /**
     * CloudEvent source URI (required).
     */
    var source: String

    /**
     * Event payload as JSON (optional).
     */
    var data: JsonElement?

    /**
     * Event subject (optional).
     */
    var subject: String?

    /**
     * Data content type (default: application/json).
     */
    var dataContentType: String?
}

// ─────────────────────────────────────────────────────────────────────────────
// CloudEventDispatcher (Test Isolation)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Centralized dispatcher that routes CloudEvents to appropriate test captures.
 *
 * This singleton subscribes once to the lifecycle events channel and routes
 * incoming events to CloudEventCapture instances based on workflow ID.
 *
 * ## Architecture
 *
 * ```
 * Messaging Channel (lifecycle events)
 *         │
 *         │ single subscription
 *         ▼
 * CloudEventDispatcher.dispatch(event)
 *         │
 *         │ extract workflowId, lookup in registry
 *         ▼
 * ┌───────┴───────┐
 * │               │
 * ▼               ▼
 * Capture A     Capture B    (only captures registered for this workflowId)
 * (Test 1)      (Test 2)
 * ```
 *
 * ## Test Isolation
 *
 * Each test registers its CloudEventCapture for the workflow IDs it cares about.
 * The dispatcher routes events only to registered captures, ensuring tests
 * don't see events from other concurrent tests.
 *
 * ## Thread Safety
 *
 * CloudEventDispatcher is thread-safe and supports concurrent registration/dispatch.
 */
interface CloudEventDispatcher {

    /**
     * Register a capture to receive events for a workflow.
     *
     * Called automatically when CloudEventCapture is created or when
     * addToScope() adds a new workflow ID.
     *
     * @param workflowId Workflow ID to receive events for
     * @param capture The capture instance to route events to
     */
    fun register(workflowId: WorkflowId, capture: CloudEventCapture)

    /**
     * Unregister a capture from all workflows.
     *
     * Called when a test completes to clean up registrations.
     *
     * @param capture The capture to unregister
     */
    fun unregister(capture: CloudEventCapture)

    /**
     * Dispatch an incoming CloudEvent to registered captures.
     *
     * Extracts workflow ID from event source and routes to all
     * captures registered for that workflow.
     *
     * @param event The CloudEvent to dispatch
     */
    fun dispatch(event: CloudEvent)
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle Event Types (Constants)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lemline CloudEvent type constants for lifecycle events.
 */
object LemlineEventTypes {
    // Workflow lifecycle
    const val WORKFLOW_CREATED = "com.lemline.workflow.created"
    const val WORKFLOW_STARTED = "com.lemline.workflow.started"
    const val WORKFLOW_COMPLETED = "com.lemline.workflow.completed"
    const val WORKFLOW_FAULTED = "com.lemline.workflow.faulted"

    // Task lifecycle
    const val TASK_CREATED = "com.lemline.task.created"
    const val TASK_STARTED = "com.lemline.task.started"
    const val TASK_COMPLETED = "com.lemline.task.completed"
    const val TASK_FAULTED = "com.lemline.task.faulted"
    const val TASK_RETRIED = "com.lemline.task.retried"

    /**
     * Check if a type is a Lemline lifecycle event.
     */
    fun isLifecycleEvent(type: String): Boolean =
        type.startsWith("com.lemline.")
}
