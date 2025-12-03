// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.DefinitionListenFilterModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.time.ExperimentalTime

const val DEFINITION_LISTEN_FILTER_TABLE = "lemline_definition_listen_filters"

/**
 * Repository for managing event filter definitions for listen tasks.
 *
 * This repository stores event filter configurations that are extracted when
 * workflow definitions are registered. The stored filters enable efficient
 * CloudEvent routing to matching listeners without parsing workflow definitions
 * at runtime.
 *
 * ## Usage
 *
 * When a workflow definition containing listen tasks is saved:
 * 1. Extract all listen tasks and their filters from the workflow
 * 2. Insert listen tasks using [DefinitionListenRepository]
 * 3. Insert filters for each listen task using this repository
 *
 * When a CloudEvent arrives:
 * 1. Query filters by event type/source/subject
 * 2. Match against active listeners for those filters
 */
@ExperimentalTime
@ApplicationScoped
class DefinitionListenFilterRepository : WithIdRepository<DefinitionListenFilterModel>() {

    companion object {
        const val LISTEN_ID_COLUMN = "listen_id"
        const val FILTER_INDEX_COLUMN = "filter_index"
        const val EVENT_ID_COLUMN = "event_id"
        const val EVENT_TYPE_COLUMN = "event_type"
        const val EVENT_SOURCE_COLUMN = "event_source"
        const val EVENT_SUBJECT_COLUMN = "event_subject"
        const val EVENT_DATACONTENTTYPE_COLUMN = "event_datacontenttype"
        const val EVENT_DATASCHEMA_COLUMN = "event_dataschema"
        const val EVENT_TIME_COLUMN = "event_time"
        const val EVENT_DATA_COLUMN = "event_data"
        const val CORRELATIONS_COLUMN = "correlations"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = DEFINITION_LISTEN_FILTER_TABLE

    override val prepareStatementMap: Map<String, (PreparedStatement, DefinitionListenFilterModel, Int) -> Unit> by lazy {
        super.prepareStatementMap + mapOf(
            LISTEN_ID_COLUMN to { stmt, entity, idx -> setIDV7(stmt, idx, entity.listenId) },
            FILTER_INDEX_COLUMN to { stmt, entity, idx -> stmt.setInt(idx, entity.filterIndex) },
            EVENT_ID_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventId) },
            EVENT_TYPE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventType) },
            EVENT_SOURCE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventSource) },
            EVENT_SUBJECT_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventSubject) },
            EVENT_DATACONTENTTYPE_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventDatacontenttype) },
            EVENT_DATASCHEMA_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventDataschema) },
            EVENT_TIME_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventTime) },
            EVENT_DATA_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.eventData) },
            CORRELATIONS_COLUMN to { stmt, entity, idx -> stmt.setString(idx, entity.correlations) }
        )
    }

    override fun createModel(rs: ResultSet): DefinitionListenFilterModel = DefinitionListenFilterModel(
        id = getIDV7(rs, WithIdRepository.ID_COLUMN)!!,
        listenId = getIDV7(rs, LISTEN_ID_COLUMN)!!,
        filterIndex = rs.getInt(FILTER_INDEX_COLUMN),
        eventId = rs.getString(EVENT_ID_COLUMN),
        eventType = rs.getString(EVENT_TYPE_COLUMN),
        eventSource = rs.getString(EVENT_SOURCE_COLUMN),
        eventSubject = rs.getString(EVENT_SUBJECT_COLUMN),
        eventDatacontenttype = rs.getString(EVENT_DATACONTENTTYPE_COLUMN),
        eventDataschema = rs.getString(EVENT_DATASCHEMA_COLUMN),
        eventTime = rs.getString(EVENT_TIME_COLUMN),
        eventData = rs.getString(EVENT_DATA_COLUMN),
        correlations = rs.getString(CORRELATIONS_COLUMN)
    )

    /**
     * Finds all filters for a specific listen task.
     *
     * @param listenId The listen task ID
     * @param connection Optional database connection
     * @return List of filter definitions for the listen task, ordered by filter index
     */
    suspend fun findByListenId(
        listenId: IDV7,
        connection: Connection? = null
    ): List<DefinitionListenFilterModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByListenIdSql).use { stmt ->
            setIDV7(stmt, 1, listenId)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findByListenIdSql by lazy {
        """
        SELECT * FROM $tableName
        WHERE $LISTEN_ID_COLUMN = ?
        ORDER BY $FILTER_INDEX_COLUMN
        """.trimIndent()
    }

    /**
     * Deletes all filters for a specific listen task.
     *
     * @param listenId The listen task ID
     * @param connection Optional database connection
     * @return Number of filters deleted
     */
    suspend fun deleteByListenId(
        listenId: IDV7,
        connection: Connection? = null
    ): Int = withConnection(connection) { conn ->
        conn.prepareStatement(deleteByListenIdSql).use { stmt ->
            setIDV7(stmt, 1, listenId)
            stmt.executeUpdate()
        }
    }

    private val deleteByListenIdSql by lazy {
        "DELETE FROM $tableName WHERE $LISTEN_ID_COLUMN = ?"
    }

    /**
     * Finds all filters that match a CloudEvent's type.
     * Used for efficient event routing to potential listeners.
     *
     * @param eventType The CloudEvent type to match
     * @param connection Optional database connection
     * @return List of matching filter definitions
     */
    suspend fun findByEventType(
        eventType: String,
        connection: Connection? = null
    ): List<DefinitionListenFilterModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findByEventTypeSql).use { stmt ->
            stmt.setString(1, eventType)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findByEventTypeSql by lazy {
        "SELECT * FROM $tableName WHERE $EVENT_TYPE_COLUMN = ?"
    }

    /**
     * Finds all filters that have no event type (wildcard filters that match any event).
     *
     * @param connection Optional database connection
     * @return List of wildcard filter definitions
     */
    suspend fun findWildcardFilters(
        connection: Connection? = null
    ): List<DefinitionListenFilterModel> = withConnection(connection) { conn ->
        conn.prepareStatement(findWildcardFiltersSql).use { stmt ->
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(createModel(rs))
                    }
                }
            }
        }
    }

    private val findWildcardFiltersSql by lazy {
        "SELECT * FROM $tableName WHERE $EVENT_TYPE_COLUMN IS NULL"
    }
}
