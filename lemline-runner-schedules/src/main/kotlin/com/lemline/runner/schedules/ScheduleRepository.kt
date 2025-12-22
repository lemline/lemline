// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

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
import com.lemline.runner.common.repositories.with.WithInstanceRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

const val SCHEDULE_TABLE = "lemline_schedules"

/**
 * Repository for managing schedule messages in the outbox pattern.
 * Uses composition to provide outbox, cleaner, and instance operations.
 *
 * @see ScheduleModel for the message model
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ScheduleRepository : CrudRepository<ScheduleModel>(),
    WithIdRepository<ScheduleModel>,
    WithOutboxRepository<ScheduleModel>,
    WithInstanceRepository<ScheduleModel>,
    WithCleanerRepository<ScheduleModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val tableName = SCHEDULE_TABLE

    companion object {
        internal const val SCHEDULE_AFTER_COLUMN = "schedule_after"
        internal const val SCHEDULE_CRON_COLUMN = "schedule_cron"
        internal const val SCHEDULE_EVERY_COLUMN = "schedule_every"
        internal const val SCHEDULE_ZONE_COLUMN = "schedule_zone"
    }

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig) }
    val instanceRepository by lazy { InstanceRepository(tableName, idHelper, ::createModel, databaseConfig) }

    // Delegate WithIdRepository methods
    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    // Delegate WithOutboxRepository methods
    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    // Delegate WithInstanceRepository methods
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?): ScheduleModel? =
        instanceRepository.findByWorkflowId(workflowId, connection)

    // Delegate WithCleanerRepository methods
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    override val columns: ColumnBindings<ScheduleModel> by lazy {
        ColumnBindingsBuilder<ScheduleModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            cleanupColumns()
            outboxColumns()

            // Schedule-specific columns
            column(SCHEDULE_AFTER_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.scheduleAfter)
            }
            column(SCHEDULE_EVERY_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.scheduleEvery)
            }
            column(SCHEDULE_CRON_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.scheduleCron)
            }
            column(SCHEDULE_ZONE_COLUMN) { stmt, entity, idx ->
                stmt.setString(idx, entity.scheduleZone)
            }
        }.build()
    }

    override fun createModel(rs: ResultSet) = ScheduleModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowCommand.ResumeFromTask>(idHelper)!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
        scheduleAfter = rs.getString(SCHEDULE_AFTER_COLUMN),
        scheduleEvery = rs.getString(SCHEDULE_EVERY_COLUMN),
        scheduleCron = rs.getString(SCHEDULE_CRON_COLUMN),
        scheduleZone = rs.getString(SCHEDULE_ZONE_COLUMN),
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)
}
