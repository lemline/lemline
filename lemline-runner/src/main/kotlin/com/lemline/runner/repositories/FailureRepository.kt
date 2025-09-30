// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.Repository
import com.lemline.runner.repositories.capabilities.ID_COLUMN
import com.lemline.runner.repositories.capabilities.IdCapabilities
import com.lemline.runner.repositories.capabilities.IdCapable
import com.lemline.runner.repositories.capabilities.InfoCapabilities
import com.lemline.runner.repositories.capabilities.OptionalInfoCapable
import com.lemline.runner.repositories.capabilities.OptionalStateCapable
import com.lemline.runner.repositories.capabilities.StateCapabilities
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class FailureRepository : Repository<FailureModel>(),
    IdCapabilities<FailureModel>,
    InfoCapabilities<FailureModel>,
    StateCapabilities<FailureModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = FAILURE_TABLE

    companion object {
        internal const val PAYLOAD_COLUMN = "payload"
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    val idCapabilities by lazy { IdCapable(this) }
    val infoCapabilities by lazy { OptionalInfoCapable(this) }
    val stateCapabilities by lazy { OptionalStateCapable(this) }

    private val failureMapping: Map<String, (PreparedStatement, FailureModel, Int) -> Unit> by lazy {
        mapOf(
            PAYLOAD_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.payload)
            },
            ERROR_REASON_COLUMN to { stmt: PreparedStatement, entity: FailureModel, idx: Int ->
                stmt.setString(idx, entity.errorReason)
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
    override val prepareStatementMap by lazy {
        idCapabilities.mapping + infoCapabilities.mapping + stateCapabilities.mapping + failureMapping
    }

    override val keyColumns = listOf(ID_COLUMN)

    private val ResultSet.id get() = with(idCapabilities) { this@id.id }
    private val ResultSet.nodePosition get() = with(stateCapabilities) { this@nodePosition.nodePosition }
    private val ResultSet.nodeStates get() = with(stateCapabilities) { this@nodeStates.nodeStates }
    private val ResultSet.workflowInfo get() = with(infoCapabilities) { this@workflowInfo.workflowInfo }
    private val ResultSet.parentId get() = with(stateCapabilities) { this@parentId.parentId }

    private val ResultSet.instanceMessage
        get(): InstanceMessage? = workflowInfo?.let {
            InstanceMessage(
                workflowInfo = it,
                workflowState = WorkflowState(
                    currentPosition = requireNotNull(nodePosition) { "NodePosition cannot be null" },
                    currentStates = requireNotNull(nodeStates) { "NodeStates cannot be null" },
                ),
                parentId = parentId
            )
        }

    override fun createModel(rs: ResultSet) = FailureModel(
        id = rs.id,
        instanceMessage = rs.instanceMessage,
        payload = rs.getString(PAYLOAD_COLUMN),
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN)
    )

    // ID Operations
    override suspend fun findById(id: IDV7, connection: Connection?): FailureModel? =
        idCapabilities.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idCapabilities.deleteById(id, connection)

    // Info Operations
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        infoCapabilities.findByWorkflowId(workflowId, connection)

    // Instance Operations
    override suspend fun findByParentId(parentId: IDV7, connection: Connection?) =
        stateCapabilities.findByParentId(parentId, connection)

}
