// SPDX-License-Identifier: BUSL-1.1
/**
 * WorkflowStateHooks Contract (REVISED)
 *
 * This file defines the contract for event-based synchronization in tests.
 *
 * DESIGN DECISION: Use CloudEvents from LifecycleEventHookImpl
 *
 * Instead of creating a separate test-specific hook, we leverage the existing
 * lifecycle event infrastructure:
 *
 * 1. LifecycleEventHook (interface in lemline-core) - called by orchestrator
 * 2. LifecycleEventHookImpl (in lemline-runner) - builds and emits CloudEvents
 * 3. CloudEventCapture (in lemline-testing) - subscribes and captures events
 *
 * This approach:
 * - Tests verify the real event emission path
 * - No test-specific hooks to maintain
 * - CloudEventCapture handles both capture AND synchronization
 * - Tests see exactly what production users see
 *
 * @feature 001-testing-architecture
 */
package com.lemline.testing

import com.lemline.common.values.WorkflowId
import com.lemline.core.nodes.NodePosition
import com.lemline.core.testcases.WorkflowTestResult
import io.cloudevents.CloudEvent
import kotlinx.serialization.json.JsonElement
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
 *
 * ## Architecture
 *
 * ```
 * StepByStepOrchestrator
 *         │
 *         │ calls
 *         ▼
 * LifecycleEventHook (interface)
 *         │
 *         │ implemented by
 *         ▼
 * LifecycleEventHookImpl (runner)
 *         │
 *         │ emits CloudEvents to
 *         ▼
 * Messaging Channel
 *         │
 *         │ subscribed by
 *         ▼
 * CloudEventCapture ◄──── WorkflowStateHooks (wraps for convenience)
 * ```
 *
 * ## Usage
 *
 * ### Simple: Wait for workflow completion
 * ```kotlin
 * val result = executor.execute(yaml, input)
 * // Result is returned only after workflow completes - no explicit waiting needed
 * ```
 *
 * ### Advanced: Synchronize with specific tasks
 * ```kotlin
 * val hooks = executor.getStateHooks()
 *
 * // Start workflow (non-blocking)
 * val workflowId = executor.startWorkflowAsync(yaml, input)
 *
 * // Wait for a specific task to complete (waits for CloudEvent)
 * val taskOutput = hooks.awaitTaskCompleted(
 *     workflowId,
 *     NodePosition.parse("[0, \"myTask\"]"),
 *     timeout = 10.seconds
 * )
 *
 * // Continue waiting for workflow completion
 * val result = hooks.awaitCompletion(workflowId, 20.seconds)
 * ```
 *
 * ### Listen task synchronization
 * ```kotlin
 * // Start workflow that will block at listen task
 * val workflowId = executor.startWorkflowAsync(yaml, input)
 *
 * // Wait until listen task is ready (via task.started CloudEvent)
 * hooks.awaitTaskStarted(
 *     workflowId,
 *     NodePosition.parse("[0, \"waitForPayment\"]"),
 *     timeout = 5.seconds
 * )
 *
 * // Now safely deliver the event
 * executor.getEventDelivery().deliver(paymentEvent, workflowId)
 *
 * // Wait for completion
 * val result = hooks.awaitCompletion(workflowId, 10.seconds)
 * ```
 *
 * ## Thread Safety
 *
 * WorkflowStateHooks is thread-safe. Multiple coroutines can await
 * different events concurrently.
 */
interface WorkflowStateHooks {

    // ─────────────────────────────────────────────────────────────────────────
    // Await Methods (CloudEvent-based waiting)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wait for a workflow to complete (success or failure).
     *
     * Internally waits for either:
     * - `com.lemline.workflow.completed` CloudEvent
     * - `com.lemline.workflow.faulted` CloudEvent
     *
     * @param workflowId The workflow to wait for
     * @param timeout Maximum wait duration
     * @return WorkflowTestResult (Success or Failure)
     * @throws kotlinx.coroutines.TimeoutCancellationException if timeout exceeded
     */
    suspend fun awaitCompletion(workflowId: WorkflowId, timeout: Duration): WorkflowTestResult

    /**
     * Wait for a specific task to complete.
     *
     * Internally waits for `com.lemline.task.completed` CloudEvent
     * matching the workflow ID and task position.
     *
     * @param workflowId The workflow instance
     * @param taskPosition Position of the task to wait for
     * @param timeout Maximum wait duration
     * @return Task output (extracted from CloudEvent data)
     * @throws kotlinx.coroutines.TimeoutCancellationException if timeout exceeded
     * @throws TaskFailedException if task.faulted event received instead
     */
    suspend fun awaitTaskCompleted(
        workflowId: WorkflowId,
        taskPosition: NodePosition,
        timeout: Duration
    ): JsonElement

    /**
     * Wait for a task to start.
     *
     * Internally waits for `com.lemline.task.started` CloudEvent.
     * Useful for synchronizing event delivery to listen tasks.
     *
     * @param workflowId The workflow instance
     * @param taskPosition Position of the task
     * @param timeout Maximum wait duration
     */
    suspend fun awaitTaskStarted(
        workflowId: WorkflowId,
        taskPosition: NodePosition,
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
        workflowId: WorkflowId,
        eventTypes: Set<String>,
        timeout: Duration
    ): CloudEvent

    // ─────────────────────────────────────────────────────────────────────────
    // Direct CloudEvent Access
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get the underlying CloudEventCapture for direct event access.
     *
     * Use this for advanced filtering or custom event patterns.
     */
    fun getEventCapture(): CloudEventCapture

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clear all tracked state and pending waiters.
     *
     * Called automatically between test executions.
     */
    fun reset()
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting Types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Exception thrown when waiting for task completion but task fails.
 */
class TaskFailedException(
    val taskPosition: NodePosition,
    val errorType: String,
    val errorMessage: String
) : RuntimeException("Task at $taskPosition failed: $errorMessage")
