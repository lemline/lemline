// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.listeners.ListenerConfig
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.Duration

@Startup
@ApplicationScoped
class EventScheduleOutbox : AbstractOutbox<EventScheduleModel>() {
    @Inject public override lateinit var databaseConfig: DatabaseConfig
    @Inject public override lateinit var commandEmitter: CommandEmitter
    @Inject lateinit var repository: EventScheduleRepository
    @Inject lateinit var listenerConfig: ListenerConfig
    @Inject lateinit var creationEventEnqueuer: CreationEventEnqueuer

    override val jobName = "Event schedules outbox"
    override val enabled get() = listenerConfig.enabled
    override val outboxConfig get() = listenerConfig.outbox
    override val outboxRepository get() = repository
    override val crudRepository get() = repository

    /**
     * Rows written before creation events were persisted at acceptance carry no `createdAt`, and
     * no creation event was enqueued for them: older runtimes emitted `workflow.created` from this
     * relay. Enqueue them now, on the batch connection, once the command is published, stamped
     * with `RootState.startedAt` as older runtimes did. Their IDs match the worker's legacy
     * `/do` `task.created`, so consumers deduplicate them.
     */
    override suspend fun processBatch(
        entities: List<EventScheduleModel>,
        maxAttempts: Int,
        retryDelay: Duration,
        conn: Connection,
    ): Int {
        val processed = super.processBatch(entities, maxAttempts, retryDelay, conn)
        entities
            .filter { it.outboxCompletedAt != null && it.instanceMessage.workflowState.createdAt == null }
            .forEach { legacy ->
                val message = legacy.instanceMessage
                val stamped = message.workflowState.copy(createdAt = message.workflowState.nodeStack.rootState.startedAt)
                creationEventEnqueuer.enqueueCreation(message.copy(workflowState = stamped), conn)
            }
        return processed
    }

    override suspend fun process(entity: EventScheduleModel) {
        // Creation events were enqueued with the row (EventScheduleService): only dispatch here.
        commandEmitter.send(entity.instanceMessage, entity.id)
        entity.cleanupAfter = Clock.System.now()
    }
}
