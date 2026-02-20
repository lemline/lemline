// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.failures

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.WORKFLOW_ID_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAME_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_POSITION_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_STATE_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_VERSION_COLUMN
import com.lemline.runner.common.repositories.ops.getInstanceMessage
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.with.WithIdRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet

const val FAILURE_TABLE = "lemline_failures"

/**
 * Repository for managing failure records.
 * Uses composition to provide instance operations.
 *
 * @see FailureModel for the entity model
 */
@ApplicationScoped
class FailureRepository : CrudRepository<FailureModel>(),
    WithIdRepository<FailureModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = FAILURE_TABLE

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }

    companion object {
        internal const val PAYLOAD_COLUMN = "payload"
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    override val columns: ColumnBindings<FailureModel> by lazy {
        ColumnBindingsBuilder<FailureModel>().apply {
            // Composed columns from helper ops
            idColumn(idHelper)

            column(WORKFLOW_ID_COLUMN) { stmt, entity, idx ->
                idHelper.set(stmt, idx, entity.instanceMessage?.workflowState?.workflowId?.value)
            }
            column(WORKFLOW_NAMESPACE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage?.workflowNamespace?.toString())
            }
            column(WORKFLOW_NAME_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage?.workflowName?.toString())
            }
            column(WORKFLOW_VERSION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage?.workflowVersion?.toString())
            }
            column(WORKFLOW_POSITION_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.instanceMessage?.workflowState?.nodePosition?.toString())
            }
            column(WORKFLOW_STATE_COLUMN) { stmt, entity, idx ->
                stmt.setString(
                    idx,
                    entity.instanceMessage?.workflowState?.let { state ->
                        InstanceMessageCodec.workflowStateToDbJson(state)
                    }
                )
            }

            // Failure-specific columns
            column(PAYLOAD_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.payload)
            }
            column(ERROR_REASON_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorReason)
            }
            column(ERROR_CLASS_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorClass)
            }
            column(ERROR_MESSAGE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorMessage)
            }
            column(ERROR_STACKTRACE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.errorStackTrace)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet) = FailureModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.WorkflowFailed>(idHelper),
        payload = rs.getString(PAYLOAD_COLUMN),
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN)
    )

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    /**
     * Find all failures for a given workflow ID.
     * Unlike other repositories that have a single entity per workflow, failures can have multiple entries.
     */
    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): List<FailureModel> =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement(findByWorkflowIdSql).use { stmt ->
                idHelper.set(stmt, 1, workflowId.value)
                stmt.executeQuery().use { it.toModels() }
            }
        }

    private val findByWorkflowIdSql by lazy {
        "SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ? ORDER BY $ID_COLUMN"
    }
}
