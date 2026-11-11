// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Duration

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
    lateinit var lifecycleHook: LifecycleEventHook

    override val outboxRepository: WithOutboxRepository<ScheduleModel> get() = scheduleRepository

    override val crudRepository: WithCrudRepository<ScheduleModel> get() = scheduleRepository

    // Is this outbox enabled?
    override val enabled by lazy { scheduleConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { scheduleConfig.outbox }

    /**
     * Insert successors on the same connection as the locked batch and its final update.
     * An insertion failure must escape the per-entity retry handler and roll back the batch:
     * otherwise a published occurrence could be committed without its successor.
     */
    override suspend fun processBatch(
        entities: List<ScheduleModel>,
        maxAttempts: Int,
        retryDelay: Duration,
        conn: Connection
    ): Int {
        val processed = super.processBatch(entities, maxAttempts, retryDelay, conn)
        for (entity in entities.filter { it.outboxCompletedAt != null }) {
            if (entity.scheduleAfter == null) {
                entity.nextScheduled()?.let { scheduleRepository.insert(it, conn) }
                entity.cleanupAfter = entity.outboxCompletedAt
            }
            // Completion-based schedules must survive cleanup until their workflow finishes.
        }
        return processed
    }

    override suspend fun process(entity: ScheduleModel) {
        entity.prepareExecution()
        commandEmitter.send(entity.instanceMessage, entity.messageId)
        lifecycleHook.onWorkflowCreated(entity.instanceMessage.workflowInfo, entity.instanceMessage.workflowState.nodeStack)
    }
}
