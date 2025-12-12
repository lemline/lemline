// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import kotlinx.serialization.json.JsonElement

/**
 * Result of workflow execution in E2E tests.
 */
data class WorkflowResult(
    /**
     * Final workflow status.
     */
    val status: WorkflowStatus,

    /**
     * Workflow output (if completed successfully).
     */
    val output: JsonElement?,

    /**
     * Error details (if faulted).
     */
    val error: WorkflowError?,

    /**
     * Workflow instance ID.
     */
    val workflowId: String
)

/**
 * Workflow execution status.
 */
enum class WorkflowStatus {
    /**
     * Workflow completed successfully.
     */
    COMPLETED,

    /**
     * Workflow failed with an error.
     */
    FAULTED,

    /**
     * Workflow was cancelled.
     */
    CANCELLED,

    /**
     * Workflow timed out.
     */
    TIMEOUT
}

/**
 * Workflow error details.
 */
data class WorkflowError(
    /**
     * Error type (e.g., "ValidationError", "ActivityError").
     */
    val type: String,

    /**
     * Short error title.
     */
    val title: String,

    /**
     * Detailed error message.
     */
    val detail: String?
)
