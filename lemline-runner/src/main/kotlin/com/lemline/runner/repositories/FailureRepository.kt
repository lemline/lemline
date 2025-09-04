// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.IDV7
import jakarta.inject.Inject
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val FAILURE_TABLE = "lemline_failures"

/**
 * Repository class for managing `FailureModel` entities.
 *
 * This class extends `WithInstanceRepository` and provides additional functionalities specific to
 * storing and retrieving failure-related data. It is used to persist details about failures, including
 * error messages, reasons, error class, and stack traces, while associating them with a workflow instance
 * through the `InstanceMessage` information.
 *
 * The `FailureRepository` is abstract and designed to be implemented by concrete repositories.
 *
 * Key Features:
 * - Extends functionality from `WithInstanceRepository`, inheriting its instance-related mapping
 *   and utilities.
 * - Adds prepared statement mappings to store and retrieve failure-specific data from a database.
 *
 * Entity:
 * - Handles entities of type `FailureModel`, which includes the following failure-specific fields:
 *   - `message`: A descriptive message about the failure.
 *   - `reason`: The reason for the failure.
 *   - `errorClass`: The fully qualified class name of the error.
 *   - `errorMessage`: The error message, if available.
 *   - `errorStackTrace`: The stack trace of the error as a single string.
 *
 * Prepared Statement Map:
 * - Defines mappings for failure-specific fields to enable database persistence and retrieval:
 *   - `MESSAGE_COLUMN`: Maps the failure message.
 *   - `REASON_COLUMN`: Maps the failure reason.
 *   - `ERROR_CLASS_COLUMN`: Maps the error class name.
 *   - `ERROR_MESSAGE_COLUMN`: Maps the error message.
 *   - `ERROR_STACKTRACE_COLUMN`: Maps the error stack trace.
 *
 * Notes:
 * - This class is marked with `@ExperimentalTime` to indicate its use of experimental Kotlin time-related APIs.
 * - The `FailureModel` class provides factory methods to create instances directly from exceptions.
 */
@jakarta.enterprise.context.ApplicationScoped
@ExperimentalTime
class FailureRepository : WithInstanceRepository<FailureModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = FAILURE_TABLE

    companion object {
        internal const val PAYLOAD_COLUMN = "payload"
        internal const val REASON_COLUMN = "reason"

        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    override val prepareStatementMap: Map<String, (PreparedStatement, FailureModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            PAYLOAD_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.payload)
            },
            REASON_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.reason)
            },
            ERROR_CLASS_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.errorClass)
            },
            ERROR_MESSAGE_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.errorMessage)
            },
            ERROR_STACKTRACE_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.errorStackTrace)
            }
        )
    }

    override fun createModel(rs: ResultSet) = FailureModel(
        id = IDV7(getUuid(rs, ID_COLUMN)),
        instanceMessage = rs.getInstanceMessage(),
        reason = rs.getString(REASON_COLUMN),
        payload = rs.getString(PAYLOAD_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN)
    )
}
