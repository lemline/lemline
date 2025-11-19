// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.common.values.WorkflowId
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ParentModel
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val PARENT_TABLE = "lemline_parents"

/**
 * Repository for managing parent workflow waiting state.
 * Stores parent workflow state while waiting for child workflow completion.
 * Event-driven pattern - processed immediately when child completes, then deleted.
 *
 * @see CleanerRepository for base functionality
 * @see ParentModel for the state model
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentRepository : CleanerRepository<ParentModel>() {

    companion object Companion {
        const val CHILD_ID_COLUMN = "child_id"
    }

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = PARENT_TABLE

    override val prepareStatementMap by lazy {
        super.prepareStatementMap + mapOf(
            CHILD_ID_COLUMN to { stmt: java.sql.PreparedStatement, entity: ParentModel, idx: Int ->
                setIDV7(stmt, idx, entity.childId)
            }
        )
    }

    override fun createModel(rs: ResultSet) = ParentModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.RunWorkflowStarted>()!!,
        childId = getIDV7(rs, CHILD_ID_COLUMN)!!,
        outboxCompletedAt = rs.getInstant(OUTBOX_COMPLETED_AT_COLUMN),
    )

    /**
     * Finds a parent by the child workflow ID.
     * Used when a child workflow completes to resume its parent.
     *
     * @param childId The workflow ID of the child
     * @param connection An optional database connection to use. If null, a new connection is acquired.
     * @return The parent model, or null if not found
     */
    suspend fun findByChildId(childId: WorkflowId, connection: java.sql.Connection? = null): ParentModel? =
        withConnection(connection) { conn ->
            conn.prepareStatement(findByChildIdSql).use { stmt ->
                setIDV7(stmt, 1, childId.value)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) createModel(rs) else null
                }
            }
        }

    private val findByChildIdSql by lazy { "SELECT * FROM $tableName WHERE $CHILD_ID_COLUMN = ? LIMIT 1" }
}
