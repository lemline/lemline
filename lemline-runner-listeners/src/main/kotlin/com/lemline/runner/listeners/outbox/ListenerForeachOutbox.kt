// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners.outbox

import com.lemline.core.nodes.ForeachTask
import com.lemline.core.nodes.Node
import com.lemline.core.states.ForeachState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.foreachBlock
import com.lemline.core.workflows.getNode
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.OutboxConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.outbox.AbstractOutbox
import com.lemline.runner.common.repositories.with.WithCrudRepository
import com.lemline.runner.common.repositories.with.WithOutboxRepository
import com.lemline.runner.listeners.CloudEventService
import com.lemline.runner.listeners.ListenerConfig
import com.lemline.runner.listeners.ListenerEventModel
import com.lemline.runner.listeners.ListenerEventRepository
import com.lemline.runner.listeners.ListenerModel
import com.lemline.runner.listeners.ListenerRepository
import io.quarkus.runtime.Startup
import io.serverlessworkflow.api.types.ListenTask
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

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
class ListenerForeachOutbox : AbstractOutbox<ListenerEventModel>() {

    override val jobName = "Listener foreach outbox"

    @Inject
    lateinit var listenerFeatureConfig: ListenerConfig

    @Inject
    private lateinit var listenerEventRepository: ListenerEventRepository

    @Inject
    private lateinit var listenerRepository: ListenerRepository

    @Inject
    override lateinit var commandEmitter: CommandEmitter

    @Inject
    override lateinit var databaseConfig: DatabaseConfig

    override val outboxRepository: WithOutboxRepository<ListenerEventModel> get() = listenerEventRepository

    override val crudRepository: WithCrudRepository<ListenerEventModel> get() = listenerEventRepository

    /** Is this processor enabled? */
    override val enabled by lazy { listenerFeatureConfig.enabled }

    override val outboxConfig: OutboxConfig by lazy { listenerFeatureConfig.outbox }

    /**
     * Override to mark pending events as ready before processing.
     *
     * This calls [ListenerEventRepository.markReadyForForeach] to find the head (oldest by sort_key)
     * pending event for each listener that has pending events but no event currently being processed,
     * and marks it as ready by setting outbox_delayed_until = NOW.
     */
    override suspend fun processEntities(batchSize: Int, maxAttempts: Int, retryDelay: Duration) {
        // Mark pending events as ready for processing (FIFO head per listener)
        // Loop until no more events need to be marked, with safety limit
        var marked: Int
        var iterations = 0
        val maxIterations = 100
        do {
            marked = listenerEventRepository.markReadyForForeach(batchSize)
            if (marked > 0 || iterations == 0) logger.debug { "$marked events marked for foreach" }
            iterations++
        } while (marked > 0 && iterations < maxIterations)

        if (iterations >= maxIterations) {
            logger.warn { "markReadyForForeach reached max iterations ($maxIterations), some events may be delayed" }
        }

        // Now process the marked entities
        super.processEntities(batchSize, maxAttempts, retryDelay)
    }

    /** Override to batch-load all listeners */
    override suspend fun processBatch(
        entities: List<ListenerEventModel>,
        maxAttempts: Int,
        retryDelay: Duration,
        conn: Connection
    ): Int {
        // Validate no duplicate listenerIds - each listener should have at most one event per batch
        val listenerIds = entities.map { it.listenerId }
        require(listenerIds.size == listenerIds.distinct().size) {
            "Duplicate listenerIds in batch: ${
                entities.groupingBy { it.listenerId }.eachCount().filter { it.value > 1 }.keys
            }"
        }

        // Batch-load all listeners in one query using the transaction connection
        val listeners = listenerRepository.findByIds(listenerIds, conn)

        return processEntitiesWith(entities, maxAttempts, retryDelay) { listenerEvent ->
            process(listenerEvent, listeners[listenerEvent.listenerId]!!)
        }
    }

    private suspend fun process(listenerEvent: ListenerEventModel, listener: ListenerModel) {
        val listenStarted = listener.instanceMessage.workflowState

        val eventData = CloudEventService.parseStringAsData(listenerEvent.event, listener.readAs)

        val workflow = WorkflowCache.getWorkflow(
            namespace = listener.workflowNamespace,
            name = listener.workflowName,
            version = listener.workflowVersion
        ) ?: error("Workflow ${listener.workflowInfo} not found in cache")

        @Suppress("UNCHECKED_CAST")
        val listenNode = workflow.getNode(listenStarted.nodePosition) as Node<ListenTask>
        val foreachNode = listenNode.foreachBlock
            ?: error("Listen task at ${listenStarted.nodePosition} has no foreach block")
        val foreachTask = foreachNode.task as ForeachTask

        val foreachState = ForeachState(
            item = eventData,
            index = listenerEvent.sortKey,
            itemVar = foreachTask.itemVar,
            indexVar = foreachTask.indexVar,
        )

        val updatedNodeStack = listenStarted.nodeStack.push(
            position = foreachNode.position,
            state = foreachState,
            executionIndex = listenerEvent.sortKey
        )

        val resumeCommand = WorkflowCommand.ResumeFromTask(
            nodeStack = updatedNodeStack,
            nodePosition = foreachNode.position,
            rawInput = eventData
        )

        val resumeMessage = InstanceMessage(
            workflowInfo = listener.instanceMessage.workflowInfo,
            workflowState = resumeCommand
        )

        val messageId = listener.id.derive("-foreach-${listenerEvent.eventId}-resume")

        commandEmitter.send(resumeMessage, messageId)

        logger.debug {
            "Foreach event (listenerId=${listenerEvent.listenerId}, event=${listenerEvent.event}) sent for processing"
        }
    }

    /**
     * Not used - this class overrides [processBatch] to use [process] instead.
     */
    override suspend fun process(entity: ListenerEventModel): Unit =
        error("process() should not be called directly; use processBatch() with local cache")
}
