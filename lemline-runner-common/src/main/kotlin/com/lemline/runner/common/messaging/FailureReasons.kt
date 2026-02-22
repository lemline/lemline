// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import com.lemline.core.errors.InternalException
import java.io.IOException
import java.sql.SQLException

object FailureReasons {
    const val OUTBOX_FAILURE = "outbox_failure"
    const val DESERIALIZATION_FAILURE = "deserialization_failure"
    const val SERIALIZATION_FAILURE = "serialization_failure"
    const val DEFINITION_MISSING = "definition_missing"
    const val SECRETS_RETRIEVAL_FAILURE = "secrets_retrieval_failure"
    const val WORKFLOW_EXECUTION_FAILURE = "workflow_execution_failure"
    const val MESSAGE_EMISSION_FAILURE = "message_emission_failure"
    const val DATABASE_FAILURE = "database_failure"
    const val IO_FAILURE = "io_failure"
    const val ILLEGAL_STATE_FAILURE = "illegal_state_failure"
    const val GENERAL_PROCESSING_FAILURE = "general_processing_failure"

    /**
     * Determines a low-cardinality failure reason from an exception for use in metrics.
     * This is crucial for creating actionable alerts and dashboards without overwhelming
     * the metrics backend.
     */
    fun getFailureReason(e: Throwable): String = when (e) {
        // Domain-specific errors from the workflow engine
        is InternalException -> e.error.type.lowercase()

        // --- Database & Persistence Errors ---
        is SQLException -> DATABASE_FAILURE

        // --- I/O and Network Errors ---
        is IOException -> IO_FAILURE

        // --- Application State Errors ---
        is IllegalStateException -> ILLEGAL_STATE_FAILURE

        // --- Fallback for any other uncategorized exception ---
        else -> GENERAL_PROCESSING_FAILURE
    }
}
