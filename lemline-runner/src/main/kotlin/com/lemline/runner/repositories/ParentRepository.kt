// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.repositories

import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.ParentModel
import com.lemline.runner.repositories.bases.DatabaseManager
import com.lemline.runner.repositories.bases.OptionalCleanerRepository
import com.lemline.runner.repositories.capabilities.InfoCapable
import com.lemline.runner.repositories.capabilities.StateCapable
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

const val PARENT_TABLE = "lemline_parents"

/**
 * Repository for managing run messages in the outbox pattern.
 * This repository handles the persistence and retrieval of run messages,
 * which are used to implement run logic for workflows.
 *
 * This repository inherits all its functionality from OutboxRepository,
 * providing specific table and entity type information. The implementation
 * uses native SQL queries with SKIP LOCKED for parallel processing safety,
 * ensuring reliable message delivery in distributed systems.
 *
 * @see com.lemline.runner.repositories.bases.OutboxRepository for base functionality and documentation
 * @see ParentModel for the message model
 * @see OutboxRunnner for the processing logic
 */
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ParentRepository : OptionalCleanerRepository<ParentModel>() {

    @Inject
    override lateinit var databaseManager: DatabaseManager

    override val tableName = PARENT_TABLE

    val infoCapable by lazy { InfoCapable(this) }
    val stateCapabilities by lazy { StateCapable(this) }

    override val prepareStatementMap = super.prepareStatementMap +
        infoCapable.mapping + stateCapabilities.mapping


    private val ResultSet.nodePosition get() = with(stateCapabilities) { this@nodePosition.nodePosition }
    private val ResultSet.nodeStates get() = with(stateCapabilities) { this@nodeStates.nodeStates }
    private val ResultSet.workflowInfo get() = with(infoCapable) { this@workflowInfo.workflowInfo }
    private val ResultSet.parentId get() = with(stateCapabilities) { this@parentId.parentId }

    private val ResultSet.instanceMessage
        get() = InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowState(
                currentPosition = nodePosition,
                currentStates = nodeStates,
            ),
            parentId = parentId
        )

    override fun createModel(rs: ResultSet) = ParentModel(
        id = rs.id,
        instanceMessage = rs.instanceMessage,
        runStatus = rs.runStatus,
        runAt = rs.runAt,
    )
}
