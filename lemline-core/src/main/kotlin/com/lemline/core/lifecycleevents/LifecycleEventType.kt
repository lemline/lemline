// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

/**
 * Open Workflow lifecycle event types.
 *
 * These follow the current Open Workflow Specification lifecycle event namespace.
 * Each event type corresponds to a specific state transition in workflow or task execution.
 *
 * @see <a href="https://github.com/open-workflow-specification/specification/blob/main/dsl.md#workflow-lifecycle-events">Workflow Lifecycle Events</a>
 */
enum class LifecycleEventType(val type: String) {
    /** Workflow instance has been created (before execution starts) */
    WORKFLOW_CREATED("org.open-workflow-specification.workflow.created.v1"),

    /** Workflow has started execution */
    WORKFLOW_STARTED("org.open-workflow-specification.workflow.started.v1"),

    /** A workflow has begun waiting for correlated events */
    WORKFLOW_CORRELATION_STARTED("org.open-workflow-specification.workflow.correlation-started.v1"),

    /** A workflow has received the events required by a correlated wait */
    WORKFLOW_CORRELATION_COMPLETED("org.open-workflow-specification.workflow.correlation-completed.v1"),

    /** Workflow has completed successfully */
    WORKFLOW_COMPLETED("org.open-workflow-specification.workflow.completed.v1"),

    /** Workflow has faulted with an unhandled error */
    WORKFLOW_FAULTED("org.open-workflow-specification.workflow.faulted.v1"),

    /** Task has been created/scheduled (queued for execution) */
    TASK_CREATED("org.open-workflow-specification.task.created.v1"),

    /** Task has started execution */
    TASK_STARTED("org.open-workflow-specification.task.started.v1"),

    /** Task has completed successfully */
    TASK_COMPLETED("org.open-workflow-specification.task.completed.v1"),

    /** Task has faulted with an error */
    TASK_FAULTED("org.open-workflow-specification.task.faulted.v1"),

    /** Task retry has been initiated */
    TASK_RETRIED("org.open-workflow-specification.task.retried.v1");

    // The specification requires readers to recognize the deprecated namespace during 1.x.
    val legacyType: String get() = type.replace("org.open-workflow-specification.", "io.serverlessworkflow.")
    val acceptedTypes: List<String> get() = listOf(type, legacyType)

    companion object {
        /** All workflow-level event types */
        val workflowEvents = listOf(
            WORKFLOW_CREATED, WORKFLOW_STARTED, WORKFLOW_CORRELATION_STARTED, WORKFLOW_CORRELATION_COMPLETED,
            WORKFLOW_COMPLETED, WORKFLOW_FAULTED,
        )

        /** All task-level event types */
        val taskEvents = listOf(TASK_CREATED, TASK_STARTED, TASK_COMPLETED, TASK_FAULTED, TASK_RETRIED)
    }
}
