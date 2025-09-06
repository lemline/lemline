package com.lemline.runner.failures

import com.lemline.core.errors.WorkflowException
import java.io.IOException
import java.sql.SQLException
import kotlin.time.ExperimentalTime

@ExperimentalTime
object FailureReasons {
    const val OUTBOX_ERROR = "outbox_error"
    const val DESERIALISATION_ERROR = "deserialisation_error"
    const val SERIALISATION_ERROR = "serialisation_error"
    const val DEFINITION_NOT_FOUND = "definition_not_found"
    const val SECRETS_RETRIEVAL_FAILED = "secrets_retrieval_failed"
    const val WORKFLOW_INITIALIZATION_ERROR = "workflow_initialization_error"
    const val MESSAGE_EMISSION_ERROR = "message_emission_error"
    const val DATABASE_ERROR = "database_error"
    const val IO_ERROR = "io_error"
    const val ILLEGAL_STATE = "illegal_state"
    const val PROCESSING_ERROR = "processing_error"
    const val WORKFLOW_ERROR_PREFIX = "workflow_"


    /**
     * Determines a low-cardinality failure reason from an exception for use in metrics.
     * This is crucial for creating actionable alerts and dashboards without overwhelming
     * the metrics backend.
     */
    fun getFailureReason(e: Throwable): String = when (e) {
        // Domain-specific errors from the workflow engine
        is WorkflowException -> WORKFLOW_ERROR_PREFIX + e.error.type.lowercase()

        // --- Database & Persistence Errors ---
        is SQLException -> DATABASE_ERROR

        // --- I/O and Network Errors ---
        is IOException -> IO_ERROR

        // --- Application State Errors ---
        is IllegalStateException -> ILLEGAL_STATE

        // --- Fallback for any other uncategorized exception ---
        else -> PROCESSING_ERROR
    }
}

