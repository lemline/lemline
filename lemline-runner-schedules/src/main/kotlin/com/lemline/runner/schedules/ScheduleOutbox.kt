// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.lifecycle.CreationEventEnqueuer
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.Duration
import org.jetbrains.annotations.VisibleForTesting

/**
 * `ScheduleOutbox` specializes `AbstractOutbox` to implement the outbox pattern for scheduled workflow executions.
 *
 * It handles publishing messages when a scheduled workflow is triggered.
 * Supported schedule types:
 * - cron: Schedules workflow execution based on a cron expression.
 * - every: Executes the workflow at fixed intervals.
 * - after: Triggers the workflow after a previous workflow completes,
 *   managed by ScheduleService when WorkflowEventHandler receives completion.
 */
@Startup
@ApplicationScoped
class ScheduleOutbox : AbstractOutbox<ScheduleModel>() {

    override val jobName: String get() = "Schedules outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var scheduleConfig: ScheduleConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var creationEventEnqueuer: CreationEventEnqueuer

    /** Clock used to accept due occurrences (their `workflow.created` time). */
    var clock: Clock = Clock.System

    override val outboxRepository: WithOutboxRepository<ScheduleModel> get() = scheduleRepository

    override val crudRepository: WithCrudRepository<ScheduleModel> get() = scheduleRepository

    // Is this outbox enabled?
    override val enabled by lazy { scheduleConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { scheduleConfig.outbox }

    /**
     * Two transactions per pass: due occurrences are first accepted (creation time frozen and
     * committed), then published. A publication retry or a crash after publishing therefore never
     * recomputes an occurrence's creation time.
     */
    override suspend fun processEntities(batchSize: Int, maxAttempts: Int, retryDelay: Duration) {
        acceptDueOccurrences(batchSize, maxAttempts)
        super.processEntities(batchSize, maxAttempts, retryDelay)
    }

    @VisibleForTesting
    internal suspend fun acceptDueOccurrences(batchSize: Int, maxAttempts: Int) = try {
        databaseConfig.withTransaction { conn ->
            val due = scheduleRepository.findEntitiesToProcess(maxAttempts, batchSize, conn).filter { !it.isAccepted }
            if (due.isNotEmpty()) {
                val now = clock.now()
                due.forEach { it.prepareExecution(now) }
                scheduleRepository.update(due, conn)
            }
        }
    } catch (e: Exception) {
        // Publication still accepts them inline; only the "frozen before publication" guarantee is lost.
        logger.error(e) { "Error while accepting due schedule occurrences" }
    }

    /**
     * Insert successors and creation lifecycle events on the same connection as the locked batch
     * and its final update. An insertion failure must escape the per-entity retry handler and roll
     * back the batch: otherwise a published occurrence could be committed without its successor or
     * its creation events. Occurrences whose publication failed get no creation events: they are
     * not dispatched yet.
     */
    override suspend fun processBatch(
        entities: List<ScheduleModel>,
        maxAttempts: Int,
        retryDelay: Duration,
        conn: Connection
    ): Int {
        val processed = super.processBatch(entities, maxAttempts, retryDelay, conn)
        for (entity in entities.filter { it.outboxCompletedAt != null }) {
            creationEventEnqueuer.enqueueCreation(entity.instanceMessage, conn)
            if (entity.scheduleAfter == null) {
                entity.nextScheduled()?.let { scheduleRepository.insert(it, conn) }
                entity.cleanupAfter = entity.outboxCompletedAt
            }
            // Completion-based schedules must survive cleanup until their workflow finishes.
        }
        return processed
    }

    override suspend fun process(entity: ScheduleModel) {
        // Normally accepted by acceptDueOccurrences(); a no-op then.
        entity.prepareExecution(clock.now())
        commandEmitter.send(entity.instanceMessage, entity.messageId)
    }
}
