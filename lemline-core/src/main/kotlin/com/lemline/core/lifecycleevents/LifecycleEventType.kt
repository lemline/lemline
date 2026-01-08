// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.lifecycleevents

/**
 * Serverless Workflow lifecycle event types.
 *
 * These follow the Serverless Workflow specification for lifecycle events.
 * Each event type corresponds to a specific state transition in workflow or task execution.
 *
 * @see <a href="https://serverlessworkflow.io/">Serverless Workflow Specification</a>
 */
enum class LifecycleEventType(val type: String) {
    /** Workflow instance has been created (before execution starts) */
    WORKFLOW_CREATED("io.serverlessworkflow.workflow.created.v1"),

    /** Workflow has started execution */
    WORKFLOW_STARTED("io.serverlessworkflow.workflow.started.v1"),

    /** Workflow has completed successfully */
    WORKFLOW_COMPLETED("io.serverlessworkflow.workflow.completed.v1"),

    /** Workflow has faulted with an unhandled error */
    WORKFLOW_FAULTED("io.serverlessworkflow.workflow.faulted.v1"),

    /** Task has been created/scheduled (queued for execution) */
    TASK_CREATED("io.serverlessworkflow.task.created.v1"),

    /** Task has started execution */
    TASK_STARTED("io.serverlessworkflow.task.started.v1"),

    /** Task has completed successfully */
    TASK_COMPLETED("io.serverlessworkflow.task.completed.v1"),

    /** Task has faulted with an error */
    TASK_FAULTED("io.serverlessworkflow.task.faulted.v1"),

    /** Task retry has been initiated */
    TASK_RETRIED("io.serverlessworkflow.task.retried.v1");

    companion object {
        /** All workflow-level event types */
        val workflowEvents = listOf(WORKFLOW_CREATED, WORKFLOW_STARTED, WORKFLOW_COMPLETED, WORKFLOW_FAULTED)

        /** All task-level event types */
        val taskEvents = listOf(TASK_CREATED, TASK_STARTED, TASK_COMPLETED, TASK_FAULTED, TASK_RETRIED)
    }
}
