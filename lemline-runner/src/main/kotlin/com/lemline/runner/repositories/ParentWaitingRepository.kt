// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.models.ParentWaitingModel
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
 * @see WithInstanceRepository for base functionality
 * @see ParentWaitingModel for the state model
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentWaitingRepository : WithInstanceRepository<ParentWaitingModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = PARENT_TABLE

    override fun createModel(rs: ResultSet) = ParentWaitingModel(
        id = getIDV7(rs, ID_COLUMN)!!,
        instanceMessage = rs.getInstanceMessage<WorkflowEvent.RunWorkflowStarted>()!!
    )
}
