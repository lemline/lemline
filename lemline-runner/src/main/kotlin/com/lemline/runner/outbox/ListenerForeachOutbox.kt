// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

import com.lemline.common.values.Token
import com.lemline.core.states.WorkflowCommand
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
import kotlinx.serialization.json.JsonObject

/**
 * ListenerForeachOutbox is a specialized implementation of AbstractOutbox for handling "foreach" processing pattern.
 *
 * Key Features:
 * - Batch processing with cache:
 *   - Overrides `processBatch` to batch-load listeners.
 *
 * - Event Transformation:
 *   - Applies "readAs" transformations to CloudEvents to extract data in formats like RAW, DATA, or ENVELOPE.
 *
 * - Message Emission:
 *   - Sends idempotent messages to trigger the processing of "foreach.do" tasks using derived message IDs.
 *
 *  Note: foreach_output will be eventually set in [WorkflowEventHandler::handleListenForEachCompleted]
 */
@Startup
@ApplicationScoped
@ExperimentalTime
@ExperimentalSerializationApi
internal class ListenerForeachOutbox : AbstractOutbox<ListenerEventModel>() {

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

    override val taskName = "Listener foreach processor"

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
     * Override to mark pending events as ready before processing.
     *
     * This calls [ListenerEventRepository.markReadyForForeach] to find the head (oldest by sort_key)
     * pending event for each listener that has pending events but no event currently being processed,
     * and marks it as ready by setting outbox_delayed_until = NOW.
     */
    override suspend fun processEntities(batchSize: Int, maxAttempts: Int, initialDelay: Duration) {
        // Mark pending events as ready for processing (FIFO head per listener)
        // Loop until no more events need to be marked, with safety limit
        var marked: Int
        var iterations = 0
        val maxIterations = 100
        do {
            marked = listenerEventRepository.markReadyForForeach(batchSize)
            iterations++
        } while (marked > 0 && iterations < maxIterations)

        if (iterations >= maxIterations) {
            logger.warn { "markReadyForForeach reached max iterations ($maxIterations), some events may be delayed" }
        }

        // Now process the marked entities
        super.processEntities(batchSize, maxAttempts, initialDelay)
    }

    /** Override to batch-load all listeners */
    override suspend fun processBatch(
        entities: List<ListenerEventModel>,
        maxAttempts: Int,
        initialDelay: Duration
    ): Int {
        // Batch-load all listeners in one query
        val listenerIds = entities.map { it.listenerId }.distinct()
        val listeners = listenerRepository.findByIds(listenerIds)

        return processEntitiesWith(entities, maxAttempts, initialDelay) { listenerEvent: ListenerEventModel ->
            process(listenerEvent, listeners[listenerEvent.listenerId]!!)
        }
    }

    /**
     * Process a single event by sending ResumeFromTask to execute foreach.do.
     * Applies the readAs transformation to the stored CloudEvent.
     */
    private suspend fun process(listenerEvent: ListenerEventModel, listener: ListenerModel) {
        val listenStarted = listener.instanceMessage.workflowState

        // Apply readAs transformation to stored CloudEvent
        val eventData = listener.applyReadAs(Json.parseToJsonElement(listenerEvent.event) as JsonObject)

        // Build the foreach.do position
        val listenPosition = listenStarted.nodePosition
        val foreachPosition = listenPosition.addToken(Token.FOR)

        // Create the resume command to execute foreach.do
        val resumeCommand = WorkflowCommand.ResumeFromTask(
            nodeStack = listenStarted.nodeStack,
            nodePosition = foreachPosition,
            rawInput = eventData
        )

        val resumeMessage = InstanceMessage(
            workflowInfo = listener.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        // Derive idempotent message ID from listener ID and event ID
        val messageId = listenerEvent.listenerId.derive("-foreach-${listenerEvent.eventId}-resume")

        instanceEmitter.send(resumeMessage, messageId)

        logger.info {
            "Foreach event (listenerId=${listenerEvent.listenerId}, eventId=${listenerEvent.eventId}) sent for processing"
        }
    }

    /**
     * Not used - this class overrides [processBatch] to use [process] instead.
     */
    override suspend fun process(entity: ListenerEventModel): Unit =
        error("process() should not be called directly; use processBatch() with local cache")
}
