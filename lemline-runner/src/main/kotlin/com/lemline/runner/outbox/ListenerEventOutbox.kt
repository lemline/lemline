// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.IDV7
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.models.ListenerEventModel
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ListenerEventRepository
import com.lemline.runner.repositories.ListenerRepository
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Outbox processor for foreach event processing (ALL / ANY+until strategies).
 *
 * Processes individual CloudEvents from the `lemline_listener_events` queue
 * for listeners with foreach enabled. Ensures sequential FIFO processing.
 *
 * ## How it works
 *
 * 1. Query for events with `outbox_delayed_until <= NOW()`
 *    (foreach_processing flag was already set by CloudEventHandler during insert)
 * 2. Load parent listener to get workflow state
 * 3. Send `ResumeFromTask` command to execute foreach.do with the event
 * 4. Mark event as processing started (update attempt count)
 *
 * When foreach.do completes:
 * - `ListenForEachCompleted` event triggers `WorkflowEventHandler`
 * - Handler marks event completed, checks for next event
 * - If next event: triggers it and keeps `foreach_processing = TRUE`
 * - If no more events and completed: aggregates outputs and completes listener
 * - If no more events and not completed: waits for more events
 *
 * ## FIFO Ordering
 *
 * Events are processed in order of arrival (`created_at ASC`).
 * Only one event per listener is processed at a time.
 *
 * ## Concurrency Safety
 *
 * The `foreach_processing` flag is set atomically by CloudEventHandler
 * when inserting the first event. This outbox does NOT set the flag -
 * it's already TRUE when we pick up the event.
 *
 * @see ListenerOutbox for ONE/ANY without foreach (simpler flow)
 * @see WorkflowEventHandler.handleListenForEachCompleted for completion handling
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerEventOutbox : AbstractOutbox<ListenerEventModel>() {

    @Inject
    private lateinit var lemlineConfig: LemlineConfiguration

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    @Inject
    override lateinit var instanceEmitter: WorkflowCommandEmitter

    @Inject
    override lateinit var databaseManager: DatabaseManager

    @Inject
    override lateinit var failureRepository: FailureRepository

    override val outboxRepository get() = listenerEventRepository

    override val crudRepository get() = listenerEventRepository

    override val taskName = "Listener event foreach processor"

    /** Is this processor enabled? */
    override val enabled by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.enabled()?.getOrNull()
            ?: lemlineConfig.outbox().enabled().getOrNull()
            ?: lemlineConfig.messaging().commands().getOrNull()?.consumer()?.enabled() ?: false
    }

    /** Outbox processing configuration */
    override val outboxConf by lazy { lemlineConfig.outbox().listener().getOrNull()?.outbox() }

    /** Cleanup configuration */
    override val cleanerConf by lazy {
        lemlineConfig.outbox().listener().getOrNull()?.cleanup()
            ?: lemlineConfig.outbox().wait().cleanup()
    }

    /**
     * Override to batch-load all listeners before processing entities.
     * This avoids N+1 queries when processing a batch of events.
     * Uses a local cache scoped to this batch invocation to avoid race conditions
     * when multiple batches run in parallel with overlapping listener IDs.
     */
    override suspend fun processBatch(
        entities: List<ListenerEventModel>,
        maxAttempts: Int,
        initialDelay: Duration
    ): Int {
        // Batch-load all listeners in one query - local to this invocation
        val listenerIds = entities.map { it.listenerId }.distinct()
        val listenerCache = listenerRepository.findByIds(listenerIds)

        return processEntitiesWith(entities, maxAttempts, initialDelay) { entity ->
            processWithCache(entity, listenerCache)
        }
    }

    /**
     * Process a single event by sending ResumeFromTask to execute foreach.do.
     */
    private suspend fun processWithCache(entity: ListenerEventModel, listenerCache: Map<IDV7, ListenerModel>) {
        val listener = listenerCache[entity.listenerId]
            ?: throw IllegalStateException("Listener ${entity.listenerId} not found for ListenerEventModel $entity")

        val listenStarted = listener.instanceMessage.workflowState
        val eventData = Json.parseToJsonElement(entity.event)

        val resumeMessage = InstanceMessage(
            workflowInfo = listener.workflowInfo,
            workflowState = listenStarted.resumeForeach(eventData, entity.iterationIndex)
        )

        // Derive idempotent message ID from event ID
        val messageId = entity.id.derive("-foreach-resume")

        instanceEmitter.send(resumeMessage, messageId)

        logger.info {
            "Foreach event ${entity.id} sent for processing, " +
                "listener ${entity.listenerId}, iteration ${entity.iterationIndex}"
        }
    }

    /**
     * Not used - this class overrides [processBatch] to use [processWithCache] instead.
     */
    override suspend fun process(entity: ListenerEventModel): Unit =
        error("process() should not be called directly; use processBatch() with local cache")
}
