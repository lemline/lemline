// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CleanerRepository
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.InstanceRepository
import com.lemline.runner.common.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.common.repositories.ops.OutboxRepository
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstanceMessage
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
import com.lemline.runner.common.repositories.ops.instanceColumns
import com.lemline.runner.common.repositories.ops.outboxColumns
import com.lemline.runner.common.repositories.ops.readCleanupField
import com.lemline.runner.common.repositories.ops.readOutboxFields
import com.lemline.runner.common.repositories.with.WithCleanerRepository
import com.lemline.runner.common.repositories.with.WithIdRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.Instant

const val GATEWAY_COMMAND_OUTBOX_TABLE = "lemline_gateway_command_outbox"

@ApplicationScoped
class GatewayCommandOutboxRepository : CrudRepository<GatewayCommandOutboxModel>(),
    WithIdRepository<GatewayCommandOutboxModel>,
    WithOutboxRepository<GatewayCommandOutboxModel>,
    WithCleanerRepository<GatewayCommandOutboxModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = GATEWAY_COMMAND_OUTBOX_TABLE

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    // Workflow-id lookups (idempotency guard)
    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): GatewayCommandOutboxModel? =
        instanceRepository.findByWorkflowId(workflowId, connection)

    override val columns: ColumnBindings<GatewayCommandOutboxModel> by lazy {
        ColumnBindingsBuilder<GatewayCommandOutboxModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            cleanupColumns()
            outboxColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet) = GatewayCommandOutboxModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowCommand.ResumeFromTask>(idHelper)!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)
}
