// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.lifecycle

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CleanerRepository
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
import com.lemline.runner.common.repositories.ops.OUTBOX_ATTEMPT_COUNT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_COMPLETED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_DELAYED_UNTIL_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_FAILED_AT_COLUMN
import com.lemline.runner.common.repositories.ops.OUTBOX_SCHEDULED_FOR_COLUMN
import com.lemline.runner.common.repositories.ops.OutboxRepository
import com.lemline.runner.common.repositories.ops.WORKFLOW_ID_COLUMN
import com.lemline.runner.common.repositories.ops.WORKFLOW_NAMESPACE_COLUMN
import com.lemline.runner.common.repositories.ops.cleanupColumns
import com.lemline.runner.common.repositories.ops.getInstant
import com.lemline.runner.common.repositories.ops.idColumn
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
import java.sql.Timestamp
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

const val CREATION_EVENT_OUTBOX_TABLE = "lemline_creation_event_outbox"
const val CREATION_EVENT_TYPE_COLUMN = "event_type"
const val CREATION_EVENT_TIME_COLUMN = "event_time"
const val CREATION_EVENT_PAYLOAD_COLUMN = "event_payload"

@ApplicationScoped
open class CreationEventRepository : CrudRepository<CreationEventModel>(),
    WithIdRepository<CreationEventModel>,
    WithOutboxRepository<CreationEventModel>,
    WithCleanerRepository<CreationEventModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    var namespaceScope: NamespaceScope = NamespaceScope.UNSCOPED

    override val tableName = CREATION_EVENT_OUTBOX_TABLE

    // Like the gateway command outbox, an unpinned process relays rows of every namespace:
    // creation events are written by whichever process accepted the command.
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig, namespaceScope.configured) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig, namespaceScope.configured) }

    override suspend fun findById(id: IDV7, connection: Connection?) =
        idRepository.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idRepository.deleteById(id, connection)

    override suspend fun findEntitiesToProcess(maxAttempts: Int, limit: Int, connection: Connection?) =
        outboxRepository.findEntitiesToProcess(maxAttempts, limit, connection)

    override suspend fun findEntitiesToDelete(cutoffDate: Instant, batchSize: Int, connection: Connection?) =
        cleanerRepository.findEntitiesToDelete(cutoffDate, batchSize, connection)

    /** Claims committed, ready, unfinished rows among [ids] without racing the scheduled relay. */
    suspend fun findReadyByIds(ids: Collection<IDV7>, maxAttempts: Int, connection: Connection): List<CreationEventModel> {
        if (ids.isEmpty()) return emptyList()
        return databaseConfig.withConnection(connection) { conn ->
            val sql = """
                SELECT * FROM $tableName
                WHERE $ID_COLUMN IN (${ids.joinToString(", ") { "?" }})
                  AND $OUTBOX_COMPLETED_AT_COLUMN IS NULL
                  AND $OUTBOX_FAILED_AT_COLUMN IS NULL
                  AND $OUTBOX_DELAYED_UNTIL_COLUMN IS NOT NULL
                  AND $OUTBOX_DELAYED_UNTIL_COLUMN <= ?
                  AND $OUTBOX_ATTEMPT_COUNT_COLUMN < ?
                FOR UPDATE SKIP LOCKED
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                var index = 1
                for (id in ids) idHelper.set(stmt, index++, id)
                stmt.setTimestamp(index++, Timestamp.from(Clock.System.now().toJavaInstant()))
                stmt.setInt(index, maxAttempts)
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(createModel(rs)) }
                }
            }
        }
    }

    /** All rows of one instance, oldest first (inspection and tests). */
    suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection? = null): List<CreationEventModel> =
        databaseConfig.withConnection(connection) { conn ->
            conn.prepareStatement("SELECT * FROM $tableName WHERE $WORKFLOW_ID_COLUMN = ? ORDER BY $CREATION_EVENT_TIME_COLUMN, $ID_COLUMN").use { stmt ->
                idHelper.set(stmt, 1, workflowId.value)
                stmt.executeQuery().use { rs -> buildList { while (rs.next()) add(createModel(rs)) } }
            }
        }

    override val columns: ColumnBindings<CreationEventModel> by lazy {
        ColumnBindingsBuilder<CreationEventModel>().apply {
            idColumn(idHelper)
            column(WORKFLOW_ID_COLUMN) { stmt, entity, idx -> idHelper.set(stmt, idx, entity.workflowId.value) }
            column(WORKFLOW_NAMESPACE_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.workflowNamespace) }
            column(CREATION_EVENT_TYPE_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.eventType) }
            column(CREATION_EVENT_TIME_COLUMN) { stmt, entity, idx ->
                stmt.setTimestamp(idx, Timestamp.from(entity.eventTime.toJavaInstant()))
            }
            column(CREATION_EVENT_PAYLOAD_COLUMN) { stmt, entity, idx -> stmt.setString(idx, entity.eventPayload) }
            cleanupColumns()
            outboxColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet) = CreationEventModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        workflowId = WorkflowId(getIDV7(rs, WORKFLOW_ID_COLUMN)!!),
        workflowNamespace = rs.getString(WORKFLOW_NAMESPACE_COLUMN),
        eventType = rs.getString(CREATION_EVENT_TYPE_COLUMN),
        eventTime = rs.getInstant(CREATION_EVENT_TIME_COLUMN)!!,
        eventPayload = rs.getString(CREATION_EVENT_PAYLOAD_COLUMN),
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN),
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)
}
