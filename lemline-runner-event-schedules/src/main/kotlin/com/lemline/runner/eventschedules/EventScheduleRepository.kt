// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseType
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
import kotlinx.serialization.json.JsonArray
import com.lemline.runner.listeners.CloudEventService

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

    /** Prepare a definition's storage under its row lock; null means an already committed delivery. */
    suspend fun loadState(definition: DefinitionModel, event: CloudEvent, conn: Connection): IDV7? {
        val hash = digest(definition.definition)
        conn.prepareStatement("""
            SELECT s.id, s.definition_hash, r.event_key,
                   CASE WHEN r.event_key IS NULL AND s.definition_hash = ? THEN s.collections END AS collections
            FROM lemline_event_schedule_state s
            LEFT JOIN lemline_event_schedule_receipts r ON r.state_id = s.id AND r.event_key = ?
            WHERE s.namespace = ? AND s.name = ? AND s.version = ?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, hash)
            stmt.setString(2, eventKey(event))
            stmt.setString(3, definition.namespace.toString())
            stmt.setString(4, definition.name.toString())
            stmt.setString(5, definition.version.toString())
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    if (rs.getString("event_key") != null) return null
                    val id = getIDV7(rs, "id")!!
                    if (rs.getString("definition_hash") != hash) {
                        conn.prepareStatement("DELETE FROM lemline_event_schedule_collections WHERE state_id = ?").use {
                            setIDV7(it, 1, id)
                            it.executeUpdate()
                        }
                        clearLegacyState(id, hash, conn)
                    } else {
                        rs.getString("collections")?.let { payload ->
                            // One-time conversion, in the same transaction as processing the event.
                            val legacy = Json.decodeFromString<LegacyEventScheduleState>(payload)
                            legacy.groups.forEachIndexed { index, group ->
                                val collection = EventScheduleCollection(
                                    index = index.toLong(), correlations = group.correlations.toMutableMap(),
                                    matched = group.matched.toMutableSet(), termination = group.termination.toMutableSet(),
                                    eventCount = group.events.size.toLong(),
                                )
                                insertCollection(id, collection, conn)
                                group.events.forEachIndexed { eventIndex, json ->
                                    val stored = CloudEventService.deserialize(json)
                                    insertEvent(collection.id, eventIndex.toLong(), stored.source.toString(), stored.id, json, conn)
                                }
                            }
                            clearLegacyState(id, hash, conn)
                        }
                    }
                    return id
                }
            }
        }
        val id = IDV7.random()
        conn.prepareStatement(
            "INSERT INTO lemline_event_schedule_state (id, namespace, name, version, definition_hash, collections) VALUES (?, ?, ?, ?, ?, NULL)"
        ).use {
            setIDV7(it, 1, id)
            it.setString(2, definition.namespace.toString())
            it.setString(3, definition.name.toString())
            it.setString(4, definition.version.toString())
            it.setString(5, hash)
            it.executeUpdate()
        }
        return id
    }

    private fun clearLegacyState(id: IDV7, hash: String, conn: Connection) {
        conn.prepareStatement("UPDATE lemline_event_schedule_state SET definition_hash = ?, collections = NULL WHERE id = ?").use {
            it.setString(1, hash)
            setIDV7(it, 2, id)
            it.executeUpdate()
        }
    }

    /** Candidate metadata only. Event envelopes are fetched only for evaluation or completion. */
    suspend fun loadCollections(stateId: IDV7, conn: Connection): List<EventScheduleCollection> =
        conn.prepareStatement("SELECT * FROM lemline_event_schedule_collections WHERE state_id = ? ORDER BY collection_index").use {
            setIDV7(it, 1, stateId)
            it.executeQuery().use { rs -> buildList {
                while (rs.next()) add(readCollection(rs))
            } }
        }

    /** SQL preselection only; the shared matcher remains authoritative under the definition lock. */
    suspend fun findCandidateCollections(
        stateId: IDV7, keys: List<EventScheduleQueryKey>, conn: Connection,
    ): List<EventScheduleCollection> {
        if (keys.isEmpty()) return emptyList()
        val parameters = mutableListOf<String>()
        // MySQL text collations may equate distinct correlation keys. Match serialized tokens exactly.
        val correlations = if (databaseConfig.dbType == DatabaseType.MYSQL) "CAST(correlations AS BINARY)" else "correlations"
        val conditions = keys.map { key ->
            val parts = key.correlations.map { (name, value) ->
                val property = Json.encodeToString(name) + ":"
                val pair = property + Json.encodeToString(value)
                parameters.addAll(listOf(
                    likeLiteral("{" + property) + "%", "%" + likeLiteral("," + property) + "%",
                    "%" + likeLiteral(pair + ",") + "%", "%" + likeLiteral(pair + "}"),
                ))
                "(($correlations NOT LIKE ? ESCAPE '!' AND $correlations NOT LIKE ? ESCAPE '!')" +
                    " OR $correlations LIKE ? ESCAPE '!' OR $correlations LIKE ? ESCAPE '!')"
            }.toMutableList()
            key.availableFilter?.let { index ->
                parameters.add("%,$index,%")
                parts.add("REPLACE(REPLACE(matched_filters, '[', ','), ']', ',') NOT LIKE ?")
            }
            if (parts.isEmpty()) "1 = 1" else parts.joinToString(" AND ", "(", ")")
        }
        val sql = "SELECT * FROM lemline_event_schedule_collections WHERE state_id = ? AND (" +
            conditions.joinToString(" OR ") + ") ORDER BY collection_index"
        return conn.prepareStatement(sql).use { stmt ->
            setIDV7(stmt, 1, stateId)
            parameters.forEachIndexed { index, value -> stmt.setString(index + 2, value) }
            stmt.executeQuery().use { rs -> buildList {
                while (rs.next()) add(readCollection(rs))
            } }
        }
    }

    // All writers (including legacy conversion) use compact Json.encodeToString maps/sets.
    // Escape SQL wildcards separately from JSON escaping; values are always bound parameters.
    private fun likeLiteral(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    suspend fun nextCollectionIndex(stateId: IDV7, conn: Connection): Long =
        conn.prepareStatement("SELECT collection_index FROM lemline_event_schedule_collections WHERE state_id = ? ORDER BY collection_index DESC LIMIT 1").use {
            setIDV7(it, 1, stateId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) + 1 else 0 }
        }

    private fun readCollection(rs: ResultSet) = EventScheduleCollection(
        id = getIDV7(rs, "id")!!, index = rs.getLong("collection_index"),
        correlations = Json.decodeFromString(rs.getString("correlations")),
        matched = Json.decodeFromString(rs.getString("matched_filters")),
        termination = Json.decodeFromString(rs.getString("until_filters")),
        eventCount = rs.getLong("event_count"),
    )

    suspend fun insertCollection(stateId: IDV7, group: EventScheduleCollection, conn: Connection) {
        conn.prepareStatement("""
            INSERT INTO lemline_event_schedule_collections
                (id, state_id, collection_index, correlations, matched_filters, until_filters, event_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use {
            setIDV7(it, 1, group.id)
            setIDV7(it, 2, stateId)
            it.setLong(3, group.index)
            it.setString(4, Json.encodeToString(group.correlations))
            it.setString(5, Json.encodeToString(group.matched))
            it.setString(6, Json.encodeToString(group.termination))
            it.setLong(7, group.eventCount)
            it.executeUpdate()
        }
    }

    suspend fun updateCollection(group: EventScheduleCollection, conn: Connection) {
        conn.prepareStatement("""
            UPDATE lemline_event_schedule_collections
            SET correlations = ?, matched_filters = ?, until_filters = ?, event_count = ? WHERE id = ?
        """.trimIndent()).use {
            it.setString(1, Json.encodeToString(group.correlations))
            it.setString(2, Json.encodeToString(group.matched))
            it.setString(3, Json.encodeToString(group.termination))
            it.setLong(4, group.eventCount)
            setIDV7(it, 5, group.id)
            it.executeUpdate()
        }
    }

    suspend fun appendEvent(collectionId: IDV7, index: Long, event: CloudEvent, conn: Connection) =
        insertEvent(collectionId, index, event.source.toString(), event.id, CloudEventService.serialize(event), conn)

    private fun insertEvent(collectionId: IDV7, index: Long, source: String, eventId: String, json: String, conn: Connection) {
        conn.prepareStatement("""
            INSERT INTO lemline_event_schedule_events (collection_id, event_index, event_source, event_id, event)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()).use {
            setIDV7(it, 1, collectionId)
            it.setLong(2, index)
            it.setString(3, source)
            it.setString(4, eventId)
            it.setString(5, json)
            it.executeUpdate()
        }
    }

    suspend fun collectionInput(collectionId: IDV7, conn: Connection): JsonArray =
        conn.prepareStatement("SELECT event FROM lemline_event_schedule_events WHERE collection_id = ? ORDER BY event_index").use {
            setIDV7(it, 1, collectionId)
            it.executeQuery().use { rs -> JsonArray(buildList {
                while (rs.next()) add(CloudEventService.parseEnvelope(CloudEventService.deserialize(rs.getString("event"))))
            }) }
        }

    suspend fun deleteCollection(collectionId: IDV7, conn: Connection) {
        conn.prepareStatement("DELETE FROM lemline_event_schedule_collections WHERE id = ?").use {
            setIDV7(it, 1, collectionId)
            it.executeUpdate()
        }
    }

    /** Called after loadState rejects duplicates, under the same definition lock. */
    suspend fun recordEvent(stateId: IDV7, event: CloudEvent, conn: Connection) {
        val key = eventKey(event)
        conn.prepareStatement("INSERT INTO lemline_event_schedule_receipts (state_id, event_key) VALUES (?, ?)").use {
            setIDV7(it, 1, stateId)
            it.setString(2, key)
            it.executeUpdate()
        }
    }

    private fun eventKey(event: CloudEvent): String =
        digest("${event.source.toString().length}:${event.source}${event.id}")

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
