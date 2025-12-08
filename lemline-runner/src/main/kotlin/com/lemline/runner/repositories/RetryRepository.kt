// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.RetryModel
import com.lemline.runner.repositories.helpers.ColumnBindings
import com.lemline.runner.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.repositories.ops.CleanerRepository
import com.lemline.runner.repositories.ops.CrudRepository
import com.lemline.runner.repositories.ops.ID_COLUMN
import com.lemline.runner.repositories.ops.IdRepository
import com.lemline.runner.repositories.ops.InstanceRepository
import com.lemline.runner.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.repositories.ops.OutboxRepository
import com.lemline.runner.repositories.ops.cleanupColumns
import com.lemline.runner.repositories.ops.getInstanceMessage
import com.lemline.runner.repositories.ops.getInstant
import com.lemline.runner.repositories.ops.idColumn
import com.lemline.runner.repositories.ops.instanceColumns
import com.lemline.runner.repositories.ops.outboxColumns
import com.lemline.runner.repositories.ops.readCleanupField
import com.lemline.runner.repositories.ops.readOutboxFields
import com.lemline.runner.repositories.with.WithCleanerRepository
import com.lemline.runner.repositories.with.WithIdRepository
import com.lemline.runner.repositories.with.WithInstanceRepository
import com.lemline.runner.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

const val RETRY_TABLE = "lemline_retries"

/**
 * Repository for managing retry messages in the outbox pattern.
 * Uses composition to provide outbox, cleaner, and instance operations.
 *
 * @see RetryModel for the message model
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class RetryRepository : CrudRepository<RetryModel>(),
    WithIdRepository<RetryModel>,
    WithInstanceRepository<RetryModel>,
    WithOutboxRepository<RetryModel>,
    WithCleanerRepository<RetryModel> {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = RETRY_TABLE

    // Composed operations - initialized lazily to ensure databaseManager is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseManager) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseManager) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseManager) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseManager) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    // Delegate WithInstanceRepository methods
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        instanceRepository.findByWorkflowId(workflowId, connection)

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    companion object {
        internal const val ERROR_REASON_COLUMN = "error_reason"
        internal const val ERROR_CLASS_COLUMN = "error_class"
        internal const val ERROR_MESSAGE_COLUMN = "error_message"
        internal const val ERROR_STACKTRACE_COLUMN = "error_stacktrace"
    }

    override val columns: ColumnBindings<RetryModel> by lazy {
        ColumnBindingsBuilder<RetryModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            cleanupColumns()
            outboxColumns()

            // Retry-specific error columns
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

    override fun createModel(rs: ResultSet) = RetryModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.TaskRetryScheduled>(idHelper)!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
        errorReason = rs.getString(ERROR_REASON_COLUMN),
        errorClass = rs.getString(ERROR_CLASS_COLUMN),
        errorMessage = rs.getString(ERROR_MESSAGE_COLUMN),
        errorStackTrace = rs.getString(ERROR_STACKTRACE_COLUMN),
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)
}
