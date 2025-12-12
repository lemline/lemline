// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

/**
 * Lemline CloudEvent type constants for lifecycle events.
 *
 * These are the standard event types emitted by LifecycleEventHookImpl
 * during workflow execution.
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
     * All lifecycle event types.
     */
    val ALL_LIFECYCLE_TYPES = setOf(
        WORKFLOW_CREATED,
        WORKFLOW_STARTED,
        WORKFLOW_COMPLETED,
        WORKFLOW_FAULTED,
        TASK_CREATED,
        TASK_STARTED,
        TASK_COMPLETED,
        TASK_FAULTED,
        TASK_RETRIED
    )

    /**
     * Workflow completion event types (terminal states).
     */
    val WORKFLOW_TERMINAL_TYPES = setOf(
        WORKFLOW_COMPLETED,
        WORKFLOW_FAULTED
    )

    /**
     * Task completion event types (terminal states).
     */
    val TASK_TERMINAL_TYPES = setOf(
        TASK_COMPLETED,
        TASK_FAULTED
    )

    /**
     * Check if a type is a Lemline lifecycle event.
     */
    fun isLifecycleEvent(type: String): Boolean =
        type.startsWith("com.lemline.")

    /**
     * Check if a type is a workflow terminal event.
     */
    fun isWorkflowTerminal(type: String): Boolean =
        type in WORKFLOW_TERMINAL_TYPES

    /**
     * Check if a type is a task terminal event.
     */
    fun isTaskTerminal(type: String): Boolean =
        type in TASK_TERMINAL_TYPES
}
