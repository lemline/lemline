// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.outbox

import com.lemline.core.states.WorkflowCommand
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.listeners.ListenerEventRepository
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Outbox processor for listener completions.
 *
 * ## Simplified Architecture
 *
 * This outbox handles the final step: completing the listen task after
 * all events have been processed (including foreach iterations if applicable).
 *
 * Listeners are picked up when `outbox_delayed_until` is set (ready for processing).
 *
 * Note: `completed_at` being set only means the listener stopped collecting events.
 * The outbox waits for `outbox_delayed_until` which is set after all foreach processing
 * completes (or immediately for non-foreach listeners).
 *
 * ## How it works
 *
 * Before each batch, this outbox calls `batchMarkReady()` to:
 * 1. Check completion criteria for each strategy
 * 2. For non-foreach: Set both `completed_at` and `outbox_delayed_until`
 * 3. For foreach: Set `completed_at` only; `outbox_delayed_until` set later after foreach completes
 *
 * Then it processes ready listeners:
 * 1. Aggregates foreach outputs (if applicable) from listener_events
 * 2. Sends `ResumeWithCompletedTask` command with the aggregated output
 * 3. Marks listener for cleanup
 *
 * ## Completion Criteria
 *
 * | Strategy | Completion Condition |
 * |----------|---------------------|
 * | ONE/ANY | One event with `outbox_completed_at IS NOT NULL` |
 * | ALL | All filters matched (`COUNT(DISTINCT filter_index) >= filters_count`) |
 * | ANY_UNTIL_EXPR | Until expression evaluates to true (handled by ListenerRepository) |
 * | ANY_UNTIL_EVENT | Termination event received (handled by CloudEventHandler) |
 *
 * @see AbstractOutbox for base outbox pattern implementation
 * @see ListenerForeachOutbox for foreach.do processing
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
class ListenerOutbox : AbstractOutbox<ListenerModel>() {

    override val jobName = "Listeners outbox"

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var listenerFeatureConfig: ListenerConfig

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var listenerRepository: ListenerRepository

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    override val outboxRepository: WithOutboxRepository<ListenerModel> get() = listenerRepository

    override val crudRepository: WithCrudRepository<ListenerModel> get() = listenerRepository

    /** Is this outbox enabled? */
    override val enabled by lazy { listenerFeatureConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { listenerFeatureConfig.outbox }

    /**
     * Override doWork to check completion criteria before processing.
     *
     * This calls `batchMarkReady()` to mark eligible listeners as ready,
     * evaluates until expressions for ANY_UNTIL_EXPR listeners,
     * checks terminated ANY_UNTIL_EVENT listeners,
     * then delegates to the standard outbox processing.
     */
    override suspend fun doWork() {
        // Mark eligible ONE/ANY/ALL listeners as completed
        val markedCompleted = listenerRepository.batchPrepareListenerOutbox()
        if (markedCompleted > 0) {
            logger.debug { "Marked $markedCompleted listeners as ready for outbox" }
        }

        // Process ready listeners via standard outbox flow
        super.doWork()
    }

    /**
     * Override processBatch to batch-load all completed outputs in a single query,
     * avoiding N+1 database queries during completion processing.
     */
    override suspend fun processBatch(
        entities: List<ListenerModel>,
        maxAttempts: Int,
        retryDelay: Duration
    ): Int {
        val listenerIds = entities.map { it.id }
        val outputsByListener = listenerEventRepository.findCompletedOutputsByListeners(listenerIds)

        return processEntitiesWith(entities, maxAttempts, retryDelay) { listener ->
            process(listener, outputsByListener[listener.id] ?: emptyList())
        }
    }

    private suspend fun process(entity: ListenerModel, completedOutputs: List<String>) {
        val outputArray = JsonArray(completedOutputs.map { output ->
            val parsed = if (entity.hasForeach) {
                Json.parseToJsonElement(output)
            } else {
                CloudEventService.parseStringAsData(output, entity.readAs)
            }
            logger.debug { "Processing $parsed" }
            parsed
        })

        val resumeCommand = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = entity.instanceMessage.workflowState.nodeStack,
            rawOutput = outputArray
        )

        val resumeMessage = InstanceMessage(
            workflowInfo = entity.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        val messageId = entity.id.derive("-listen-complete")

        commandEmitter.send(resumeMessage, messageId)

        logger.info { "Listen completion sent for listener ${entity.id}" }

        entity.cleanupAfter = Clock.System.now()
    }

    /**
     * Not used - this class overrides processBatch() to use process() with pre-loaded data.
     */
    override suspend fun process(entity: ListenerModel): Unit =
        error("process() should not be called directly; use processBatch() with batch-loaded outputs")
}
