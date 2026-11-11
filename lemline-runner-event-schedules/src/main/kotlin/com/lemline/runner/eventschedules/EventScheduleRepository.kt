// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.common.repositories.helpers.ColumnBindings
import com.lemline.runner.common.repositories.helpers.ColumnBindingsBuilder
import com.lemline.runner.common.repositories.ops.CleanerRepository
import com.lemline.runner.common.repositories.ops.CrudRepository
import com.lemline.runner.common.repositories.ops.ID_COLUMN
import com.lemline.runner.common.repositories.ops.IdRepository
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
import com.lemline.runner.definitions.DefinitionModel
import io.cloudevents.CloudEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json

const val EVENT_SCHEDULE_TABLE = "lemline_event_schedule_starts"

/**
 * Repository for managing event-triggered starts in the outbox pattern.
 * Uses composition to provide outbox, cleaner, and instance operations.
 *
 * @see EventScheduleModel for the message model
 */
@ApplicationScoped
class EventScheduleRepository : CrudRepository<EventScheduleModel>(),
    WithIdRepository<EventScheduleModel>,
    WithOutboxRepository<EventScheduleModel>,
    WithCleanerRepository<EventScheduleModel> {

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    var namespaceScope: NamespaceScope = NamespaceScope.UNSCOPED

    override val tableName = EVENT_SCHEDULE_TABLE

    /** Caller holds the definition row lock for the entire read/modify/write transaction. */
    suspend fun loadState(definition: DefinitionModel, conn: Connection): Pair<IDV7, EventScheduleState> {
        val hash = digest(definition.definition)
        conn.prepareStatement(
            "SELECT * FROM lemline_event_schedule_state WHERE namespace = ? AND name = ? AND version = ?"
        ).use { stmt ->
            stmt.setString(1, definition.namespace.toString())
            stmt.setString(2, definition.name.toString())
            stmt.setString(3, definition.version.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val id = getIDV7(rs, "id")!!
                    if (rs.getString("definition_hash") == hash) {
                        return id to Json.decodeFromString<EventScheduleState>(rs.getString("collections"))
                    }
                    // Do not combine events collected under different revisions of a forced update.
                    conn.prepareStatement("UPDATE lemline_event_schedule_state SET definition_hash = ?, collections = ? WHERE id = ?").use {
                        it.setString(1, hash)
                        it.setString(2, Json.encodeToString(EventScheduleState()))
                        setIDV7(it, 3, id)
                        it.executeUpdate()
                    }
                    return id to EventScheduleState()
                }
            }
        }
        val id = IDV7.random()
        conn.prepareStatement(
            "INSERT INTO lemline_event_schedule_state (id, namespace, name, version, definition_hash, collections) VALUES (?, ?, ?, ?, ?, ?)"
        ).use {
            setIDV7(it, 1, id)
            it.setString(2, definition.namespace.toString())
            it.setString(3, definition.name.toString())
            it.setString(4, definition.version.toString())
            it.setString(5, hash)
            it.setString(6, Json.encodeToString(EventScheduleState()))
            it.executeUpdate()
        }
        return id to EventScheduleState()
    }

    suspend fun recordEvent(stateId: IDV7, event: CloudEvent, conn: Connection): Boolean {
        // Length-prefixing avoids ambiguity; identity is CloudEvents source + id, not id alone.
        val key = digest("${event.source.toString().length}:${event.source}${event.id}")
        conn.prepareStatement("SELECT 1 FROM lemline_event_schedule_receipts WHERE state_id = ? AND event_key = ?").use {
            setIDV7(it, 1, stateId)
            it.setString(2, key)
            it.executeQuery().use { rs -> if (rs.next()) return false }
        }
        conn.prepareStatement("INSERT INTO lemline_event_schedule_receipts (state_id, event_key) VALUES (?, ?)").use {
            setIDV7(it, 1, stateId)
            it.setString(2, key)
            it.executeUpdate()
        }
        return true
    }

    suspend fun saveState(stateId: IDV7, state: EventScheduleState, conn: Connection) {
        conn.prepareStatement("UPDATE lemline_event_schedule_state SET collections = ? WHERE id = ?").use {
            it.setString(1, Json.encodeToString(state))
            setIDV7(it, 2, stateId)
            it.executeUpdate()
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // Composed operations - initialized lazily to ensure databaseConfig is injected
    val idRepository by lazy { IdRepository(tableName, idHelper, ::createModel, databaseConfig) }
    val outboxRepository by lazy { OutboxRepository(tableName, ::createModel, databaseConfig, namespaceScope.configured) }
    val cleanerRepository by lazy { CleanerRepository(tableName, ::createModel, databaseConfig, namespaceScope.configured) }

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

    override val columns: ColumnBindings<EventScheduleModel> by lazy {
        ColumnBindingsBuilder<EventScheduleModel>().apply {
            idColumn(idHelper)
            instanceColumns(idHelper)
            cleanupColumns()
            outboxColumns()
        }.build()
    }

    override fun createModel(rs: ResultSet) = EventScheduleModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowCommand.ResumeFromTask>(idHelper)!!,
        outboxScheduledFor = rs.getInstant(OUTBOX_SCHEDULED_FOR_COLUMN)!!,
    )
        .readOutboxFields(rs)
        .readCleanupField(rs)
}
