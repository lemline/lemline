// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ListenerEventModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val LISTENER_EVENT_TABLE = "lemline_listener_events"

/**
 * Repository for managing listener event accumulation.
 *
 * This table stores CloudEvents for listeners using ALL or ANY+until strategies
 * that need to accumulate multiple events before completion.
 *
 * ## Key Operations
 *
 * - **Batch insert**: Insert events for multiple listeners in one request
 * - **Count by listener**: Check if ALL strategy is complete
 * - **Get events**: Retrieve accumulated events at completion time
 *
 * ## Idempotency
 *
 * Inserts use ON CONFLICT DO NOTHING / INSERT IGNORE to handle duplicate events gracefully.
 * The primary key is derived deterministically from listener ID and filter index or event ID.
 *
 * @see ListenerEventModel for the entity model
 */
@ApplicationScoped
@ExperimentalSerializationApi
@ExperimentalTime
internal class ListenerEventRepository : WithIdRepository<ListenerEventModel>() {

    companion object {
        const val ID_COLUMN = "id"
        const val LISTENER_ID_COLUMN = "listener_id"
        const val FILTER_INDEX_COLUMN = "filter_index"
        const val EVENT_COLUMN = "event"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = LISTENER_EVENT_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, ListenerEventModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            LISTENER_ID_COLUMN to { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenerId) },
            FILTER_INDEX_COLUMN to { stmt, entity, idx ->
                entity.filterIndex?.let { stmt.setInt(idx, it) } ?: stmt.setNull(idx, Types.INTEGER)
            },
            EVENT_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.event) }
        )
    }

    override fun createModel(rs: ResultSet): ListenerEventModel = ListenerEventModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!,
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN).takeIf { !rs.wasNull() },
        event = rs.getString(EVENT_COLUMN),
        createdAt = rs.getInstant(CREATED_AT_COLUMN)
    )

    /**
     * Finds all events for a listener, ordered by filter index.
     * Used at completion time to build the events array for the resume command.
     *
     * @param listenerId The listener ID
     * @param connection Optional database connection
     * @return List of events ordered by filter_index
     */
    suspend fun findByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): List<ListenerEventModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findByListenerIdSql by lazy {
        "SELECT * FROM $tableName WHERE $LISTENER_ID_COLUMN = ? ORDER BY $FILTER_INDEX_COLUMN"
    }

    /**
     * Batch finds all events for multiple listeners in a single query.
     * Returns a map of listener_id to list of events (ordered by filter_index).
     *
     * This is more efficient than calling [findByListenerId] multiple times.
     *
     * @param listenerIds List of listener IDs to fetch events for
     * @param connection Optional database connection
     * @return Map of listener ID to list of events
     */
    suspend fun batchFindByListenerIds(
        listenerIds: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, List<ListenerEventModel>> {
        if (listenerIds.isEmpty()) return emptyMap()

        return withConnection(connection) { conn ->
            val placeholders = listenerIds.joinToString(", ") { "?" }
            val sql = """
                SELECT * FROM $tableName
                WHERE $LISTENER_ID_COLUMN IN ($placeholders)
                ORDER BY $LISTENER_ID_COLUMN, $FILTER_INDEX_COLUMN
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                listenerIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildMap<IDV7, MutableList<ListenerEventModel>> {
                        while (rs.next()) {
                            val event = createModel(rs)
                            getOrPut(event.listenerId) { mutableListOf() }.add(event)
                        }
                    }
                }
            }
        }
    }

    /**
     * Batch counts events for multiple listeners in a single query.
     * Returns a map of listener_id to count.
     *
     * This minimizes database round-trips when checking completion for multiple listeners.
     *
     * @param listenerIds List of listener IDs to count
     * @param connection Optional database connection
     * @return Map of listener ID to event count
     */
    suspend fun batchCountByListenerIds(
        listenerIds: List<IDV7>,
        connection: Connection? = null
    ): Map<IDV7, Int> {
        if (listenerIds.isEmpty()) return emptyMap()

        return withConnection(connection) { conn ->
            val placeholders = listenerIds.joinToString(", ") { "?" }
            val sql = """
                SELECT $LISTENER_ID_COLUMN, COUNT(*) as cnt
                FROM $tableName
                WHERE $LISTENER_ID_COLUMN IN ($placeholders)
                GROUP BY $LISTENER_ID_COLUMN
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                listenerIds.forEachIndexed { index, id ->
                    setIDV7(stmt, index + 1, id)
                }
                stmt.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val listenerId = getIDV7(rs, LISTENER_ID_COLUMN)!!
                            val count = rs.getInt("cnt")
                            put(listenerId, count)
                        }
                    }
                }
            }
        }
    }

    /**
     * Counts events for a single listener.
     *
     * @param listenerId The listener ID
     * @param connection Optional database connection
     * @return Number of events for the listener
     */
    suspend fun countByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(countByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private val countByListenerIdSql by lazy {
        "SELECT COUNT(*) FROM $tableName WHERE $LISTENER_ID_COLUMN = ?"
    }

    /**
     * Deletes all events for a listener.
     *
     * @param listenerId The listener ID
     * @param connection Optional database connection
     * @return Number of deleted events
     */
    suspend fun deleteByListenerId(
        listenerId: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByListenerIdSql).use { stmt ->
            setIDV7(stmt, 1, listenerId)
            stmt.executeUpdate()
        }
    }

    private val deleteByListenerIdSql by lazy {
        "DELETE FROM $tableName WHERE $LISTENER_ID_COLUMN = ?"
    }
}
