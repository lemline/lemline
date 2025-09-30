// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.bases

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.bases.OptionalOutboxModel
import com.lemline.runner.models.bases.OutboxModel
import com.lemline.runner.models.bases.OutboxModelBase
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.capabilities.CleanerCapable
import com.lemline.runner.repositories.capabilities.ID_COLUMN
import com.lemline.runner.repositories.capabilities.IdCapable
import com.lemline.runner.repositories.capabilities.InfoCapabilities
import com.lemline.runner.repositories.capabilities.InfoCapable
import com.lemline.runner.repositories.capabilities.OptionalCleanerCapable
import com.lemline.runner.repositories.capabilities.OptionalOutboxCapable
import com.lemline.runner.repositories.capabilities.OutboxCapabilities
import com.lemline.runner.repositories.capabilities.OutboxCapable
import com.lemline.runner.repositories.capabilities.OutboxCapableBase
import com.lemline.runner.repositories.capabilities.StateCapabilities
import com.lemline.runner.repositories.capabilities.StateCapable
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

abstract class OutboxRepository<T : OutboxModel> : OutboxRepositoryBase<T>() {
    override val idCapable by lazy { IdCapable(this) }
    override val cleanerCapable by lazy { CleanerCapable(this) }
    override val senderCapable by lazy { OutboxCapable(this, failureRepository) }

    val ResultSet.runAt: Instant get() = with(cleanerCapable) { this@runAt.runAt }
    val ResultSet.runDelayedUntil: Instant get() = with(senderCapable) { this@runDelayedUntil.runDelayedUntil }
}

abstract class OptionalOutboxRepository<T : OptionalOutboxModel> : OutboxRepositoryBase<T>() {
    override val idCapable by lazy { IdCapable(this) }
    override val cleanerCapable by lazy { OptionalCleanerCapable(this) }
    override val senderCapable by lazy { OptionalOutboxCapable(this, failureRepository) }

    val ResultSet.runAt: Instant? get() = with(cleanerCapable) { this@runAt.runAt }
    val ResultSet.runDelayedUntil: Instant? get() = with(senderCapable) { this@runDelayedUntil.runDelayedUntil }
}

abstract class OutboxRepositoryBase<T : OutboxModelBase> : CleanerRepositoryBase<T>(),
    InfoCapabilities<T>,
    StateCapabilities<T>,
    OutboxCapabilities<T> {

    protected abstract val failureRepository: FailureRepository

    val infoCapable by lazy { InfoCapable(this) }
    val stateCapable by lazy { StateCapable(this) }

    abstract val senderCapable: OutboxCapableBase<T>

    // Key Columns
    override val keyColumns = listOf(ID_COLUMN)

    override val prepareStatementMap by lazy {
        super.prepareStatementMap +
            infoCapable.mapping +
            stateCapable.mapping +
            senderCapable.mapping
    }

    val ResultSet.outBoxStatus get() = with(cleanerCapable) { this@outBoxStatus.runStatus }
    val ResultSet.runAttemptCount get() = with(senderCapable) { this@runAttemptCount.runAttemptCount }
    val ResultSet.runLastErrorClass get() = with(senderCapable) { this@runLastErrorClass.runLastErrorClass }
    val ResultSet.runLastErrorMessage get() = with(senderCapable) { this@runLastErrorMessage.runLastErrorMessage }
    val ResultSet.runLastErrorStackTrace get() = with(senderCapable) { this@runLastErrorStackTrace.runLastErrorStackTrace }

    val ResultSet.instanceMessage
        get(): InstanceMessage {
            val rs = this
            return InstanceMessage(
                workflowInfo = with(infoCapable) { rs.workflowInfo },
                workflowState = WorkflowState(
                    currentPosition = with(stateCapable) { rs.nodePosition },
                    currentStates = with(stateCapable) { rs.nodeStates },
                ),
                parentId = with(stateCapable) { rs.parentId },
            )
        }

    // Info Operations
    override suspend fun findByWorkflowId(workflowId: WorkflowId, connection: Connection?) =
        infoCapable.findByWorkflowId(workflowId, connection)

    // Instance Operations
    override suspend fun findByParentId(parentId: IDV7, connection: Connection?) =
        stateCapable.findByParentId(parentId, connection)

    // Sender Operations
    override suspend fun retryById(id: IDV7, connection: Connection?) =
        senderCapable.retryById(id, connection)

    override suspend fun findEntitiesToSend(maxAttempts: Int, limit: Int, connection: Connection?) =
        senderCapable.findEntitiesToSend(maxAttempts, limit, connection)
}
